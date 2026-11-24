package us.crafties.btgames.games.paddle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the court needs to draw one frame, always from THIS player's point of view. */
data class Frame(
    val ballX: Float,          // 0 = my goal line (left), 1 = their goal line (right)
    val ballY: Float,
    val myPaddleY: Float,
    val theirPaddleY: Float,
    val myScore: Int,
    val theirScore: Int,
    val phase: Phase,
)

/**
 * Runs one match.
 *
 *  - The phone that plays FIRST is the host: it runs [PaddleEngine] at a fixed tick, streams state
 *    to the guest, and the first serve is aimed at it (x = 0 in engine space). Who plays first
 *    comes from the handshake: the requester in an ordinary request, or whoever was chosen when
 *    both phones asked at once.
 *  - The other device is the guest: it sends its paddle position and renders host state.
 *
 * Both sides render "me on the left". The guest mirrors x on the way in so nothing else needs
 * to know about the flip.
 */
class PaddleSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    val isHost: Boolean,
    private val mySide: Side,
    private val myNick: String,
    private val theirNick: String,
    private val winScore: Int = 7,
) : GameSession {
    private val engine = PaddleEngine(winScore = winScore)

    private val _frame = MutableStateFlow(
        Frame(0.5f, 0.5f, 0.5f, 0.5f, 0, 0, Phase.SERVING)
    )
    val frame: StateFlow<Frame> = _frame.asStateFlow()

    /** Latest local touch position (0..1 of court height). */
    @Volatile private var myPaddle = 0.5f

    // ---- guest-side smoothing ----------------------------------------------------------
    private var lastSeq = -1
    private var latest: PaddleState? = null
    private var latestAtNanos = 0L
    private var displayBallX = 0.5f
    private var displayBallY = 0.5f

    private var loop: Job? = null
    private var listeners: Job? = null

    override fun start() {
        listeners = scope.launch {
            launch { transport.stream.collect(::onStream) }
            launch { transport.control.collect(::onControl) }
        }
        loop = scope.launch { if (isHost) hostLoop() else guestLoop() }
    }

    override fun stop() {
        loop?.cancel(); listeners?.cancel()
    }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val f by frame.collectAsStateWithLifecycle()
        PaddleCourtScreen(
            frame = f,
            myNick = myNick,
            theirNick = theirNick,
            isHost = isHost,
            mySide = mySide,
            onPaddle = ::setMyPaddle,
            onRematch = ::rematch,
            onLeave = onLeave,
        )
    }

    fun setMyPaddle(y: Float) {
        myPaddle = y.coerceIn(0f, 1f)
    }

    // =====================================================================================
    // Host
    // =====================================================================================

    private suspend fun hostLoop() {
        engine.newMatch()
        var seq = 0
        var last = System.nanoTime()
        var sendAccumulator = 0f
        var lastHostScore = 0
        var lastGuestScore = 0

        while (true) {
            val now = System.nanoTime()
            // Clamp dt so a stalled coroutine can't teleport the ball through a paddle.
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now

            engine.setHostPaddle(myPaddle)
            engine.step(dt)
            val st = engine.state

            // Reliable goal / game-over events (the stream is lossy, scores must not be).
            if (st.hostScore != lastHostScore || st.guestScore != lastGuestScore) {
                lastHostScore = st.hostScore; lastGuestScore = st.guestScore
                val payload = if (st.phase == Phase.GAME_OVER) {
                    PaddleCodec.encodeOver(st.hostScore, st.guestScore)
                } else {
                    PaddleCodec.encodeScore(st.hostScore, st.guestScore)
                }
                transport.sendControl(Control.Game(payload))
            }
            // Stream state at ~30 Hz; the simulation itself runs at ~60 Hz.
            sendAccumulator += dt
            if (sendAccumulator >= 1f / STATE_HZ) {
                sendAccumulator = 0f
                transport.sendStream(PaddleCodec.encodeState(seq++ and 0xFFFF, st))
            }

            _frame.value = Frame(
                ballX = st.ballX, ballY = st.ballY,
                myPaddleY = st.hostPaddleY, theirPaddleY = st.guestPaddleY,
                myScore = st.hostScore, theirScore = st.guestScore,
                phase = st.phase,
            )
            delay(TICK_MS)
        }
    }

    // =====================================================================================
    // Guest
    // =====================================================================================

    private suspend fun guestLoop() {
        var sendAt = 0L
        while (true) {
            val nowNanos = System.nanoTime()

            // Send my paddle ~30 Hz. It is tiny (3 bytes) and latest-wins.
            if (nowNanos - sendAt > 1_000_000_000L / INPUT_HZ) {
                sendAt = nowNanos
                transport.sendStream(PaddleCodec.encodeInput(myPaddle))
            }

            val s = latest
            if (s != null) {
                // Dead-reckon the ball from the last packet using its velocity, then ease the
                // drawn ball toward that estimate. This hides the ~33 ms gaps between packets.
                val age = ((nowNanos - latestAtNanos) / 1e9f).coerceIn(0f, 0.12f)
                val moving = s.phase == Phase.PLAYING
                val predX = if (moving) s.ballX + s.ballVx * age else s.ballX
                val predY = if (moving) reflect(s.ballY + s.ballVy * age) else s.ballY

                val snap = !moving || kotlin.math.abs(predX - displayBallX) > 0.25f
                if (snap) { displayBallX = predX; displayBallY = predY }
                else {
                    displayBallX += (predX - displayBallX) * 0.45f
                    displayBallY += (predY - displayBallY) * 0.45f
                }

                // Engine space has the host on the left; the guest mirrors so "me" is on the left.
                _frame.value = Frame(
                    ballX = 1f - displayBallX,
                    ballY = displayBallY,
                    myPaddleY = myPaddle,                 // draw my own paddle instantly from touch
                    theirPaddleY = s.hostPaddleY,
                    myScore = s.guestScore,
                    theirScore = s.hostScore,
                    phase = s.phase,
                )
            }
            delay(TICK_MS)
        }
    }

    /** Fold a predicted y back into 0..1 as if it bounced off the top/bottom walls. */
    private fun reflect(y: Float): Float {
        var v = y
        var guard = 0
        while ((v < 0f || v > 1f) && guard++ < 4) v = if (v < 0f) -v else 2f - v
        return v.coerceIn(0f, 1f)
    }

    // =====================================================================================
    // Incoming
    // =====================================================================================

    private fun onStream(bytes: ByteArray) {
        if (isHost) {
            PaddleCodec.decodeInput(bytes)?.let { engine.setGuestPaddle(it) }
        } else {
            val d = PaddleCodec.decodeState(bytes) ?: return
            // Drop stale/out-of-order frames (16-bit wraparound aware).
            if (lastSeq >= 0) {
                val diff = (d.seq - lastSeq) and 0xFFFF
                if (diff == 0 || diff > 0x8000) return
            }
            lastSeq = d.seq
            latest = d.state
            latestAtNanos = System.nanoTime()
        }
    }

    private fun onControl(msg: Control) {
        if (isHost || msg !is Control.Game) return
        // The guest trusts the reliable event for the final score even if the last state frame was lost.
        when (val e = PaddleCodec.decodeEvent(msg.payload)) {
            is PaddleCodec.Event.Score ->
                latest = latest?.copy(hostScore = e.hostScore, guestScore = e.guestScore)
            is PaddleCodec.Event.Over ->
                latest = (latest ?: PaddleState()).copy(
                    hostScore = e.hostScore, guestScore = e.guestScore, phase = Phase.GAME_OVER,
                )
            null -> Unit
        }
    }

    /** Ask for a rematch: only the host owns the simulation, so only it can restart. */
    fun rematch() {
        if (!isHost) return
        engine.newMatch()
    }

    val isFinished: Boolean get() = _frame.value.phase == Phase.GAME_OVER

    companion object {
        private const val TICK_MS = 16L
        private const val STATE_HZ = 30f
        private const val INPUT_HZ = 30
    }
}
