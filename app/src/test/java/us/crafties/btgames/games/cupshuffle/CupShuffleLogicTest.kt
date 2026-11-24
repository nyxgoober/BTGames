package us.crafties.btgames.games.cupshuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CupShuffleLogicTest {

    @Test fun swapsExchangeSlots() {
        assertEquals(listOf(1, 0, 2), CupShuffleLogic.swapped(listOf(0, 1, 2), 0, 1))
        assertEquals(listOf(0, 2, 1), CupShuffleLogic.swapped(listOf(0, 1, 2), 1, 2))
    }

    @Test fun ballRidesWithItsCup() {
        assertEquals(1, CupShuffleLogic.trackBall(0, 0, 1))
        assertEquals(0, CupShuffleLogic.trackBall(1, 0, 1))
        assertEquals(2, CupShuffleLogic.trackBall(2, 0, 1))
    }

    @Test fun adjacentPairsStayNeighbours() {
        val rnd = Random(42)
        repeat(100) {
            val (a, b) = CupShuffleLogic.randomAdjacentPair(rnd, 5)
            assertEquals(1, b - a)
            assertTrue(a in 0..3)
        }
    }

    @Test fun shuffledOrderStaysAValidPermutation() {
        val rnd = Random(7)
        var order = CupShuffleLogic.initialOrder(6)
        repeat(50) {
            val (a, b) = CupShuffleLogic.randomAdjacentPair(rnd, 6)
            order = CupShuffleLogic.swapped(order, a, b)
            assertTrue(CupShuffleCodec.validOrder(order))
        }
    }

    @Test fun ballTrackedThroughSwapsMatchesOrder() {
        val rnd = Random(13)
        var order = CupShuffleLogic.initialOrder(4)
        var ballCup = 1
        var ballSlot = 1
        repeat(50) {
            val (a, b) = CupShuffleLogic.randomAdjacentPair(rnd, 4)
            order = CupShuffleLogic.swapped(order, a, b)
            ballSlot = CupShuffleLogic.trackBall(ballSlot, a, b)
            assertEquals("slot must hold the ball cup", ballCup, order[ballSlot])
        }
    }
}
