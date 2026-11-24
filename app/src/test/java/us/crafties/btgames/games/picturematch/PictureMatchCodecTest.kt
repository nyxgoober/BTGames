package us.crafties.btgames.games.picturematch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureMatchCodecTest {
    private fun round(m: PictureMatchCodec.Msg) = PictureMatchCodec.decode(PictureMatchCodec.encode(m))

    private fun sameState(a: PictureMatchCodec.Msg.State, b: PictureMatchCodec.Msg?) {
        assertTrue(b is PictureMatchCodec.Msg.State)
        b as PictureMatchCodec.Msg.State
        assertEquals(a.match, b.match)
        assertEquals(a.grid, b.grid)
        assertEquals(a.turn, b.turn)
        assertTrue(a.scores.contentEquals(b.scores))
        assertEquals(a.pending, b.pending)
        assertEquals(a.last, b.last)
        assertTrue(a.packed.contentEquals(b.packed))
    }

    @Test fun everyMessageSurvivesARoundTrip() {
        val state = PictureMatchCodec.Msg.State(
            5, 10, 3, intArrayOf(7, 6, 5, 4, 3, 2), -1, -1, ByteArray(25),
        )
        assertEquals(PictureMatchCodec.Msg.Seed(3, 8, -123456), round(PictureMatchCodec.Msg.Seed(3, 8, -123456)))
        assertEquals(PictureMatchCodec.Msg.Ready(3), round(PictureMatchCodec.Msg.Ready(3)))
        assertEquals(PictureMatchCodec.Msg.Pick(3, 63), round(PictureMatchCodec.Msg.Pick(3, 63)))
        assertEquals(PictureMatchCodec.Msg.Again(3), round(PictureMatchCodec.Msg.Again(3)))
        sameState(state, round(state))
    }

    @Test fun payloadsFitTheWireLimit() {
        val full = PictureMatchCodec.Msg.State(
            1, 10, 0, IntArray(6) { 50 }, 99, 98, ByteArray(25),
        )
        assertEquals(37, PictureMatchCodec.encode(full).size)
        assertTrue(PictureMatchCodec.encode(full).size <= 40)
        assertEquals(7, PictureMatchCodec.encode(PictureMatchCodec.Msg.Seed(1, 6, 1)).size)
    }

    @Test fun malformedMessagesAreRejected() {
        assertNull(PictureMatchCodec.decode(byteArrayOf()))
        assertNull(PictureMatchCodec.decode(byteArrayOf(9)))
        assertNull(PictureMatchCodec.decode(byteArrayOf(1, 1, 7, 0, 0, 0, 0)))   // bad grid
        assertNull(PictureMatchCodec.decode(byteArrayOf(4, 1, 6, 0, 0, 0)))       // short state
        val good = PictureMatchCodec.encode(
            PictureMatchCodec.Msg.State(1, 6, 0, IntArray(6), -1, -1, ByteArray(9)),
        )
        assertNull(PictureMatchCodec.decode(good + 0))                              // trailing byte
        val badScore = good.copyOf(); badScore[6] = 20                               // more pairs than exist
        assertNull(PictureMatchCodec.decode(badScore))
    }

    @Test fun noTileSentinelDecodesToMinusOne() {
        val m = round(PictureMatchCodec.Msg.State(1, 6, 0, IntArray(6), -1, -1, ByteArray(9)))
        assertTrue(m is PictureMatchCodec.Msg.State)
        assertEquals(-1, (m as PictureMatchCodec.Msg.State).pending)
        assertEquals(-1, m.last)
    }
}
