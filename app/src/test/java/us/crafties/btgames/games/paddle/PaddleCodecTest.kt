package us.crafties.btgames.games.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleCodecTest {

    @Test fun inputRoundTripIsAccurate() {
        for (v in listOf(0f, 0.25f, 0.5f, 0.777f, 1f)) {
            val out = PaddleCodec.decodeInput(PaddleCodec.encodeInput(v))!!
            assertEquals(v, out, 1f / 60000f)
        }
    }

    @Test fun inputIsClampedIntoTheField() {
        assertEquals(0f, PaddleCodec.decodeInput(PaddleCodec.encodeInput(-3f))!!, 0f)
        assertEquals(1f, PaddleCodec.decodeInput(PaddleCodec.encodeInput(9f))!!, 0f)
    }

    @Test fun stateRoundTripIsAccurate() {
        val s = PaddleState(0.31f, 0.72f, -0.9f, 0.33f, 0.4f, 0.6f, 5, 3, Phase.PLAYING)
        val d = PaddleCodec.decodeState(PaddleCodec.encodeState(123, s))!!
        assertEquals(123, d.seq)
        assertEquals(s.ballX, d.state.ballX, 0.001f)
        assertEquals(s.ballY, d.state.ballY, 0.001f)
        assertEquals(s.ballVx, d.state.ballVx, 0.001f)
        assertEquals(s.ballVy, d.state.ballVy, 0.001f)
        assertEquals(s.hostPaddleY, d.state.hostPaddleY, 0.001f)
        assertEquals(s.guestPaddleY, d.state.guestPaddleY, 0.001f)
        assertEquals(5, d.state.hostScore); assertEquals(3, d.state.guestScore)
        assertEquals(Phase.PLAYING, d.state.phase)
    }

    @Test fun everyPhaseSurvives() {
        for (p in Phase.entries) {
            val d = PaddleCodec.decodeState(PaddleCodec.encodeState(0, PaddleState(phase = p)))!!
            assertEquals(p, d.state.phase)
        }
    }

    @Test fun sequenceNumbersUseAll16Bits() {
        for (seq in listOf(0, 1, 32767, 32768, 65535)) {
            assertEquals(seq, PaddleCodec.decodeState(PaddleCodec.encodeState(seq, PaddleState()))!!.seq)
        }
    }

    @Test fun velocityBeyondTheRangeIsClampedNotWrapped() {
        // A wrapped value would flip the ball's direction on the guest's screen.
        val d = PaddleCodec.decodeState(PaddleCodec.encodeState(0, PaddleState(ballVx = 50f, ballVy = -50f)))!!
        assertTrue("vx stays positive", d.state.ballVx > 3.9f)
        assertTrue("vy stays negative", d.state.ballVy < -3.9f)
    }

    @Test fun statePacketFitsInDefaultAttPayload() {
        val enc = PaddleCodec.encodeState(1, PaddleState())
        assertEquals(PaddleCodec.STATE_SIZE, enc.size)
        assertTrue("state packet ${enc.size}B must fit the 20B default ATT payload", enc.size <= 20)
    }

    // ---- reliable score events ---------------------------------------------------------

    @Test fun scoreAndOverEventsRoundTrip() {
        assertEquals(PaddleCodec.Event.Score(3, 4), PaddleCodec.decodeEvent(PaddleCodec.encodeScore(3, 4)))
        assertEquals(PaddleCodec.Event.Over(7, 2), PaddleCodec.decodeEvent(PaddleCodec.encodeOver(7, 2)))
    }

    @Test fun eventsAreSmallEnoughForTheGamePayload() {
        assertTrue(PaddleCodec.encodeScore(7, 7).size <= us.crafties.btgames.net.Proto.MAX_GAME_PAYLOAD)
    }

    // ---- malformed input ---------------------------------------------------------------

    @Test fun garbageIsIgnoredNotThrown() {
        assertNull(PaddleCodec.decodeInput(byteArrayOf()))
        assertNull(PaddleCodec.decodeInput(byteArrayOf(1)))
        assertNull(PaddleCodec.decodeInput(byteArrayOf(99, 0, 0)))            // wrong type
        assertNull(PaddleCodec.decodeState(byteArrayOf(PaddleCodec.T_STATE, 0, 0)))  // truncated
        assertNull(PaddleCodec.decodeState(ByteArray(PaddleCodec.STATE_SIZE)))  // right size, wrong type byte (0)
        assertNull(PaddleCodec.decodeEvent(byteArrayOf()))
        assertNull(PaddleCodec.decodeEvent(byteArrayOf(PaddleCodec.E_SCORE, 1)))
        assertNull(PaddleCodec.decodeEvent(byteArrayOf(99, 0, 0, 0, 0)))
    }

    @Test fun anUnknownPhaseByteFallsBackInsteadOfCrashing() {
        val enc = PaddleCodec.encodeState(0, PaddleState())
        enc[enc.size - 1] = 99
        assertEquals(Phase.PLAYING, PaddleCodec.decodeState(enc)!!.state.phase)
    }
}
