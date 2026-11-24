package us.crafties.btgames.games.blocktower

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Block Tower messages ride inside Control.Game (reliable). Four kinds:
 *
 *   SEED    [1][round:u8][seed:u32 LE]                  6 bytes
 *           "Our tower grows from this seed." Sent by the seeder (re-sent until ACKed).
 *   ACK     [2][round:u8]                                2 bytes
 *           "Seed received, tower built." Answers every SEED (idempotent).
 *   MOVE    [3][round:u8][n:u8][layer:u8][slot:u8]       5 bytes
 *           "I pulled layer/slot as move n." Both phones apply it to the same state.
 *   REMATCH [4][round:u8]                                2 bytes
 *           "I want round N." When both players ask for the same round, it starts.
 *
 * Moves apply in order: a MOVE whose number is not the next one is a duplicate (or from the
 * future, which the reliable channel rules out) and is ignored. [round] rises with every
 * rematch so a late packet from the last tower is ignored.
 */
object BlockTowerCodec {
    private const val T_SEED: Byte = 1
    private const val T_ACK: Byte = 2
    private const val T_MOVE: Byte = 3
    private const val T_REMATCH: Byte = 4

    sealed interface Msg {
        data class Seed(val round: Int, val seed: Long) : Msg
        data class Ack(val round: Int) : Msg
        data class Move(val round: Int, val n: Int, val layer: Int, val slot: Int) : Msg
        data class Rematch(val round: Int) : Msg
    }

    fun encodeSeed(round: Int, seed: Long): ByteArray =
        buf(6).put(T_SEED).put(round.toByte()).putInt(seed.toInt()).array()

    fun encodeAck(round: Int): ByteArray = byteArrayOf(T_ACK, round.toByte())

    fun encodeMove(round: Int, n: Int, layer: Int, slot: Int): ByteArray =
        byteArrayOf(T_MOVE, round.toByte(), n.toByte(), layer.toByte(), slot.toByte())

    fun encodeRematch(round: Int): ByteArray = byteArrayOf(T_REMATCH, round.toByte())

    fun decode(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return try {
            when (data[0]) {
                T_SEED -> {
                    if (data.size < 6) return null
                    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    b.get()
                    Msg.Seed(b.get().toInt() and 0xFF, b.int.toLong() and 0xFFFFFFFFL)
                }
                T_ACK -> if (data.size >= 2) Msg.Ack(data[1].toInt() and 0xFF) else null
                T_MOVE -> {
                    if (data.size < 5) return null
                    Msg.Move(
                        data[1].toInt() and 0xFF, data[2].toInt() and 0xFF,
                        data[3].toInt() and 0xFF, data[4].toInt() and 0xFF,
                    )
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
