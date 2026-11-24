package us.crafties.btgames.games.paddle

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Everything about how Paddle talks on the wire lives here, so the shared protocol stays
 * game-agnostic.
 *
 * Stream (lossy, latest-wins):
 *   INPUT  guest -> host   [T_INPUT][paddleY:u16]                                   3 bytes
 *   STATE  host -> guest   [T_STATE][seq:u16][ball xy][ball vel][paddles][scores][phase]  20 bytes
 *
 * Reliable (rides inside Control.Game):
 *   SCORE  host -> guest   [E_SCORE][hostScore:i16][guestScore:i16]
 *   OVER   host -> guest   [E_OVER][hostScore:i16][guestScore:i16]
 */
object PaddleCodec {
    const val T_INPUT: Byte = 16
    const val T_STATE: Byte = 17

    const val E_SCORE: Byte = 1
    const val E_OVER: Byte = 2

    /** type(1) + seq(2) + ball xy(4) + ball vel(4) + paddles(4) + scores(4) + phase(1) */
    const val STATE_SIZE = 1 + 2 + 4 + 4 + 4 + 4 + 1

    private fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    // ---- stream -----------------------------------------------------------------------

    fun encodeInput(paddleY: Float): ByteArray =
        buf(3).put(T_INPUT).putShort(q(paddleY)).array()

    fun decodeInput(data: ByteArray): Float? {
        if (data.size < 3 || data[0] != T_INPUT) return null
        return uq(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(1))
    }

    /** seq lets the receiver drop stale/out-of-order state packets. */
    fun encodeState(seq: Int, s: PaddleState): ByteArray = buf(STATE_SIZE).apply {
        put(T_STATE)
        putShort(seq.toShort())
        putShort(q(s.ballX)); putShort(q(s.ballY))
        putShort(qv(s.ballVx)); putShort(qv(s.ballVy))
        putShort(q(s.hostPaddleY)); putShort(q(s.guestPaddleY))
        putShort(s.hostScore.toShort()); putShort(s.guestScore.toShort())
        put(s.phase.ordinal.toByte())
    }.array()

    data class DecodedState(val seq: Int, val state: PaddleState)

    fun decodeState(data: ByteArray): DecodedState? {
        if (data.size < STATE_SIZE || data[0] != T_STATE) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.get()
        val seq = b.short.toInt() and 0xFFFF
        val bx = uq(b.short); val by = uq(b.short)
        val vx = uqv(b.short); val vy = uqv(b.short)
        val hp = uq(b.short); val gp = uq(b.short)
        val hs = b.short.toInt(); val gs = b.short.toInt()
        val phase = Phase.entries.getOrElse(b.get().toInt()) { Phase.PLAYING }
        return DecodedState(seq, PaddleState(bx, by, vx, vy, hp, gp, hs, gs, phase))
    }

    // ---- reliable events --------------------------------------------------------------

    sealed interface Event {
        data class Score(val hostScore: Int, val guestScore: Int) : Event
        data class Over(val hostScore: Int, val guestScore: Int) : Event
    }

    fun encodeScore(hostScore: Int, guestScore: Int): ByteArray =
        buf(5).put(E_SCORE).putShort(hostScore.toShort()).putShort(guestScore.toShort()).array()

    fun encodeOver(hostScore: Int, guestScore: Int): ByteArray =
        buf(5).put(E_OVER).putShort(hostScore.toShort()).putShort(guestScore.toShort()).array()

    fun decodeEvent(data: ByteArray): Event? {
        if (data.size < 5) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return when (b.get()) {
            E_SCORE -> Event.Score(b.short.toInt(), b.short.toInt())
            E_OVER -> Event.Over(b.short.toInt(), b.short.toInt())
            else -> null
        }
    }

    // ---- quantisation -----------------------------------------------------------------

    private fun q(v: Float): Short = (v.coerceIn(0f, 1f) * 65535f).toInt().toShort()
    private fun uq(s: Short): Float = (s.toInt() and 0xFFFF) / 65535f
    // Velocity range is +-4 field-widths/sec mapped to signed 16 bit.
    private const val V_RANGE = 4f
    private fun qv(v: Float): Short = (v.coerceIn(-V_RANGE, V_RANGE) / V_RANGE * 32767f).toInt().toShort()
    private fun uqv(s: Short): Float = s / 32767f * V_RANGE
}
