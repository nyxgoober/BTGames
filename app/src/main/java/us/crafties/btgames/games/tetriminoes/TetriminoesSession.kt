package us.crafties.btgames.games.tetriminoes

import android.os.SystemClock
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

enum class TetriminoesPhase { SETUP, COUNTDOWN, PLAYING, OVER }

enum class TetriminoesResult { WIN, LOSS, DRAW }

/** Everything the Tetriminoes screen draws. A fresh copy is published on every tick. */
data class TetriminoesUi(
    val phase: TetriminoesPhase,
    val round: Int,
    /** This phone picks the time limit for [round]. RED picks even rounds, BLUE odd ones. */
    val iChoose: Boolean,
    /** Seconds, 0 until the chooser has picked. */
    val durationSec: Int,
    val countdownLeft: Int,
    val remainingSec: Int,
    val level: Int,
    val grid: List<IntArray>,
    val falling: List<Pair<Int, Int>>,
    val ghost: List<Pair<Int, Int>>,
    val fallingKind: Kind,
    val next: Kind,
    val myScore: Int,
    val myLines: Int,
    /** How many pieces have locked so far. The screen binds each swipe to one piece with this. */
    val pieces: Int,
    val theirScore: Int,
    val theirLines: Int,
    val theirLevel: Int,
    val myDone: Boolean,
    val theyDone: Boolean,
    val outcome: TetriminoesResult?,
    val myWins: Int,
    val theirWins: Int,
    val draws: Int,
    val iWantRematch: Boolean,
    val theyWantRematch: Boolean,
    val myNick: String,
    val theirNick: String,
    val mySide: Side,
)

/**
 * Score-attack Tetriminoes: both phones play their own board at the same time, scores are shared
 * live, and the higher score when the clock runs out wins.
 *
 * Who picks the time is random per match: RED chooses on even rounds, BLUE on odd ones.
 * Colours are a fair coin flip per match (see Sides), so round 0's chooser is random, and
 * rematches alternate so the same player never keeps the pick.
 *
 * Timing: the chooser's pick starts a 3 s countdown on both phones (the peer starts it when
 * the CHOICE arrives; resends cover a lost packet). Each phone then runs its own clock, so
 * the match ends for both within a packet's latency of each other. Final scores ride on
 * UPDATE(done=true), re-sent until the other side's is seen.
 */
class TetriminoesSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
) : GameSession {

    private var round = 0
    private var phase = TetriminoesPhase.SETUP
    private var durationSec = 0
    private var engine = TetriminoesEngine()

    private var countdownEnds = 0L
    private var playStart = 0L
    private var lastTick = 0L
    private var gravityAcc = 0L

    private var myDone = false
    private var theyDone = false
    private var theirScore = 0
    private var theirLines = 0
    private var theirLevel = 1

    private var outcome: TetriminoesResult? = null
    private var myWins = 0
    private var theirWins = 0
    private var draws = 0

    private var iWantRematch = false
    private var theyWantRematch = false

    private var lastSentScore = -1
    private var lastSentLines = -1
    private var lastSentAt = 0L
    private var seenPeerUpdate = false

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<TetriminoesUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var loop: Job? = null

    private fun iChoose(): Boolean = (round % 2 == 0) == (mySide == Side.RED)

    override fun start() {
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) TetriminoesCodec.decode(msg.payload)?.let(::onMessage)
            }
        }
        loop = scope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    override fun stop() {
        listeners?.cancel(); loop?.cancel()
    }

    // ---- local input --------------------------------------------------------------------

    fun chooseDuration(sec: Int) {
        if (phase != TetriminoesPhase.SETUP || !iChoose() || sec !in TetriminoesCodec.DURATIONS) return
        durationSec = sec
        transport.sendControl(Control.Game(TetriminoesCodec.encodeChoice(round, sec)))
        lastSentAt = SystemClock.uptimeMillis()
        enterCountdown()
    }

    /** Returns true if the piece actually moved (the screen ticks only then). */
    fun moveLeft(): Boolean {
        if (phase != TetriminoesPhase.PLAYING || myDone) return false
        if (!engine.moveLeft()) return false
        publish()
        return true
    }

    /** Returns true if the piece actually moved (the screen ticks only then). */
    fun moveRight(): Boolean {
        if (phase != TetriminoesPhase.PLAYING || myDone) return false
        if (!engine.moveRight()) return false
        publish()
        return true
    }

    fun rotate() {
        if (phase != TetriminoesPhase.PLAYING || myDone) return
        if (engine.rotateCW()) publish()
    }

    /** One soft-drop step. Returns true if it locked the piece. */
    fun softDropStep(): Boolean {
        if (phase != TetriminoesPhase.PLAYING || myDone) return false
        val before = engine.pieces
        engine.softDrop()
        afterEngineChange()
        return engine.pieces != before
    }

    /** Slam to the ghost position. Returns true if it locked the piece. */
    fun hardDrop(): Boolean {
        if (phase != TetriminoesPhase.PLAYING || myDone) return false
        val before = engine.pieces
        engine.hardDrop()
        afterEngineChange()
        return engine.pieces != before
    }

    fun requestRematch() {
        if (phase != TetriminoesPhase.OVER || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(TetriminoesCodec.encodeRematch(round + 1)))
        maybeStartRematch()
        publish()
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: TetriminoesCodec.Msg) {
        when (m) {
            is TetriminoesCodec.Msg.Choice -> {
                if (m.round != (round and 0xFF) || phase != TetriminoesPhase.SETUP || iChoose()) return
                durationSec = m.durationSec
                enterCountdown()
            }
            is TetriminoesCodec.Msg.Update -> {
                // Never rewrite the numbers under a locked-in verdict.
                if (m.round != (round and 0xFF) || phase == TetriminoesPhase.OVER) return
                seenPeerUpdate = true
                if (m.score >= theirScore) {
                    theirScore = m.score
                    theirLines = m.lines
                    theirLevel = m.level
                }
                if (m.done) theyDone = true
                publish()
            }
            is TetriminoesCodec.Msg.Rematch -> {
                if (m.round == ((round + 1) and 0xFF) && phase == TetriminoesPhase.OVER) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    // ---- loop ---------------------------------------------------------------------------

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        when (phase) {
            TetriminoesPhase.SETUP -> {
                // Nothing to do until the chooser picks. The chooser is us only when iChoose().
            }
            TetriminoesPhase.COUNTDOWN -> {
                // Re-send the pick until the peer proves they are playing.
                if (iChoose() && !seenPeerUpdate && now - lastSentAt > RESEND_MS) {
                    lastSentAt = now
                    transport.sendControl(Control.Game(TetriminoesCodec.encodeChoice(round, durationSec)))
                }
                if (now >= countdownEnds) {
                    phase = TetriminoesPhase.PLAYING
                    playStart = now
                    lastTick = now
                    gravityAcc = 0
                    sendUpdate(force = true)
                }
                publish()
            }
            TetriminoesPhase.PLAYING -> {
                val elapsedSec = ((now - playStart) / 1000).coerceAtLeast(0)
                val level = TetriminoesEngine.levelFor(elapsedSec, durationSec.toLong())
                engine.level = level
                if (!myDone) {
                    val interval = TetriminoesEngine.intervalMs(elapsedSec, durationSec.toLong())
                    // Forgive stalled frames: without a cap, one long pause leaves a debt that
                    // dumps several rows on every following tick and top-outs the player.
                    val cap = interval * 2L
                    gravityAcc = (gravityAcc + (now - lastTick).coerceIn(0L, cap)).coerceIn(0L, cap)
                    var guard = 0
                    while (gravityAcc >= interval && !engine.gameOver && guard++ < 8) {
                        engine.gravityTick()
                        gravityAcc -= interval
                    }
                    if (engine.gameOver) {
                        myDone = true
                    } else if (elapsedSec >= durationSec) {
                        myDone = true
                    }
                    sendUpdate(force = myDone)
                } else {
                    // Done (topped out or timed out): keep re-sending the final score in case
                    // the peer missed it, plus answer their heartbeat. Decide only when both
                    // runs are final, so both phones compare the same two scores.
                    if (now - lastSentAt > RESEND_MS) sendUpdate(force = true)
                    if (theyDone) finishMatch()
                }
                lastTick = now
                publish()
            }
            TetriminoesPhase.OVER -> {
                if (iWantRematch && now - lastSentAt > RESEND_MS) {
                    lastSentAt = now
                    transport.sendControl(Control.Game(TetriminoesCodec.encodeRematch(round + 1)))
                }
            }
        }
    }

    private fun sendUpdate(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && engine.score == lastSentScore && engine.lines == lastSentLines &&
            now - lastSentAt < HEARTBEAT_MS
        ) return
        lastSentScore = engine.score
        lastSentLines = engine.lines
        lastSentAt = now
        transport.sendControl(
            Control.Game(
                TetriminoesCodec.encodeUpdate(
                    round, engine.score, engine.lines, engine.level.coerceIn(1, 10), myDone,
                ),
            ),
        )
    }

    private fun afterEngineChange() {
        if (engine.gameOver && !myDone && phase == TetriminoesPhase.PLAYING) {
            myDone = true
            sendUpdate(force = true)
        } else {
            sendUpdate(force = false)
        }
        publish()
    }

    private fun enterCountdown() {
        engine = TetriminoesEngine()
        phase = TetriminoesPhase.COUNTDOWN
        countdownEnds = SystemClock.uptimeMillis() + COUNTDOWN_MS
        publish()
    }

    private fun finishMatch() {
        if (phase == TetriminoesPhase.OVER) return
        phase = TetriminoesPhase.OVER
        sendUpdate(force = true)
        outcome = when {
            engine.score > theirScore -> TetriminoesResult.WIN
            engine.score < theirScore -> TetriminoesResult.LOSS
            else -> TetriminoesResult.DRAW
        }
        when (outcome) {
            TetriminoesResult.WIN -> myWins++
            TetriminoesResult.LOSS -> theirWins++
            TetriminoesResult.DRAW -> draws++
            null -> Unit
        }
        publish()
    }

    private fun maybeStartRematch() {
        if (!iWantRematch || !theyWantRematch) return
        round++
        phase = TetriminoesPhase.SETUP
        durationSec = 0
        engine = TetriminoesEngine()
        myDone = false
        theyDone = false
        theirScore = 0
        theirLines = 0
        theirLevel = 1
        outcome = null
        iWantRematch = false
        theyWantRematch = false
        lastSentScore = -1
        lastSentLines = -1
        seenPeerUpdate = false
        lastSentAt = 0L
        gravityAcc = 0
        publish()
    }

    // ---- snapshot -----------------------------------------------------------------------

    private fun snapshot(): TetriminoesUi {
        val now = SystemClock.uptimeMillis()
        val countdownLeft = if (phase == TetriminoesPhase.COUNTDOWN) {
            ((countdownEnds - now) / 1000).toInt().coerceIn(0, 3)
        } else 0
        val remaining = if (phase == TetriminoesPhase.PLAYING && durationSec > 0) {
            (durationSec - (now - playStart) / 1000).toInt().coerceAtLeast(0)
        } else if (phase == TetriminoesPhase.OVER) 0 else durationSec
        return TetriminoesUi(
            phase = phase,
            round = round,
            iChoose = iChoose(),
            durationSec = durationSec,
            countdownLeft = countdownLeft,
            remainingSec = remaining,
            level = engine.level.coerceIn(1, 10),
            grid = engine.grid.map { it.clone() },
            falling = if (phase == TetriminoesPhase.PLAYING && !myDone) engine.currentCells() else emptyList(),
            ghost = if (phase == TetriminoesPhase.PLAYING && !myDone) engine.ghostCells() else emptyList(),
            fallingKind = engine.current,
            next = engine.next,
            myScore = engine.score,
            myLines = engine.lines,
            pieces = engine.pieces,
            theirScore = theirScore,
            theirLines = theirLines,
            theirLevel = theirLevel,
            myDone = myDone,
            theyDone = theyDone,
            outcome = outcome,
            myWins = myWins,
            theirWins = theirWins,
            draws = draws,
            iWantRematch = iWantRematch,
            theyWantRematch = theyWantRematch,
            myNick = nickMe,
            theirNick = nickThem,
            mySide = mySide,
        )
    }

    private fun publish() { _ui.value = snapshot() }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        TetriminoesScreen(
            state = state,
            onChoose = ::chooseDuration,
            onLeft = ::moveLeft,
            onRight = ::moveRight,
            onRotate = ::rotate,
            onSoftDrop = ::softDropStep,
            onHardDrop = ::hardDrop,
            onRematch = ::requestRematch,
            onLeave = onLeave,
        )
    }

    companion object {
        const val TICK_MS = 50L
        const val COUNTDOWN_MS = 3000L
        const val HEARTBEAT_MS = 1000L
        const val RESEND_MS = 1500L
    }
}
