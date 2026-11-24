package us.crafties.btgames.games.fourchips

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

/** Everything the 4 Chips screen draws. */
data class FourChipsUi(
    val board: FourChipsBoard,
    /** My discs this round: FIRST when I move first, SECOND otherwise. Colour is separate. */
    val myMark: Chip,
    val myNick: String,
    val theirNick: String,
    /** Which colour I am. Independent of whether I move first. */
    val mySide: Side,
    val round: Int,
    /** Counts every applied drop (mine and theirs): the screen animates each new one falling. */
    val dropSeq: Int,
    /** Where the newest drop landed. */
    val dropCol: Int,
    val dropRow: Int,
    /** I have asked for a rematch and am waiting for the other player. */
    val iWantRematch: Boolean,
    /** The other player has asked for a rematch. */
    val theyWantRematch: Boolean,
    val myWins: Int,
    val theirWins: Int,
    val draws: Int,
) {
    val myTurn: Boolean get() = !board.isOver && board.turn == myMark
    val outcomeForMe: FourResult? get() = when (board.outcome) {
        FourOutcome.IN_PROGRESS -> null
        FourOutcome.DRAW -> FourResult.DRAW
        FourOutcome.FIRST_WINS -> if (myMark == Chip.FIRST) FourResult.WIN else FourResult.LOSS
        FourOutcome.SECOND_WINS -> if (myMark == Chip.SECOND) FourResult.WIN else FourResult.LOSS
    }
}

enum class FourResult { WIN, LOSS, DRAW }

/**
 * A 4 Chips series. Both phones hold the board and validate everything they receive, like XO.
 *
 * Rules of the road:
 *  - Round 0 is started by whoever asked for the match; the starter alternates every rematch,
 *    since moving first is a real edge here. The starter plays FIRST.
 *  - After every drop the dropper sends the WHOLE packed board ([FourChipsCodec.Msg.State]).
 *  - A received board is accepted only if it strictly advances ours, keeps every disc we
 *    already have, and adds only the opponent's discs. Duplicates and stale packets are harmless.
 *  - While a round is unfinished each phone re-sends its board every few seconds, so a lost
 *    packet heals itself without the players noticing.
 *  - A rematch starts only when BOTH players ask for the same round.
 */
class FourChipsSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
) : GameSession {

    private var round = 0
    private var board = FourChipsBoard(firstMover = firstMark(0))
    private var dropSeq = 0
    private var dropCol = -1
    private var dropRow = -1
    private var iWantRematch = false
    private var theyWantRematch = false
    private var myWins = 0
    private var theirWins = 0
    private var draws = 0
    private var scored = false // has the finished board of this round been counted yet?

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<FourChipsUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var resender: Job? = null

    /** My mark this round: FIRST exactly when I move first. */
    private fun myMark(): Chip {
        val first = firstMark(round)
        return if ((round % 2 == 0) == playsFirst) first else first.other()
    }

    /**
     * Which mark moves first in [r]: FIRST on even rounds, SECOND on odd ones. This is a
     * property of the shared board, so it must NOT depend on [playsFirst] — deriving it
     * per-phone made the two sides disagree, reject each other's opening drop, and wait
     * forever. Who moves first still alternates: the requester on even rounds, the other
     * phone on odd ones.
     */
    private fun firstMark(r: Int): Chip = if (r % 2 == 0) Chip.FIRST else Chip.SECOND

    override fun start() {
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) FourChipsCodec.decode(msg.payload)?.let(::onMessage)
            }
        }
        // Periodic resend of whatever we believe the state is. Cheap (14 bytes) and self-healing.
        resender = scope.launch {
            while (true) {
                delay(RESEND_MS)
                if (!board.isOver && board.moveCount > 0) sendState()
                if (iWantRematch && board.isOver) transport.sendControl(Control.Game(FourChipsCodec.encodeRematch(round + 1)))
            }
        }
    }

    override fun stop() {
        listeners?.cancel(); resender?.cancel()
    }

    // ---- local input --------------------------------------------------------------------

    fun dropDisc(col: Int) {
        val next = board.drop(col, myMark()) ?: return // not my turn, full, or round over
        board = next.board
        dropCol = col
        dropRow = next.row
        dropSeq++
        sendState()
        afterBoardChange()
    }

    fun requestRematch() {
        if (!board.isOver || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(FourChipsCodec.encodeRematch(round + 1)))
        maybeStartRematch()
        publish()
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: FourChipsCodec.Msg) {
        when (m) {
            is FourChipsCodec.Msg.State -> onState(m)
            is FourChipsCodec.Msg.Rematch -> {
                if (m.round == round + 1 && board.isOver) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    private fun onState(m: FourChipsCodec.Msg.State) {
        when {
            m.round < round -> return // late packet from a round we have already finished
            m.round > round -> {
                // We missed the rematch handshake (e.g. it was lost). Their round is authoritative
                // only if it is the very next one and our round is over, otherwise ignore.
                if (m.round != round + 1 || !board.isOver) return
                startRound(m.round, m.board.firstMover)
            }
        }
        if (!advances(m.board)) return
        // A received drop animates like a local one.
        val landed = latestDrop(m.board)
        board = m.board
        if (landed != null) {
            dropCol = landed.first
            dropRow = landed.second
            dropSeq++
        }
        afterBoardChange()
    }

    /** The single new disc in [incoming] versus our board, or null if there is not exactly one. */
    private fun latestDrop(incoming: FourChipsBoard): Pair<Int, Int>? {
        var found: Pair<Int, Int>? = null
        for (i in incoming.cells.indices) {
            if (board.cells[i] == Chip.EMPTY && incoming.cells[i] != Chip.EMPTY) {
                if (found != null) return null
                found = (i % FourChipsBoard.COLS) to (i / FourChipsBoard.COLS)
            }
        }
        return found
    }

    /**
     * True if [incoming] is a legitimate next step from our board: more discs than we have,
     * none of ours rewritten, every new disc the opponent's, and the whole thing reachable.
     * (Reachability was already checked by the codec; the gravity check lives there too.)
     */
    private fun advances(incoming: FourChipsBoard): Boolean {
        if (incoming.firstMover != board.firstMover) return false
        if (incoming.moveCount <= board.moveCount) return false
        val theirMark = myMark().other()
        for (i in 0 until FourChipsBoard.COLS * FourChipsBoard.ROWS) {
            val mine = board.cells[i]
            val inc = incoming.cells[i]
            if (mine != Chip.EMPTY && inc != mine) return false // would rewrite an existing disc
            if (mine == Chip.EMPTY && inc != Chip.EMPTY && inc != theirMark) return false
        }
        return true
    }

    // ---- rounds and scoring -------------------------------------------------------------

    private fun maybeStartRematch() {
        if (iWantRematch && theyWantRematch) {
            startRound(round + 1, firstMark(round + 1))
        }
    }

    private fun startRound(newRound: Int, firstMover: Chip) {
        round = newRound
        board = FourChipsBoard(firstMover = firstMover)
        dropCol = -1
        dropRow = -1
        iWantRematch = false
        theyWantRematch = false
        scored = false
        publish()
    }

    private fun afterBoardChange() {
        if (board.isOver && !scored) {
            scored = true
            val mine = myMark()
            when (board.outcome) {
                FourOutcome.DRAW -> draws++
                FourOutcome.FIRST_WINS -> if (mine == Chip.FIRST) myWins++ else theirWins++
                FourOutcome.SECOND_WINS -> if (mine == Chip.SECOND) myWins++ else theirWins++
                FourOutcome.IN_PROGRESS -> Unit
            }
        }
        publish()
    }

    private fun sendState() {
        transport.sendControl(Control.Game(FourChipsCodec.encodeState(round, board)))
    }

    private fun snapshot() = FourChipsUi(
        board = board, myMark = myMark(), myNick = nickMe, theirNick = nickThem, mySide = mySide,
        round = round, dropSeq = dropSeq, dropCol = dropCol, dropRow = dropRow,
        iWantRematch = iWantRematch, theyWantRematch = theyWantRematch,
        myWins = myWins, theirWins = theirWins, draws = draws,
    )

    private fun publish() { _ui.value = snapshot() }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        FourChipsScreen(state = state, onColumn = ::dropDisc, onRematch = ::requestRematch, onLeave = onLeave)
    }

    private companion object {
        const val RESEND_MS = 2500L
    }
}
