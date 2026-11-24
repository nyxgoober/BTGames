package us.crafties.btgames.games.keymash

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Keymash messages ride inside Control.Game (reliable). Three kinds:
 *
 *   CHOICE  [1][round:u8][mode:u8][seed:u32 LE]                                     7 bytes
 *           "We play this mode from this seed." Sent by the chooser (re-sent until the race
 *           starts). Both phones generate identical prompts from the seed, so the text itself
 *           never crosses the wire.
 *   UPDATE  [2][round:u8][done:u8][completed:u8][total:u8][wpm:u16 LE][score:u32 LE]  11 bytes
 *           "I am this far along." Heartbeat plus on every submit; [score] is live until done.
 *   REMATCH [3][round:u8]                                                            2 bytes
 *           "I want round N." When both players ask for the same round, it starts.
 *
 * Progress only climbs, so a stale UPDATE is harmless: the screen keeps the max per round.
 * [round] rises with every rematch so a late packet from the last race is ignored.
 */
object KeymashCodec {
    private const val T_CHOICE: Byte = 1
    private const val T_UPDATE: Byte = 2
    private const val T_REMATCH: Byte = 3

    sealed interface Msg {
        data class Choice(val round: Int, val mode: KeymashMode, val seed: Long) : Msg
        data class Update(
            val round: Int,
            val done: Boolean,
            val completed: Int,
            val total: Int,
            val wpm: Int,
            val score: Int,
        ) : Msg
        data class Rematch(val round: Int) : Msg
    }

    fun encodeChoice(round: Int, mode: KeymashMode, seed: Long): ByteArray =
        buf(7).put(T_CHOICE).put(round.toByte()).put(mode.code.toByte()).putInt(seed.toInt()).array()

    fun encodeUpdate(
        round: Int,
        done: Boolean,
        completed: Int,
        total: Int,
        wpm: Int,
        score: Int,
    ): ByteArray = buf(11).put(T_UPDATE).put(round.toByte())
        .put(if (done) 1 else 0).put(completed.toByte()).put(total.toByte())
        .putShort(wpm.toShort()).putInt(score).array()

    fun encodeRematch(round: Int): ByteArray = byteArrayOf(T_REMATCH, round.toByte())

    fun decode(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return try {
            when (data[0]) {
                T_CHOICE -> {
                    if (data.size < 7) return null
                    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    b.get()
                    val round = b.get().toInt() and 0xFF
                    val mode = KeymashMode.fromCode(b.get().toInt()) ?: return null
                    val seed = b.int.toLong() and 0xFFFFFFFFL
                    Msg.Choice(round, mode, seed)
                }
                T_UPDATE -> {
                    if (data.size < 11) return null
                    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    b.get()
                    val round = b.get().toInt() and 0xFF
                    val done = b.get().toInt() != 0
                    val completed = b.get().toInt() and 0xFF
                    val total = b.get().toInt() and 0xFF
                    val wpm = b.short.toInt() and 0xFFFF
                    val score = b.int
                    if (completed > total || total <= 0 || score < 0) return null
                    Msg.Update(round, done, completed, total, wpm, score)
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
