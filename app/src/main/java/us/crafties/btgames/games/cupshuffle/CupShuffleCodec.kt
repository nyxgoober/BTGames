package us.crafties.btgames.games.cupshuffle

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cup Shuffle messages ride inside Control.Game (reliable). Six kinds:
 *
 *   CHOICE  [1][round:u8][cups:u8][ball:u8]                          4 bytes
 *           "I hid the ball under cup [ball] of [cups]." Opens the show (both phones display
 *           the ball openly for a moment), then the shuffling. Re-sent until the round is
 *           visibly moving, so a guesser that missed the first one still joins.
 *   SHUFFLE [2][round:u8][seq:u16 LE][cups:u8][order...]              5 + cups bytes
 *           "Swap number [seq] leaves the cups in [order]." One tap = one swap. Each packet
 *           carries the whole order, so a lost tap cannot desynchronise the guesser: the next
 *           SHUFFLE simply overwrites the picture. Only newer [seq] applies.
 *   END     [3][round:u8][total:u16 LE][cups:u8]                      5 bytes
 *           "Shuffling is over after [total] swaps." Re-sent until the guess arrives. Carries
 *           [cups] so even a guesser that missed everything can still play the guessing half.
 *   GUESS   [4][round:u8][pos:u8]                                    3 bytes
 *           "I pick the cup now at slot [pos]." Re-sent until the result arrives.
 *   RESULT  [5][round:u8][ball:u8][guess:u8]                         4 bytes
 *           "The ball is at slot [ball]; you picked [guess]." Authoritative; re-sent a few
 *           times so a lost packet heals itself.
 *   REMATCH [6][round:u8]                                            2 bytes
 *           "I want round N." When both players ask for the same round, it starts (roles swap).
 *
 * Cup ids are 0 until cups-1; [order] lists the id in each display slot left to right;
 * [ball]/[pos]/[guess] are slots except in CHOICE, where [ball] is the id (order is initial).
 */
object CupShuffleCodec {
    private const val T_CHOICE: Byte = 1
    private const val T_SHUFFLE: Byte = 2
    private const val T_END: Byte = 3
    private const val T_GUESS: Byte = 4
    private const val T_RESULT: Byte = 5
    private const val T_REMATCH: Byte = 6

    sealed interface Msg {
        data class Choice(val round: Int, val cups: Int, val ball: Int) : Msg
        data class Shuffle(val round: Int, val seq: Int, val cups: Int, val order: List<Int>) : Msg
        data class End(val round: Int, val total: Int, val cups: Int) : Msg
        data class Guess(val round: Int, val pos: Int) : Msg
        data class Result(val round: Int, val ball: Int, val guess: Int) : Msg
        data class Rematch(val round: Int) : Msg
    }

    fun encodeChoice(round: Int, cups: Int, ball: Int): ByteArray =
        byteArrayOf(T_CHOICE, round.toByte(), cups.toByte(), ball.toByte())

    fun encodeShuffle(round: Int, seq: Int, order: List<Int>): ByteArray =
        buf(5 + order.size).put(T_SHUFFLE).put(round.toByte()).putShort(seq.toShort())
            .put(order.size.toByte()).apply { order.forEach { put(it.toByte()) } }.array()

    fun encodeEnd(round: Int, total: Int, cups: Int): ByteArray =
        buf(5).put(T_END).put(round.toByte()).putShort(total.toShort()).put(cups.toByte()).array()

    fun encodeGuess(round: Int, pos: Int): ByteArray = byteArrayOf(T_GUESS, round.toByte(), pos.toByte())

    fun encodeResult(round: Int, ball: Int, guess: Int): ByteArray =
        byteArrayOf(T_RESULT, round.toByte(), ball.toByte(), guess.toByte())

    fun encodeRematch(round: Int): ByteArray = byteArrayOf(T_REMATCH, round.toByte())

    fun decode(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return try {
            when (data[0]) {
                T_CHOICE -> {
                    if (data.size < 4) return null
                    val round = data[1].toInt() and 0xFF
                    val cups = data[2].toInt() and 0xFF
                    val ball = data[3].toInt() and 0xFF
                    if (!validCups(cups) || ball >= cups) return null
                    Msg.Choice(round, cups, ball)
                }
                T_SHUFFLE -> {
                    if (data.size < 5) return null
                    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    b.get()
                    val round = b.get().toInt() and 0xFF
                    val seq = b.short.toInt() and 0xFFFF
                    val cups = b.get().toInt() and 0xFF
                    if (!validCups(cups) || data.size < 5 + cups) return null
                    val order = List(cups) { b.get().toInt() and 0xFF }
                    if (!validOrder(order)) return null
                    Msg.Shuffle(round, seq, cups, order)
                }
                T_END -> {
                    if (data.size < 5) return null
                    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    b.get()
                    val round = b.get().toInt() and 0xFF
                    val total = b.short.toInt() and 0xFFFF
                    val cups = b.get().toInt() and 0xFF
                    if (!validCups(cups)) return null
                    Msg.End(round, total, cups)
                }
                T_GUESS -> {
                    if (data.size < 3) return null
                    Msg.Guess(data[1].toInt() and 0xFF, data[2].toInt() and 0xFF)
                }
                T_RESULT -> {
                    if (data.size < 4) return null
                    Msg.Result(data[1].toInt() and 0xFF, data[2].toInt() and 0xFF, data[3].toInt() and 0xFF)
                }
                T_REMATCH -> if (data.size >= 2) Msg.Rematch(data[1].toInt() and 0xFF) else null
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A permutation of 0 until cups-1: every id exactly once, so no cup duplicates or vanishes. */
    fun validOrder(order: List<Int>): Boolean {
        if (!validCups(order.size)) return false
        return order.sorted() == List(order.size) { it }
    }

    fun validCups(cups: Int): Boolean = cups in CupShuffleLogic.MIN_CUPS..CupShuffleLogic.MAX_CUPS

    private fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
