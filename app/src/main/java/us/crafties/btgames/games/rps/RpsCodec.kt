package us.crafties.btgames.games.rps

/**
 * RPS messages ride inside Control.Game (reliable). Two kinds:
 *
 *   THROW   [1][match:u8][round:u8][throw:u8]   4 bytes
 *           "My throw for this round." Re-sent until the peer's throw arrives.
 *   REMATCH [2][match:u8]                        2 bytes
 *           "I want match N." When both players ask for the same match, it starts.
 *
 * Throws apply only to their own round and match; anything stale is ignored, so a lost
 * packet heals itself on the next resend and the two phones can never disagree.
 */
object RpsCodec {
    private const val T_THROW: Byte = 1
    private const val T_REMATCH: Byte = 2

    sealed interface Msg {
        data class ThrowMsg(val match: Int, val round: Int, val throw_: Throw) : Msg
        data class Rematch(val match: Int) : Msg
    }

    fun encodeThrow(match: Int, round: Int, throw_: Throw): ByteArray =
        byteArrayOf(T_THROW, match.toByte(), round.toByte(), throw_.code.toByte())

    fun encodeRematch(match: Int): ByteArray = byteArrayOf(T_REMATCH, match.toByte())

    fun decode(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            T_THROW -> {
                if (data.size < 4) return null
                val t = Throw.fromCode(data[3].toInt()) ?: return null
                Msg.ThrowMsg(data[1].toInt() and 0xFF, data[2].toInt() and 0xFF, t)
            }
            T_REMATCH -> if (data.size >= 2) Msg.Rematch(data[1].toInt() and 0xFF) else null
            else -> null
        }
    }
}
