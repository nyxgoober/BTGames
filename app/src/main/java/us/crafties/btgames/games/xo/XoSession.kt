package us.crafties.btgames.games.xo

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

/** Everything the XO screen draws. */
data class XoUi(
    val board: XoBoard,
    val myMark: Mark,
    val myNick: String,
    val theirNick: String,
    /** Which colour I am. Independent of whether I play X or O. */
    val mySide: Side,
    val round: Int,
    /** I have asked for a rematch and am waiting for the other player. */
    val iWantRematch: Boolean,
    /** The other player has asked for a rematch. */
    val theyWantRematch: Boolean,
    val myWins: Int,
    val theirWins: Int,
    val draws: Int,
) {
    val myTurn: Boolean get() = !board.isOver && board.turn == myMark
    val outcomeForMe: Result? get() = when (board.outcome) {
        Outcome.IN_PROGRESS -> null
        Outcome.DRAW -> Result.DRAW
        Outcome.X_WINS -> if (myMark == Mark.X) Result.WIN else Result.LOSS
        Outcome.O_WINS -> if (myMark == Mark.O) Result.WIN else Result.LOSS
    }
}

enum class Result { WIN, LOSS, DRAW }

/**
 * One XO match series. Both phones hold the board and validate everything they receive.
 *
 * Rules of the road:
 *  - The phone that plays first is X and moves first in round 0 (the requester in an ordinary
 *    request, or whoever was chosen when both phones asked at once).
 *  - After every move the mover sends the WHOLE board ([XoCodec.Msg.State]).
 *  - A received board is accepted only if it strictly advances ours, keeps every mark we
 *    already have, and adds only the opponent's mark. Duplicates and stale packets are harmless.
 *  - While a game is unfinished each phone re-sends its board every few seconds, so a lost
 *    packet heals itself without the players noticing.
 *  - A rematch starts only when BOTH players ask for the same round. The first mover alternates.
 */
class XoSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
) : GameSession {

    /** X moves first in round 0, so the phone that plays first is X. */
    private val myMark: Mark = if (playsFirst) Mark.X else Mark.O

    private var round = 0
    private var board = XoBoard(firstMover = Mark.X)
    private var iWantRematch = false
    private var theyWantRematch = false
    private var myWins = 0
    private var theirWins = 0
    private var draws = 0
    private var scored = false // has the finished board of this round been counted yet?

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<XoUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var resender: Job? = null

    override fun start() {
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) XoCodec.decode(msg.payload)?.let(::onMessage)
            }
        }
        // Periodic resend of whatever we believe the state is. Cheap (12 bytes) and self-healing.
        resender = scope.launch {
            while (true) {
                delay(RESEND_MS)
                if (!board.isOver && board.moveCount > 0) sendState()
                if (iWantRematch && board.isOver) transport.sendControl(Control.Game(XoCodec.encodeRematch(round + 1)))
            }
        }
    }

    override fun stop() {
        listeners?.cancel(); resender?.cancel()
    }

    // ---- local input --------------------------------------------------------------------

    fun tapCell(cell: Int) {
        val next = board.play(cell, myMark) ?: return // not my turn, occupied, or game over
        board = next
        sendState()
        afterBoardChange()
    }

    fun requestRematch() {
        if (!board.isOver || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(XoCodec.encodeRematch(round + 1)))
        maybeStartRematch()
        publish()
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: XoCodec.Msg) {
        when (m) {
            is XoCodec.Msg.State -> onState(m)
            is XoCodec.Msg.Rematch -> {
                if (m.round == round + 1 && board.isOver) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    private fun onState(m: XoCodec.Msg.State) {
        when {
            m.round < round -> return // late packet from a game we have already finished
            m.round > round -> {
                // We missed the rematch handshake (e.g. it was lost). Their round is authoritative
                // only if it is the very next one and our game is over, otherwise ignore.
                if (m.round != round + 1 || !board.isOver) return
                startRound(m.round, m.board.firstMover)
            }
        }
        if (!advances(m.board)) return
        board = m.board
        afterBoardChange()
    }

    /**
     * True if [incoming] is a legitimate next step from our board: more marks than we have,
     * none of ours rewritten, and every new mark belongs to the opponent.
     */
    private fun advances(incoming: XoBoard): Boolean {
        if (incoming.firstMover != board.firstMover) return false
        if (incoming.moveCount <= board.moveCount) return false
        val theirMark = myMark.other()
        for (i in 0..8) {
            val mine = board.cells[i]
            val inc = incoming.cells[i]
            if (mine != Mark.EMPTY && inc != mine) return false   // would rewrite an existing mark
            if (mine == Mark.EMPTY && inc != Mark.EMPTY && inc != theirMark) return false
        }
        return true
    }

    // ---- rounds and scoring -------------------------------------------------------------

    private fun maybeStartRematch() {
        if (iWantRematch && theyWantRematch) {
            // Alternate who starts so the same phone doesn't always get the advantage.
            startRound(round + 1, board.firstMover.other())
        }
    }

    private fun startRound(newRound: Int, firstMover: Mark) {
        round = newRound
        board = XoBoard(firstMover = firstMover)
        iWantRematch = false
        theyWantRematch = false
        scored = false
        publish()
    }

    private fun afterBoardChange() {
        if (board.isOver && !scored) {
            scored = true
            when (board.outcome) {
                Outcome.DRAW -> draws++
                Outcome.X_WINS -> if (myMark == Mark.X) myWins++ else theirWins++
                Outcome.O_WINS -> if (myMark == Mark.O) myWins++ else theirWins++
                Outcome.IN_PROGRESS -> Unit
            }
        }
        publish()
    }

    private fun sendState() {
        transport.sendControl(Control.Game(XoCodec.encodeState(round, board)))
    }

    private fun snapshot() = XoUi(
        board = board, myMark = myMark, myNick = nickMe, theirNick = nickThem, mySide = mySide, round = round,
        iWantRematch = iWantRematch, theyWantRematch = theyWantRematch,
        myWins = myWins, theirWins = theirWins, draws = draws,
    )

    private fun publish() { _ui.value = snapshot() }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        XoScreen(state = state, onCell = ::tapCell, onRematch = ::requestRematch, onLeave = onLeave)
    }

    private companion object {
        const val RESEND_MS = 2500L
    }
}
