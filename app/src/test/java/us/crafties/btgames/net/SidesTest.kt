package us.crafties.btgames.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SidesTest {

    private val rnd = Random(20240921)

    /** Distinct random token pairs; an exact tie is turned into an ordinary request elsewhere. */
    private fun pairs(n: Int = 50_000): List<Pair<Int, Int>> =
        generateSequence { rnd.nextInt() to rnd.nextInt() }.filter { it.first != it.second }.take(n).toList()

    @Test
    fun anOrdinaryRequestGivesTheTwoPlayersOppositeColours() {
        for ((asker, _) in pairs()) {
            // The asker anchors on its own token; the acceptor read that same token out of the HELLO.
            assertNotEquals(Sides.forAsker(asker), Sides.forAcceptor(asker))
        }
    }

    @Test
    fun aMutualRequestGivesTheTwoPlayersOppositeColours() {
        for ((a, b) in pairs()) {
            // Each phone calls it with (mine, theirs), so the arguments are swapped between them.
            assertNotEquals(Sides.forMutual(a, b), Sides.forMutual(b, a))
        }
    }

    @Test
    fun whenBothPeopleAcceptEachOthersRequestTheColoursStillDiffer() {
        // The fallback after a failed mutual attempt: both phones accept while each also asked.
        // They must use the symmetric rule, not "anchor on whoever asked", or they would collide.
        for ((a, b) in pairs()) {
            assertNotEquals(Sides.forMutual(a, b), Sides.forMutual(b, a))
        }
        // And the naive rule really would collide, which is why accept() does not use it.
        val collisions = pairs().count { (a, b) -> Sides.forAcceptor(b) == Sides.forAcceptor(a) }
        assertTrue("naive both-accept rule should collide often, got $collisions", collisions > 10_000)
    }

    @Test
    fun theSamePairAlwaysGetsTheSameColours() {
        assertEquals(Sides.forMutual(1234, 99), Sides.forMutual(1234, 99))
        assertEquals(Sides.forAsker(-7), Sides.forAsker(-7))
    }

    @Test
    fun colourIsAFairCoinFlip() {
        val reds = pairs().count { (a, _) -> Sides.forAsker(a) == Side.RED }
        val share = reds / 50_000.0
        assertTrue("red share $share", share in 0.48..0.52)
    }

    @Test
    fun consecutiveTokensStillSplitEvenly() {
        // A naive `token and 1` would alternate perfectly here, which is also bad: it would make the
        // colour predictable from the token. The mixer must not produce a lopsided or striped result.
        val reds = (0 until 20_000).count { Sides.forAsker(it) == Side.RED }
        assertTrue("sequential red share ${reds / 20_000.0}", reds in 9_500..10_500)
        val runs = (1 until 20_000).count { Sides.forAsker(it) != Sides.forAsker(it - 1) }
        assertTrue("sequential tokens should not strictly alternate ($runs flips)", runs < 19_000)
    }

    @Test
    fun otherFlipsTheSide() {
        assertEquals(Side.BLUE, Side.RED.other())
        assertEquals(Side.RED, Side.BLUE.other())
    }
}
