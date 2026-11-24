package us.crafties.btgames.games.fourchips

import us.crafties.btgames.games.fourchips.FourChipsBoard.Companion.COLS
import us.crafties.btgames.games.fourchips.FourChipsBoard.Companion.ROWS

/**
 * 4 Chips messages ride inside Control.Game (reliable). Two kinds:
 *
 *   STATE   [1][round:u8][firstMover:u8][11 packed bytes]   14 bytes
 *           "This is the whole board." Sent after every drop. The 42 cells pack 2 bits
 *           each, LSB-first, so the full board fits the 40-byte game payload with room.
 *   REMATCH [2][round:u8]                                     2 bytes
 *           "I want to play round N." When both players have asked for the same round, it starts.
 *
 * Sending the whole board instead of "column 3" means a dropped, duplicated or re-ordered
 * message cannot desynchronise the phones: the next STATE simply overwrites the picture.
 * [round] increases with every rematch so a late STATE from the previous game is ignored.
 */
object FourChipsCodec {
    private const val T_STATE: Byte = 1
    private const val T_REMATCH: Byte = 2

    /** 42 cells at 2 bits = 84 bits = 11 bytes (last byte half-used). */
    const val PACKED_SIZE = 11

    sealed interface Msg {
        data class State(val round: Int, val board: FourChipsBoard) : Msg
        data class Rematch(val round: Int) : Msg
    }

    fun encodeState(round: Int, board: FourChipsBoard): ByteArray {
        val out = ByteArray(3 + PACKED_SIZE)
        out[0] = T_STATE
        out[1] = round.toByte()
        out[2] = board.firstMover.code.toByte()
        board.cells.forEachIndexed { i, chip ->
            val shift = (i % 4) * 2
            out[3 + i / 4] = (out[3 + i / 4].toInt() or (chip.code shl shift)).toByte()
        }
        return out
    }

    fun encodeRematch(round: Int): ByteArray = byteArrayOf(T_REMATCH, round.toByte())

    fun decode(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            T_STATE -> {
                if (data.size < 3 + PACKED_SIZE) return null
                val round = data[1].toInt() and 0xFF
                val first = Chip.fromCode(data[2].toInt()) ?: return null
                if (first == Chip.EMPTY) return null
                val cells = List(COLS * ROWS) { i ->
                    val shift = (i % 4) * 2
                    Chip.fromCode((data[3 + i / 4].toInt() ushr shift) and 0x03) ?: return null
                }
                // Refuse anything that could not arise from legal play.
                if (!FourChipsBoard.isReachable(cells, first)) return null
                Msg.State(round, FourChipsBoard(cells, first))
            }
            T_REMATCH -> if (data.size >= 2) Msg.Rematch(data[1].toInt() and 0xFF) else null
            else -> null
        }
    }
}
