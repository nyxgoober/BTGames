package us.crafties.btgames.games.tetriminoes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TetriminoesCodecTest {

    @Test fun choiceRoundTrips() {
        val m = TetriminoesCodec.decode(TetriminoesCodec.encodeChoice(2, 120))
        assertEquals(TetriminoesCodec.Msg.Choice(2, 120), m)
    }

    @Test fun choiceRejectsDurationsNobodyOffered() {
        // 45 s is well-formed but not one of DURATIONS: refuse it.
        val raw = TetriminoesCodec.encodeChoice(0, 120).copyOf()
        raw[2] = 45
        raw[3] = 0
        assertNull(TetriminoesCodec.decode(raw))
    }

    @Test fun updateRoundTripsWithDoneFlag() {
        val m = TetriminoesCodec.decode(TetriminoesCodec.encodeUpdate(1, 12_345, 12, 7, true))
        assertEquals(TetriminoesCodec.Msg.Update(1, 12_345, 12, 7, true), m)
        val open = TetriminoesCodec.decode(TetriminoesCodec.encodeUpdate(1, 0, 0, 1, false))
        assertEquals(TetriminoesCodec.Msg.Update(1, 0, 0, 1, false), open)
    }

    @Test fun updateRejectsImpossibleLevels() {
        assertNull(TetriminoesCodec.decode(TetriminoesCodec.encodeUpdate(0, 100, 1, 0, false)))
        assertNull(TetriminoesCodec.decode(TetriminoesCodec.encodeUpdate(0, 100, 1, 11, false)))
    }

    @Test fun rematchRoundTrips() {
        assertEquals(TetriminoesCodec.Msg.Rematch(3), TetriminoesCodec.decode(TetriminoesCodec.encodeRematch(3)))
        assertNull(TetriminoesCodec.decode(byteArrayOf(3)))
    }

    @Test fun garbageDecodesToNull() {
        assertNull(TetriminoesCodec.decode(byteArrayOf()))
        assertNull(TetriminoesCodec.decode(byteArrayOf(9, 1, 2, 3)))
        assertNull(TetriminoesCodec.decode(byteArrayOf(1, 2)))
        assertTrue(TetriminoesCodec.DURATIONS.size >= 3)
    }
}
