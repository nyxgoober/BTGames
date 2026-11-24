package us.crafties.btgames.games.chess

/**
 * Chess messages ride inside Control.Game (reliable). Two kinds:
 *
 *   STATE   [1][round:u8][turn:u8][castling:u8][ep+1:u8][halfmove:u8][fullmove:u8][32 packed bytes]
 *           39 bytes — "This is the whole board." Sent after every move. The 64 squares pack
 *           two per byte (low nibble first).
 *   REMATCH [2][round:u8]                                                                2 bytes
 *           "I want to play round N." When both players have asked for the same round, it starts.
 *
 * Sending the whole board instead of "e2 to e4" means a dropped, duplicated or re-ordered
 * message cannot desynchronise the phones: the session accepts an incoming board only if it
 * is one legal move from the current one, so anything else is harmless.
 * [round] increases with every rematch so a late STATE from the previous game is ignored.
 */
object ChessCodec {
    private const val T_STATE: Byte = 1
    private const val T_REMATCH: Byte = 2

    /** Header (7) + 64 squares at 4 bits = 32 bytes. */
    const val STATE_SIZE = 39

    sealed interface Msg {
        data class State(val round: Int, val board: ChessBoard) : Msg
        data class Rematch(val round: Int) : Msg
    }

    fun encodeState(round: Int, b: ChessBoard): ByteArray {
        val out = ByteArray(STATE_SIZE)
        out[0] = T_STATE
        out[1] = round.toByte()
        out[2] = b.turn.code.toByte()
        out[3] = b.castling.toByte()
        out[4] = (b.enPassant + 1).toByte() // -1 -> 0
        out[5] = b.halfmove.coerceAtMost(100).toByte()
        out[6] = b.fullmove.toByte()
        b.cells.forEachIndexed { i, p ->
            val n = 7 + i / 2
            out[n] = if (i % 2 == 0) {
                (p.code and 15).toByte()
            } else {
                (out[n].toInt() or (p.code shl 4)).toByte()
            }
        }
        return out
    }

    fun encodeRematch(round: Int): ByteArray = byteArrayOf(T_REMATCH, round.toByte())

    fun decode(a: ByteArray): Msg? {
        if (a.isEmpty()) return null
        return when (a[0]) {
            T_STATE -> {
                if (a.size < STATE_SIZE) return null
                val round = a[1].toInt() and 0xFF
                val turn = ChessSide.entries.getOrNull(a[2].toInt()) ?: return null
                val rights = a[3].toInt() and 0xFF
                val ep = (a[4].toInt() and 0xFF) - 1
                val half = a[5].toInt() and 0xFF
                val full = a[6].toInt() and 0xFF
                val cells = List(64) { i ->
                    val v = a[7 + i / 2].toInt() and 0xFF
                    ChessPiece.from(if (i % 2 == 0) v and 15 else v ushr 4) ?: return null
                }
                // Refuse anything that could not arise from legal play.
                if (!ChessBoard.sane(cells, rights, ep) || half > 100 || full < 1) return null
                Msg.State(round, ChessBoard(cells, turn, rights, ep, half, full))
            }
            T_REMATCH -> if (a.size >= 2) Msg.Rematch(a[1].toInt() and 0xFF) else null
            else -> null
        }
    }
}
