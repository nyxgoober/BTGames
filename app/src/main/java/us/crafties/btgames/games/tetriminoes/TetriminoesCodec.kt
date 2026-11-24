package us.crafties.btgames.games.tetriminoes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tetriminoes messages ride inside Control.Game (reliable). Three kinds:
 *
 *   CHOICE  [1][round:u8][durationSec:u16 LE]                                   4 bytes
 *           "We play this long." Sent by the chooser (re-sent until the match starts).
 *   UPDATE  [2][round:u8][score:u32 LE][lines:u16 LE][level:u8][flags:u8]       10 bytes
 *           "Here is my score." Sent when it changes plus a 1 Hz heartbeat; flags bit 0 = done.
 *   REMATCH [3][round:u8]                                                        2 bytes
 *           "I want round N." When both players ask for the same round, it starts.
 *
 * Scores only climb, so a stale UPDATE is harmless: the screen keeps the max per round.
 * [round] rises with every rematch so a late packet from the last match is ignored.
 */
object TetriminoesCodec {
    private const val T_CHOICE: Byte = 1
    private const val T_UPDATE: Byte = 2
    private const val T_REMATCH: Byte = 3

    const val FLAG_DONE: Int = 1

    /** Offered time limits (seconds). The chooser picks one of these. */
    val DURATIONS = listOf(60, 120, 180, 300)

    sealed interface Msg {
        data class Choice(val round: Int, val durationSec: Int) : Msg
        data class Update(
            val round: Int,
            val score: Int,
            val lines: Int,
            val level: Int,
            val done: Boolean,
        ) : Msg
        data class Rematch(val round: Int) : Msg
    }

    fun encodeChoice(round: Int, durationSec: Int): ByteArray =
        buf(4).put(T_CHOICE).put(round.toByte()).putShort(durationSec.toShort()).array()

    fun encodeUpdate(round: Int, score: Int, lines: Int, level: Int, done: Boolean): ByteArray =
        buf(10).put(T_UPDATE).put(round.toByte()).putInt(score).putShort(lines.toShort())
            .put(level.toByte()).put(if (done) FLAG_DONE.toByte() else 0).array()

    fun encodeRematch(round: Int): ByteArray = byteArrayOf(T_REMATCH, round.toByte())

    fun decode(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return try {
            when (data[0]) {
                T_CHOICE -> {
                    if (data.size < 4) return null
                    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    b.get()
                    val round = b.get().toInt() and 0xFF
                    val dur = b.short.toInt() and 0xFFFF
                    if (dur !in DURATIONS) return null
                    Msg.Choice(round, dur)
                }
                T_UPDATE -> {
                    if (data.size < 10) return null
                    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    b.get()
                    val round = b.get().toInt() and 0xFF
                    val score = b.int
                    val lines = b.short.toInt() and 0xFFFF
                    val level = b.get().toInt() and 0xFF
                    val flags = b.get().toInt() and 0xFF
                    if (score < 0 || level !in 1..10) return null
                    Msg.Update(round, score, lines, level, (flags and FLAG_DONE) != 0)
                }
                T_REMATCH -> if (data.size >= 2) Msg.Rematch(data[1].toInt() and 0xFF) else null
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
