package us.crafties.btgames.games.checkers

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

/** Everything the Checkers screen draws. */
data class CheckersUi(
    val board: CheckersBoard,
    /** My pieces this round: FIRST when I move first, SECOND otherwise. Colour is separate. */
    val myMark: CheckerSide,
    /** Selected cell, or -1. Local only, never crosses the wire. */
    val selected: Int,
    /** Legal destinations from [selected]. */
    val targets: List<Int>,
    /** A jump is available: slides are refused, so the screen says so. */
    val mustJump: Boolean,
    val lastStep: Step?,
    /** Counts every applied step (mine and theirs): the screen glides each new one. */
    val stepSeq: Int,
    val myNick: String,
    val theirNick: String,
    /** Which colour I am. Independent of whether I move first. */
    val mySide: Side,
    val round: Int,
    /** I have asked for a rematch and am waiting for the other player. */
    val iWantRematch: Boolean,
    /** The other player has asked for a rematch. */
    val theyWantRematch: Boolean,
    val myWins: Int,
    val theirWins: Int,
) {
    /** SECOND starts at the top of the shared board; turn it so my men are always nearest me. */
    val flipped: Boolean get() = myMark == CheckerSide.SECOND

    val myTurn: Boolean get() = !board.isOver && board.turn == myMark
    val outcomeForMe: CheckersResult? get() = when (board.winner()) {
        null -> null
        myMark -> CheckersResult.WIN
        else -> CheckersResult.LOSS
    }
}

enum class CheckersResult { WIN, LOSS }

/**
 * A Checkers series (Makhos, Thai draughts: 8 men a side on two rows, forward-only men,
 * compulsory jumps, flying kings). Both phones hold the board and validate everything
 * they receive, like XO.
 *
 * Rules of the road:
 *  - Round 0 is started by whoever asked for the match; the starter alternates every rematch.
 *    The starter plays FIRST.
 *  - After every step (each landing of a chained capture included) the stepper sends the
 *    WHOLE packed board ([CheckersCodec.Msg.State]).
 *  - A received board is accepted only if it is one legal step from ours
 *    ([CheckersBoard.nextBoards] membership). Duplicates and stale packets are harmless.
 *  - While a round is unfinished each phone re-sends its board every few seconds, so a lost
 *    packet heals itself without the players noticing.
 *  - A rematch starts only when BOTH players ask for the same round.
 */
class CheckersSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
) : GameSession {

    private var round = 0
    private var board = CheckersBoard(firstMover = firstSide(0))
    private var selected = -1
    private var lastStep: Step? = null
    private var stepSeq = 0
    private var iWantRematch = false
    private var theyWantRematch = false
    private var myWins = 0
    private var theirWins = 0
    private var scored = false // has the finished board of this round been counted yet?

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<CheckersUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var resender: Job? = null

    /** My mark this round: FIRST exactly when I move first. */
    private fun myMark(): CheckerSide {
        val first = firstSide(round)
        return if ((round % 2 == 0) == playsFirst) first else first.other()
    }

    /**
     * Which mark moves first in [r]: FIRST on even rounds, SECOND on odd ones. A property of the
     * shared board, never of [playsFirst] — deriving it per-phone made the two sides disagree
     * (the FourChips lesson). Who moves first still alternates: the requester on even rounds,
     * the other phone on odd ones.
     */
    private fun firstSide(r: Int): CheckerSide = if (r % 2 == 0) CheckerSide.FIRST else CheckerSide.SECOND

    override fun start() {
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) CheckersCodec.decode(msg.payload)?.let(::onMessage)
            }
        }
        // Periodic resend of whatever we believe the state is. Cheap (30 bytes) and self-healing.
        resender = scope.launch {
            while (true) {
                delay(RESEND_MS)
                if (!board.isOver && board.ply > 0) sendState()
                if (iWantRematch && board.isOver) transport.sendControl(Control.Game(CheckersCodec.encodeRematch(round + 1)))
            }
        }
    }

    override fun stop() {
        listeners?.cancel(); resender?.cancel()
    }

    // ---- local input --------------------------------------------------------------------

    fun tapCell(cell: Int) {
        if (cell !in 0..63 || board.isOver || board.turn != myMark()) {
            selected = -1
            publish()
            return
        }
        if (selected >= 0) {
            if (cell == selected) {
                selected = -1
                publish()
                return
            }
            val step = board.applyStep(selected, cell)
            if (step != null) {
                commitStep(selected, cell, step)
                return
            }
        }
        // Not a legal landing: maybe a new piece to select.
        selected = if (selectable(cell)) cell else -1
        publish()
    }

    /**
     * A drag-and-drop: pick the piece on [from] up and release it on [to]. A no-move release
     * behaves like a tap; an illegal landing falls back to selecting [from] so the turn can
     * continue by tapping. Returns true when a step was actually played.
     */
    fun dropPiece(from: Int, to: Int): Boolean {
        if (from == to) {
            tapCell(from)
            return false
        }
        if (board.isOver || board.turn != myMark()) {
            selected = -1
            publish()
            return false
        }
        val step = board.applyStep(from, to)
        if (step == null) {
            selected = if (selectable(from)) from else -1
            publish()
            return false
        }
        commitStep(from, to, step)
        return true
    }

    private fun selectable(cell: Int): Boolean =
        cell in 0..63 && board.cells[cell].side() == myMark() && board.stepsFrom(cell).isNotEmpty()

    private fun commitStep(from: Int, to: Int, step: CheckersBoard) {
        lastStep = Step(from, to)
        selected = -1
        board = step
        stepSeq++
        sendState()
        afterBoardChange()
    }

    fun requestRematch() {
        if (!board.isOver || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(CheckersCodec.encodeRematch(round + 1)))
        maybeStartRematch()
        publish()
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: CheckersCodec.Msg) {
        when (m) {
            is CheckersCodec.Msg.State -> onState(m)
            is CheckersCodec.Msg.Rematch -> {
                if (m.round == round + 1 && board.isOver) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    private fun onState(m: CheckersCodec.Msg.State) {
        when {
            m.round < round -> return // late packet from a round we have already finished
            m.round > round -> {
                // We missed the rematch handshake (e.g. it was lost). Their round is authoritative
                // only if it is the very next one and our round is over, otherwise ignore.
                if (m.round != round + 1 || !board.isOver) return
                startRound(m.round, m.board.firstMover)
            }
        }
        if (m.board.firstMover != board.firstMover) return
        if (m.board !in board.nextBoards()) return // not one legal step from here: stale or hostile
        val step = diffStep(board, m.board)
        board = m.board
        if (step != null) lastStep = step
        stepSeq++
        selected = -1
        afterBoardChange()
    }

    /**
     * The single step between two boards that differ by one legal move: the origin (my piece,
     * now empty) and the landing (empty, now my piece). A capture also empties the victim's
     * square; anything else means the boards do not differ by one step.
     */
    private fun diffStep(before: CheckersBoard, after: CheckersBoard): Step? {
        var from = -1
        var to = -1
        for (i in 0..63) {
            val b = before.cells[i]
            val a = after.cells[i]
            if (b == a) continue
            when {
                b.side() == before.turn && a == Piece.EMPTY -> {
                    if (from != -1) return null
                    from = i
                }
                b == Piece.EMPTY && a.side() == before.turn -> {
                    if (to != -1) return null
                    to = i
                }
                b.side() == before.turn.other() && a == Piece.EMPTY -> Unit // the jumped victim
                else -> return null
            }
        }
        return if (from != -1 && to != -1) Step(from, to) else null
    }

    // ---- rounds and scoring -------------------------------------------------------------

    private fun maybeStartRematch() {
        if (iWantRematch && theyWantRematch) {
            startRound(round + 1, firstSide(round + 1))
        }
    }

    private fun startRound(newRound: Int, firstMover: CheckerSide) {
        round = newRound
        board = CheckersBoard(firstMover = firstMover, turn = firstMover)
        selected = -1
        lastStep = null
        stepSeq = 0
        iWantRematch = false
        theyWantRematch = false
        scored = false
        publish()
    }

    private fun afterBoardChange() {
        if (board.isOver && !scored) {
            scored = true
            if (board.winner() == myMark()) myWins++ else theirWins++
        }
        publish()
    }

    private fun sendState() {
        transport.sendControl(Control.Game(CheckersCodec.encodeState(round, board)))
    }

    private fun mustJump(): Boolean {
        if (!myTurn()) return false
        val mine = if (board.pending >= 0) {
            board.stepsFrom(board.pending)
        } else {
            board.cells.indices.filter { board.cells[it].side() == myMark() }
                .flatMap { board.stepsFrom(it) }
        }
        // Steps are jumps iff any available step spans two ranks.
        return mine.any { kotlin.math.abs(it.to / 8 - it.from / 8) == 2 }
    }

    private fun myTurn(): Boolean = !board.isOver && board.turn == myMark()

    private fun snapshot() = CheckersUi(
        board = board, myMark = myMark(), selected = selected,
        targets = if (selected >= 0) board.stepsFrom(selected).map { it.to } else emptyList(),
        mustJump = mustJump(), lastStep = lastStep, stepSeq = stepSeq,
        myNick = nickMe, theirNick = nickThem, mySide = mySide,
        round = round, iWantRematch = iWantRematch, theyWantRematch = theyWantRematch,
        myWins = myWins, theirWins = theirWins,
    )

    private fun publish() { _ui.value = snapshot() }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        CheckersScreen(state = state, onCell = ::tapCell, onDrop = ::dropPiece, onRematch = ::requestRematch, onLeave = onLeave)
    }

    private companion object {
        const val RESEND_MS = 2500L
    }
}
