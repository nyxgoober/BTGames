package us.crafties.btgames.games.cupshuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CupShuffleCodecTest {

    @Test fun choiceRoundTrips() {
        val m = CupShuffleCodec.decode(CupShuffleCodec.encodeChoice(7, 5, 4)) as CupShuffleCodec.Msg.Choice
        assertEquals(7, m.round)
        assertEquals(5, m.cups)
        assertEquals(4, m.ball)
    }

    @Test fun shuffleRoundTrips() {
        val order = listOf(2, 0, 1, 3)
        val m = CupShuffleCodec.decode(CupShuffleCodec.encodeShuffle(2, 300, order)) as CupShuffleCodec.Msg.Shuffle
        assertEquals(2, m.round)
        assertEquals(300, m.seq)
        assertEquals(4, m.cups)
        assertEquals(order, m.order)
    }

    @Test fun endGuessResultRematchRoundTrip() {
        val e = CupShuffleCodec.decode(CupShuffleCodec.encodeEnd(1, 20, 3)) as CupShuffleCodec.Msg.End
        assertEquals(1, e.round)
        assertEquals(20, e.total)
        assertEquals(3, e.cups)
        val g = CupShuffleCodec.decode(CupShuffleCodec.encodeGuess(1, 2)) as CupShuffleCodec.Msg.Guess
        assertEquals(2, g.pos)
        val r = CupShuffleCodec.decode(CupShuffleCodec.encodeResult(1, 0, 2)) as CupShuffleCodec.Msg.Result
        assertEquals(0, r.ball)
        assertEquals(2, r.guess)
        val rm = CupShuffleCodec.decode(CupShuffleCodec.encodeRematch(2)) as CupShuffleCodec.Msg.Rematch
        assertEquals(2, rm.round)
    }

    @Test fun rejectsBadCupCounts() {
        assertNull(CupShuffleCodec.decode(CupShuffleCodec.encodeChoice(0, 2, 0)))
        assertNull(CupShuffleCodec.decode(CupShuffleCodec.encodeChoice(0, 7, 0)))
        assertNull(CupShuffleCodec.decode(byteArrayOf(1, 0, 3, 5))) // ball past the last cup
        assertNull(CupShuffleCodec.decode(CupShuffleCodec.encodeEnd(0, 0, 2)))
    }

    @Test fun rejectsNonPermutationOrders() {
        // A duplicated cup id: one cup would vanish on the guesser's phone.
        assertNull(CupShuffleCodec.decode(CupShuffleCodec.encodeShuffle(0, 1, listOf(0, 0, 2))))
        // Too few cups for a shuffle to hide behind.
        assertNull(CupShuffleCodec.decode(CupShuffleCodec.encodeShuffle(0, 1, listOf(0, 1))))
    }

    @Test fun rejectsTruncatedAndForeignPackets() {
        assertNull(CupShuffleCodec.decode(byteArrayOf()))
        assertNull(CupShuffleCodec.decode(byteArrayOf(9, 0)))
        assertNull(CupShuffleCodec.decode(CupShuffleCodec.encodeGuess(0, 0).copyOf(1)))
        assertNull(CupShuffleCodec.decode(CupShuffleCodec.encodeShuffle(0, 1, listOf(0, 1, 2)).copyOf(6)))
    }

    @Test fun biggestShuffleFitsTheRadio() {
        val bytes = CupShuffleCodec.encodeShuffle(255, 65535, listOf(0, 1, 2, 3, 4, 5))
        assertTrue("SHUFFLE must fit MAX_GAME_PAYLOAD", bytes.size <= 40)
    }
}
