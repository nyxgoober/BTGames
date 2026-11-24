package us.crafties.btgames.games.xo

/**
 * XO messages ride inside Control.Game (reliable). Two kinds:
 *
 *   STATE   [1][round:u8][firstMover:u8][c0..c8: one byte each]         12 bytes
 *           "This is the whole board." Sent after every move.
 *   REMATCH [2][round:u8]                                                2 bytes
 *           "I want to play round N." When both players have asked for the same round, it starts.
 *
 * Sending the whole board instead of "cell 4" means a dropped, duplicated or re-ordered message
 * cannot desynchronise the phones: the next STATE simply overwrites the picture.
 * [round] increases with every rematch so a late STATE from the previous game is ignored.
 */
object XoCodec {
    private const val T_STATE: Byte = 1
    private const val T_REMATCH: Byte = 2

    sealed interface Msg {
        data class State(val round: Int, val board: XoBoard) : Msg
        data class Rematch(val round: Int) : Msg
    }

    fun encodeState(round: Int, board: XoBoard): ByteArray {
        val out = ByteArray(3 + 9)
        out[0] = T_STATE
        out[1] = round.toByte()
        out[2] = board.firstMover.code.toByte()
        board.cells.forEachIndexed { i, m -> out[3 + i] = m.code.toByte() }
        return out
    }

    fun encodeRematch(round: Int): ByteArray = byteArrayOf(T_REMATCH, round.toByte())

    fun decode(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            T_STATE -> {
                if (data.size < 12) return null
                val round = data[1].toInt() and 0xFF
                val first = Mark.fromCode(data[2].toInt()) ?: return null
                if (first == Mark.EMPTY) return null
                val cells = List(9) { Mark.fromCode(data[3 + it].toInt()) ?: return null }
                // Refuse anything that could not arise from legal play.
                if (!XoBoard.isReachable(cells, first)) return null
                Msg.State(round, XoBoard(cells, first))
            }
            T_REMATCH -> if (data.size >= 2) Msg.Rematch(data[1].toInt() and 0xFF) else null
            else -> null
        }
    }
}
