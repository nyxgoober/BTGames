package us.crafties.btgames.games.picturematch

/** Wire format for Picture Match. All messages ride inside a reliable Control.Game. */
object PictureMatchCodec {
    private const val T_SEED: Byte = 1
    private const val T_READY: Byte = 2
    private const val T_PICK: Byte = 3
    private const val T_STATE: Byte = 4
    private const val T_AGAIN: Byte = 5

    /** Sentinel for "no tile pending" in a STATE snapshot. */
    const val NO_TILE: Int = 255

    sealed interface Msg {
        /** Host deals a fresh board: match number, grid width and shuffle seed. */
        data class Seed(val match: Int, val grid: Int, val seed: Int) : Msg
        data class Ready(val match: Int) : Msg
        /** A player asks to turn tile [tile] up. The transport stamps the sender index. */
        data class Pick(val match: Int, val tile: Int) : Msg
        /** Host's authoritative snapshot: two bits per tile, least significant first. */
        data class State(
            val match: Int,
            val grid: Int,
            val turn: Int,
            val scores: IntArray,
            val pending: Int,
            val last: Int,
            val packed: ByteArray,
        ) : Msg
        data class Again(val match: Int) : Msg
    }

    private fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun encode(m: Msg): ByteArray = when (m) {
        is Msg.Seed -> byteArrayOf(T_SEED, m.match.toByte(), m.grid.toByte()) + i32(m.seed)
        is Msg.Ready -> byteArrayOf(T_READY, m.match.toByte())
        is Msg.Pick -> byteArrayOf(T_PICK, m.match.toByte(), m.tile.toByte())
        is Msg.State -> byteArrayOf(
            T_STATE, m.match.toByte(), m.grid.toByte(), m.turn.toByte(),
            m.pending.toByte(), m.last.toByte(),
        ) + ByteArray(PictureIcons.MAX_PLAYERS) { m.scores.getOrElse(it) { 0 }.toByte() } + m.packed
        is Msg.Again -> byteArrayOf(T_AGAIN, m.match.toByte())
    }

    fun decode(b: ByteArray): Msg? {
        fun u(i: Int) = b[i].toInt() and 0xFF
        fun tile(i: Int) = if (u(i) == NO_TILE) -1 else u(i)
        if (b.isEmpty()) return null
        return when (b[0]) {
            T_SEED -> if (b.size >= 7 && u(2) in PictureIcons.VALID_GRIDS) {
                Msg.Seed(u(1), u(2), u(3) or (u(4) shl 8) or (u(5) shl 16) or (u(6) shl 24))
            } else null
            T_READY -> if (b.size >= 2) Msg.Ready(u(1)) else null
            T_PICK -> if (b.size >= 3) Msg.Pick(u(1), u(2)) else null
            T_STATE -> if (b.size >= 12 && u(2) in PictureIcons.VALID_GRIDS) {
                val cells = u(2) * u(2)
                val maskLen = (cells + 3) / 4
                if (b.size != 12 + maskLen) return null
                val pairs = cells / 2
                if ((u(4) != NO_TILE && u(4) >= cells) || (u(5) != NO_TILE && u(5) >= cells)) return null
                if ((6 until 12).any { u(it) > pairs }) return null
                Msg.State(
                    u(1), u(2), u(3), IntArray(PictureIcons.MAX_PLAYERS) { u(6 + it) },
                    tile(4), tile(5), b.copyOfRange(12, b.size),
                )
            } else null
            T_AGAIN -> if (b.size >= 2) Msg.Again(u(1)) else null
            else -> null
        }
    }
}
