package us.crafties.btgames.games.checkers

/**
 * Checkers messages ride inside Control.Game (reliable). Two kinds:
 *
 *   STATE   [1][round:u8][firstMover:u8][turn:u8][pending:u8][ply:u8][24 packed bytes]  30 bytes
 *           "This is the whole board." Sent after every step, including each landing of a
 *           chained capture. The 64 squares pack at 3 bits each, LSB-first; [pending] is 0
 *           for a free turn, otherwise cell + 1.
 *   REMATCH [2][round:u8]                                                                2 bytes
 *           "I want to play round N." When both players have asked for the same round, it starts.
 *
 * Sending the whole board instead of "e3 to d4" means a dropped, duplicated or re-ordered
 * message cannot desynchronise the phones: the session accepts an incoming board only if it
 * is one legal step from the current one, so anything else is harmless.
 * [round] increases with every rematch so a late STATE from the previous game is ignored.
 */
object CheckersCodec {
    private const val T_STATE: Byte = 1
    private const val T_REMATCH: Byte = 2

    /** 64 squares at 3 bits = 192 bits = 24 bytes. */
    const val PACKED_SIZE = 24

    sealed interface Msg {
        data class State(val round: Int, val board: CheckersBoard) : Msg
        data class Rematch(val round: Int) : Msg
    }

    fun encodeState(round: Int, board: CheckersBoard): ByteArray {
        val out = ByteArray(6 + PACKED_SIZE)
        out[0] = T_STATE
        out[1] = round.toByte()
        out[2] = board.firstMover.code.toByte()
        out[3] = board.turn.code.toByte()
        out[4] = (board.pending + 1).toByte() // -1 -> 0
        out[5] = board.ply.toByte()
        board.cells.forEachIndexed { i, piece ->
            val bit = i * 3
            val code = piece.code
            for (k in 0..2) {
                if ((code ushr k) and 1 == 1) {
                    val b = bit + k
                    out[6 + b / 8] = (out[6 + b / 8].toInt() or (1 shl (b % 8))).toByte()
                }
            }
        }
        return out
    }

    fun encodeRematch(round: Int): ByteArray = byteArrayOf(T_REMATCH, round.toByte())

    fun decode(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            T_STATE -> {
                if (data.size < 6 + PACKED_SIZE) return null
                val round = data[1].toInt() and 0xFF
                val first = CheckerSide.fromCode(data[2].toInt()) ?: return null
                val turn = CheckerSide.fromCode(data[3].toInt()) ?: return null
                val pendingRaw = data[4].toInt() and 0xFF
                if (pendingRaw > 64) return null
                val ply = data[5].toInt() and 0xFF
                val cells = List(64) { i ->
                    val bit = i * 3
                    var code = 0
                    for (k in 0..2) {
                        val b = bit + k
                        if ((data[6 + b / 8].toInt() ushr (b % 8)) and 1 == 1) code = code or (1 shl k)
                    }
                    Piece.fromCode(code) ?: return null // codes 5..7 are never sent
                }
                val pending = pendingRaw - 1
                // Refuse anything that could not arise from legal play.
                if (!CheckersBoard.isSane(cells, turn, pending)) return null
                Msg.State(round, CheckersBoard(cells, first, turn, pending, ply))
            }
            T_REMATCH -> if (data.size >= 2) Msg.Rematch(data[1].toInt() and 0xFF) else null
            else -> null
        }
    }
}
