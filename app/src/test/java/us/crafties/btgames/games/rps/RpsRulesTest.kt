package us.crafties.btgames.games.rps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import us.crafties.btgames.R

class RpsRulesTest {

    @Test fun everyThrowBeatsOneAndLosesToOne() {
        assertEquals(RpsResult.WIN, RpsRules.outcome(Throw.ROCK, Throw.SCISSORS))
        assertEquals(RpsResult.WIN, RpsRules.outcome(Throw.PAPER, Throw.ROCK))
        assertEquals(RpsResult.WIN, RpsRules.outcome(Throw.SCISSORS, Throw.PAPER))
        assertEquals(RpsResult.LOSS, RpsRules.outcome(Throw.SCISSORS, Throw.ROCK))
        assertEquals(RpsResult.LOSS, RpsRules.outcome(Throw.ROCK, Throw.PAPER))
        assertEquals(RpsResult.LOSS, RpsRules.outcome(Throw.PAPER, Throw.SCISSORS))
    }

    @Test fun sameThrowIsADraw() {
        for (t in Throw.entries) assertEquals(t.name, RpsResult.DRAW, RpsRules.outcome(t, t))
    }

    @Test fun verbsPointAtResources() {
        assertEquals(R.string.rps_verb_rock, RpsRules.verb(Throw.ROCK))
        assertEquals(R.string.rps_verb_paper, RpsRules.verb(Throw.PAPER))
        assertEquals(R.string.rps_verb_scissors, RpsRules.verb(Throw.SCISSORS))
    }
}

class RpsCodecTest {

    @Test fun throwRoundTrips() {
        val m = RpsCodec.decode(RpsCodec.encodeThrow(2, 7, Throw.PAPER))
        assertEquals(RpsCodec.Msg.ThrowMsg(2, 7, Throw.PAPER), m)
    }

    @Test fun throwRejectsUnknownThrows() {
        val raw = RpsCodec.encodeThrow(0, 0, Throw.ROCK).copyOf()
        raw[3] = 9
        assertNull(RpsCodec.decode(raw))
        assertNull(RpsCodec.decode(byteArrayOf(1, 0)))
    }

    @Test fun rematchRoundTrips() {
        assertEquals(RpsCodec.Msg.Rematch(4), RpsCodec.decode(RpsCodec.encodeRematch(4)))
    }

    @Test fun garbageDecodesToNull() {
        assertNull(RpsCodec.decode(byteArrayOf()))
        assertNull(RpsCodec.decode(byteArrayOf(9, 1, 2, 3)))
    }
}
