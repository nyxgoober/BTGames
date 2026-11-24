package us.crafties.btgames.games.chess

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameSession

/** Everything the Chess screen draws. */
data class ChessUi(
    val board: ChessBoard,
    /** True when I play White this round. Independent of [mySide] (red/blue). */
    val whiteMe: Boolean,
    /** Selected square, or -1. Local only, never crosses the wire. */
    val selected: Int,
    /** The move that produced the current board, if we can tell. */
    val last: ChessMove?,
    /** Half-moves played: the screen thuds once per new one. */
    val ply: Int,
    val myNick: String,
    val theirNick: String,
    /** Which colour I am. Independent of whether I play White. */
    val mySide: Side,
    val round: Int,
    val promotionPending: Boolean,
    val repetitionDraw: Boolean,
    /** I have asked for a rematch and am waiting for the other player. */
    val iWantRematch: Boolean,
    /** The other player has asked for a rematch. */
    val theyWantRematch: Boolean,
    val myWins: Int,
    val theirWins: Int,
    val draws: Int,
) {
    val myChessSide: ChessSide get() = if (whiteMe) ChessSide.WHITE else ChessSide.BLACK

    /** Black starts at the top of the shared board; turn it so my pieces are always nearest me. */
    val flipped: Boolean get() = !whiteMe
    val myTurn: Boolean get() = !isOver && board.turn == myChessSide
    val isOver: Boolean get() = board.checkmate || board.stalemate || board.drawByFiftyMoves || repetitionDraw

    val outcomeForMe: ChessResult? get() = when {
        board.checkmate -> if (board.turn == myChessSide) ChessResult.LOSS else ChessResult.WIN
        board.stalemate || board.drawByFiftyMoves || repetitionDraw -> ChessResult.DRAW
        else -> null
    }
}

enum class ChessResult { WIN, LOSS, DRAW }

/**
 * One Chess series. Both phones hold the board and validate everything they receive,
 * like XO and Checkers.
 *
 * Rules of the road:
 *  - Round 0: the phone that plays first is White. White swaps every rematch.
 *  - After every move the mover sends the WHOLE board ([ChessCodec.Msg.State]).
 *  - A received board is accepted only if it is one legal move from ours
 *    ([ChessBoard.legalMoves]). Duplicates and stale packets are harmless.
 *  - While a round is unfinished each phone re-sends its board every few seconds, so a lost
 *    packet heals itself without the players noticing.
 *  - A rematch starts only when BOTH players ask for the same round.
 */
class ChessSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
) : GameSession {

    private var round = 0
    private var board = ChessBoard()
    private var selected = -1
    private var last: ChessMove? = null
    private var ply = 0
    private var pendingPromotion: Pair<Int, Int>? = null
    private val repetitions = mutableMapOf(board.positionKey() to 1)
    private var repetitionDraw = false
    private var iWantRematch = false
    private var theyWantRematch = false
    private var myWins = 0
    private var theirWins = 0
    private var draws = 0
    private var scored = false // has the finished board of this round been counted yet?

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<ChessUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var resender: Job? = null

    /** Whether I play White in [r]: the requester on even rounds, the other phone on odd ones. */
    private fun whiteMeIn(r: Int): Boolean = if (r % 2 == 0) playsFirst else !playsFirst

    private fun whiteMe(): Boolean = whiteMeIn(round)

    private fun isOver(): Boolean = board.checkmate || board.stalemate || board.drawByFiftyMoves || repetitionDraw

    /** True once any move has been played (the initial position is not worth resending). */
    private fun moved(): Boolean = board.turn == ChessSide.BLACK || board.fullmove > 1

    override fun start() {
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) ChessCodec.decode(msg.payload)?.let(::onMessage)
            }
        }
        // Periodic resend of whatever we believe the state is. Cheap (39 bytes) and self-healing.
        resender = scope.launch {
            while (true) {
                delay(RESEND_MS)
                if (!isOver() && moved()) sendState()
                if (iWantRematch && isOver()) {
                    transport.sendControl(Control.Game(ChessCodec.encodeRematch(round + 1)))
                }
            }
        }
    }

    override fun stop() {
        listeners?.cancel()
        resender?.cancel()
    }

    // ---- local input --------------------------------------------------------------------

    private fun myChessSide(): ChessSide = if (whiteMe()) ChessSide.WHITE else ChessSide.BLACK

    fun tap(square: Int) {
        if (square !in 0..63 || isOver() || pendingPromotion != null || board.turn != myChessSide()) {
            selected = -1
            publish()
            return
        }
        if (selected >= 0) {
            val options = board.movesFrom(selected).filter { it.to == square }
            if (options.isNotEmpty()) {
                if (options.any { it.promotion != 0 }) {
                    pendingPromotion = selected to square
                    publish()
                    return
                }
                play(options.first())
                return
            }
        }
        selected = if (board.cells[square].side == board.turn && board.movesFrom(square).isNotEmpty()) {
            square
        } else {
            -1
        }
        publish()
    }

    fun promote(pieceType: Int) {
        val pending = pendingPromotion ?: return
        if (pieceType !in listOf(5, 4, 3, 2)) return
        val move = board.movesFrom(pending.first)
            .firstOrNull { it.to == pending.second && it.promotion == pieceType } ?: return
        pendingPromotion = null
        play(move)
    }

    /**
     * Drag and drop: play the move from [from] to [to] when it is legal. Returns true only when a
     * move was actually played, so the screen can skip the glide (the piece is already under the
     * finger). A drop that would promote opens the chooser instead and returns false, leaving the
     * pawn where it is; the glide plays once the player has picked the piece.
     */
    fun drop(from: Int, to: Int): Boolean {
        if (from !in 0..63 || to !in 0..63 || from == to || isOver() ||
            pendingPromotion != null || board.turn != myChessSide()
        ) {
            return false
        }
        val options = board.movesFrom(from).filter { it.to == to }
        if (options.isEmpty()) return false
        if (options.any { it.promotion != 0 }) {
            pendingPromotion = from to to
            publish()
            return false
        }
        play(options.first())
        return true
    }

    fun requestRematch() {
        if (!isOver() || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(ChessCodec.encodeRematch(round + 1)))
        maybeStartRematch()
        publish()
    }

    private fun play(move: ChessMove) {
        board = board.apply(move) ?: return
        last = move
        selected = -1
        ply++
        recordPosition()
        sendState()
        afterBoardChange()
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: ChessCodec.Msg) {
        when (m) {
            is ChessCodec.Msg.State -> onState(m)
            is ChessCodec.Msg.Rematch -> {
                if (m.round == round + 1 && isOver()) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    private fun onState(m: ChessCodec.Msg.State) {
        when {
            m.round < round -> return // late packet from a round we have already finished
            m.round > round -> {
                // We missed the rematch handshake (e.g. it was lost). Their round is authoritative
                // only if it is the very next one and our round is over, otherwise ignore.
                if (m.round != round + 1 || !isOver()) return
                startRound(m.round)
            }
        }
        // Accept only a direct legal successor of our board.
        val move = board.legalMoves().firstOrNull { board.apply(it) == m.board } ?: return
        board = m.board
        last = move
        selected = -1
        pendingPromotion = null
        ply++
        recordPosition()
        afterBoardChange()
    }

    private fun recordPosition() {
        val key = board.positionKey()
        val count = (repetitions[key] ?: 0) + 1
        repetitions[key] = count
        if (count >= 3) repetitionDraw = true
    }

    // ---- rounds and scoring -------------------------------------------------------------

    private fun maybeStartRematch() {
        if (iWantRematch && theyWantRematch) startRound(round + 1)
    }

    private fun startRound(newRound: Int) {
        round = newRound
        board = ChessBoard()
        selected = -1
        last = null
        ply = 0
        pendingPromotion = null
        repetitions.clear()
        repetitions[board.positionKey()] = 1
        repetitionDraw = false
        iWantRematch = false
        theyWantRematch = false
        scored = false
        publish()
    }

    private fun afterBoardChange() {
        if (isOver() && !scored) {
            scored = true
            if (board.checkmate) {
                // The side to move is mated.
                if (board.turn == myChessSide()) theirWins++ else myWins++
            } else {
                draws++ // stalemate, fifty-move, or threefold repetition
            }
        }
        publish()
    }

    private fun sendState() {
        transport.sendControl(Control.Game(ChessCodec.encodeState(round, board)))
    }

    private fun snapshot() = ChessUi(
        board = board,
        whiteMe = whiteMe(),
        selected = selected,
        last = last,
        ply = ply,
        myNick = nickMe,
        theirNick = nickThem,
        mySide = mySide,
        round = round,
        promotionPending = pendingPromotion != null,
        repetitionDraw = repetitionDraw,
        iWantRematch = iWantRematch,
        theyWantRematch = theyWantRematch,
        myWins = myWins,
        theirWins = theirWins,
        draws = draws,
    )

    private fun publish() {
        _ui.value = snapshot()
    }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        ChessScreen(
            state = state,
            onCell = ::tap,
            onDrop = ::drop,
            onPromote = ::promote,
            onRematch = ::requestRematch,
            onLeave = onLeave,
        )
    }

    private companion object {
        const val RESEND_MS = 2500L
    }
}
