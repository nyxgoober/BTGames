package us.crafties.btgames.games.cupshuffle

import kotlin.random.Random

/**
 * Pure cup-order helpers. Cups have fixed ids (0 until cups-1); [order] lists the id in each
 * display slot, left to right. A shuffle swaps two slots; the ball rides along with its cup.
 * Pure Kotlin so the behaviour can be unit tested without a phone.
 */
object CupShuffleLogic {
    const val MIN_CUPS = 3
    const val MAX_CUPS = 6

    fun initialOrder(cups: Int): List<Int> {
        require(cups in MIN_CUPS..MAX_CUPS) { "cups must be $MIN_CUPS..$MAX_CUPS" }
        return List(cups) { it }
    }

    /** [order] with the cups in slots [a] and [b] exchanged. */
    fun swapped(order: List<Int>, a: Int, b: Int): List<Int> {
        require(a in order.indices && b in order.indices && a != b) { "swap $a <-> $b not in $order" }
        return order.toMutableList().also {
            val t = it[a]
            it[a] = it[b]
            it[b] = t
        }
    }

    /** The slot holding [ballCup] after swapping slots [a] and [b]. */
    fun trackBall(ballSlot: Int, a: Int, b: Int): Int = when (ballSlot) {
        a -> b
        b -> a
        else -> ballSlot
    }

    /**
     * One followable shuffle: a random neighbouring pair. Neighbours, not any two cups, so the
     * guesser can track at the 250 ms tap spacing the session enforces.
     */
    fun randomAdjacentPair(rnd: Random, cups: Int): Pair<Int, Int> {
        require(cups in MIN_CUPS..MAX_CUPS) { "cups must be $MIN_CUPS..$MAX_CUPS" }
        val k = rnd.nextInt(cups - 1)
        return k to k + 1
    }
}
