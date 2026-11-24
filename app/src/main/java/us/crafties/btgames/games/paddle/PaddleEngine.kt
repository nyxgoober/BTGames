package us.crafties.btgames.games.paddle

import kotlin.math.abs

/**
 * Pure, deterministic Paddle simulation. No Android, no Bluetooth, no clocks: the caller
 * supplies `dt`. Runs only on the host (the device that initiated the connection).
 *
 * Units: field is 1.0 wide by 1.0 tall. Speeds are field-units/second. The real screen
 * is not square, so the UI stretches this to the phone's aspect ratio; that is fine for a
 * shared logical field.
 */
class PaddleEngine(
    private val winScore: Int = 7,
    private val serveDelaySec: Float = 1.2f,
    private val random: () -> Float = { kotlin.random.Random.nextFloat() },
) {
    companion object {
        const val PADDLE_H = 0.20f          // paddle height as fraction of field
        const val PADDLE_INSET = 0.035f     // distance of paddle face from its goal line
        const val BALL_R = 0.018f           // used vertically; horizontally UI compensates
        const val START_SPEED = 0.55f       // field widths / sec
        const val MAX_SPEED = 1.6f
        const val SPEEDUP = 1.06f
        const val MAX_BOUNCE_ANGLE = 1.05f  // radians-ish; controls how steep edge hits are
    }

    var state = PaddleState()
        private set

    private var serveTimer = 0f
    private var serveToward = -1 // -1 = toward host (x=0), +1 = toward guest (x=1)

    /** Start a fresh match. The initiator is the host, so the first serve heads to x = 0. */
    fun newMatch() {
        state = PaddleState(phase = Phase.SERVING)
        serveToward = -1
        serveTimer = serveDelaySec
    }

    fun setHostPaddle(y: Float) {
        state = state.copy(hostPaddleY = clampPaddle(y))
    }

    fun setGuestPaddle(y: Float) {
        state = state.copy(guestPaddleY = clampPaddle(y))
    }

    private fun clampPaddle(y: Float) = y.coerceIn(PADDLE_H / 2, 1f - PADDLE_H / 2)

    fun step(dt: Float) {
        when (state.phase) {
            Phase.GAME_OVER -> return
            Phase.SERVING -> {
                serveTimer -= dt
                if (serveTimer <= 0f) launch()
            }
            Phase.PLAYING -> advance(dt)
        }
    }

    private fun launch() {
        // Random shallow angle, direction fixed by whoever must receive the serve.
        val angle = (random() - 0.5f) * 0.9f // +-0.45 rad
        val vx = serveToward * START_SPEED * kotlin.math.cos(angle)
        val vy = START_SPEED * kotlin.math.sin(angle)
        state = state.copy(ballX = 0.5f, ballY = 0.5f, ballVx = vx, ballVy = vy, phase = Phase.PLAYING)
    }

    private fun advance(dt: Float) {
        var x = state.ballX + state.ballVx * dt
        var y = state.ballY + state.ballVy * dt
        var vx = state.ballVx
        var vy = state.ballVy

        // Wall bounces (top/bottom)
        if (y < BALL_R) { y = BALL_R; vy = abs(vy) }
        if (y > 1f - BALL_R) { y = 1f - BALL_R; vy = -abs(vy) }

        // Paddle collision. Detect crossing the paddle face this step so fast balls can't tunnel.
        val hostFace = PADDLE_INSET
        val guestFace = 1f - PADDLE_INSET

        if (vx < 0f && state.ballX >= hostFace && x < hostFace) {
            if (hits(state.hostPaddleY, y)) {
                x = hostFace
                val r = bounce(state.hostPaddleY, y, vx, vy, +1)
                vx = r.first; vy = r.second
            }
        } else if (vx > 0f && state.ballX <= guestFace && x > guestFace) {
            if (hits(state.guestPaddleY, y)) {
                x = guestFace
                val r = bounce(state.guestPaddleY, y, vx, vy, -1)
                vx = r.first; vy = r.second
            }
        }

        // Goals
        if (x < 0f) return score(guestScored = true)
        if (x > 1f) return score(guestScored = false)

        state = state.copy(ballX = x, ballY = y, ballVx = vx, ballVy = vy)
    }

    private fun hits(paddleY: Float, ballY: Float) =
        abs(ballY - paddleY) <= PADDLE_H / 2 + BALL_R

    /** Outgoing direction sign is `dir` (+1 = away from host). Angle depends on where it hit. */
    private fun bounce(paddleY: Float, ballY: Float, vx: Float, vy: Float, dir: Int): Pair<Float, Float> {
        val speed = (kotlin.math.hypot(vx, vy) * SPEEDUP).coerceAtMost(MAX_SPEED)
        val rel = ((ballY - paddleY) / (PADDLE_H / 2)).coerceIn(-1f, 1f) // -1 top edge .. +1 bottom edge
        val angle = rel * MAX_BOUNCE_ANGLE * 0.5f
        val nvx = dir * speed * kotlin.math.cos(angle)
        val nvy = speed * kotlin.math.sin(angle)
        return nvx to nvy
    }

    /**
     * A goal on the GUEST's side (x > 1) means the host scored, and the ball is then served
     * toward the player who just conceded, i.e. the guest. And vice versa.
     */
    private fun score(guestScored: Boolean) {
        val hs = state.hostScore + if (guestScored) 0 else 1
        val gs = state.guestScore + if (guestScored) 1 else 0
        val over = hs >= winScore || gs >= winScore
        serveToward = if (guestScored) -1 else +1 // toward the conceder
        serveTimer = serveDelaySec
        state = state.copy(
            ballX = 0.5f, ballY = 0.5f, ballVx = 0f, ballVy = 0f,
            hostScore = hs, guestScore = gs,
            phase = if (over) Phase.GAME_OVER else Phase.SERVING,
        )
    }
}
