package us.crafties.btgames.games.shutthebox

import us.crafties.btgames.net.Control

/** How a match is played. Chosen by the seat-0 player before the first roll. */
object StbMode {
    /** One player keeps rolling until the box is shut or a roll has no move; then the next player. */
    const val UNTIL_DONE = 0
    /** One roll and one move per turn, then the turn passes. A roll with no move puts you out. */
    const val ONE_FLIP = 1
}

/** Small binary codec for Shut-The-Box messages carried in Control.Game payloads. */
object ShutTheBoxCodec {
    private const val T_MODE: Byte = 1
    private const val T_STATE: Byte = 2
    private const val T_DONE: Byte = 3
    private const val T_TURN: Byte = 4
    private const val T_FINAL: Byte = 5

    sealed class Msg {
        /**
         * Seat 0 chose the mode and tile count. [screen] = the host is a table screen and holds no seat,
         * so player seats start at transport index 1 (the table screen repeats this message while it waits).
         */
        data class Mode(val mode: Int, val tiles: Int, val screen: Boolean = false) : Msg()
        /** The active player's box and dice, sent after every roll and move so everyone sees it. */
        data class State(val seat: Int, val mask: Int, val die1: Int, val die2: Int, val rollId: Int) : Msg()
        /** A player's turn is over. [out] = they are finished for the whole match with [score]. */
        data class Done(val seat: Int, val score: Int, val out: Boolean) : Msg()
        /** Seat 0 announces whose turn it is (and the mode and tile count, so late phones catch up). */
        data class Turn(val seat: Int, val mode: Int, val tiles: Int, val screen: Boolean = false) : Msg()
        data class Final(val winners: List<Int>) : Msg()
    }

    fun encode(msg: Msg): ByteArray = when (msg) {
        is Msg.Mode -> byteArrayOf(T_MODE, msg.mode.toByte(), msg.tiles.toByte(), if (msg.screen) 1 else 0)
        is Msg.State -> byteArrayOf(
            T_STATE, msg.seat.toByte(), (msg.mask and 0xFF).toByte(), ((msg.mask shr 8) and 0xFF).toByte(),
            msg.die1.toByte(), msg.die2.toByte(), (msg.rollId and 0xFF).toByte(),
        )
        is Msg.Done -> byteArrayOf(T_DONE, msg.seat.toByte(), msg.score.toByte(), if (msg.out) 1 else 0)
        is Msg.Turn -> byteArrayOf(T_TURN, msg.seat.toByte(), msg.mode.toByte(), msg.tiles.toByte(), if (msg.screen) 1 else 0)
        is Msg.Final -> byteArrayOf(T_FINAL, msg.winners.size.toByte()) + ByteArray(msg.winners.size) { msg.winners[it].toByte() }
    }

    fun decode(b: ByteArray): Msg? {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.isEmpty()) return null
        return when (b[0]) {
            T_MODE -> if (b.size >= 3) Msg.Mode(u(1), u(2), screen = b.size >= 4 && u(3) != 0) else null
            T_STATE -> if (b.size >= 7) Msg.State(u(1), u(2) or (u(3) shl 8), u(4), u(5), u(6)) else null
            T_DONE -> if (b.size >= 4) Msg.Done(u(1), u(2), u(3) != 0) else null
            T_TURN -> if (b.size >= 4) Msg.Turn(u(1), u(2), u(3), screen = b.size >= 5 && u(4) != 0) else null
            T_FINAL -> if (b.size >= 2 && b.size >= 2 + u(1)) Msg.Final((0 until u(1)).map { u(2 + it) }) else null
            else -> null
        }
    }

    fun decodeControl(msg: Control.Game): Msg? = decode(msg.payload)
}
