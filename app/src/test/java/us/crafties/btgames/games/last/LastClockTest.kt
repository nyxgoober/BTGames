package us.crafties.btgames.games.last

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastClockTest {

    @Test fun offsetIsTheHostReadingAtTheMiddleOfTheRoundTrip() {
        val c = ClockSync()
        assertFalse(c.synced)
        // my clock reads 1000 when I ping, 1100 when the answer lands; the host said 5050 -> host = mine + 4000
        c.sample(1000, 5050, 1100)
        assertTrue(c.synced)
        assertEquals(4000L, c.offset)
        assertEquals(6500L, c.toHost(2500))
    }

    @Test fun theShortestRoundTripWins() {
        val c = ClockSync()
        c.sample(0, 5400, 800)      // slow, lumpy: offset 5000
        c.sample(1000, 5030, 1040)  // fast: host 5030 at my 1020 -> offset 4010
        c.sample(2000, 6700, 2600)  // slow again
        assertEquals(4010L, c.offset)
        assertEquals(40L, c.rtt)
    }

    @Test fun oldSamplesAgeOut() {
        val c = ClockSync()
        c.sample(0, 100, 10)        // rtt 10, offset 95
        repeat(ClockSync.WINDOW) { c.sample(1000L * (it + 1), 1000L * (it + 1) + 500 + 15, 1000L * (it + 1) + 30) }
        // the first sample has been pushed out; the rest have rtt 30 and offset 500
        assertEquals(500L, c.offset)
    }

    @Test fun backwardsClocksAreIgnored() {
        val c = ClockSync()
        c.sample(100, 100, 50)
        assertFalse(c.synced)
    }
}

class LastRefereeTest {

    private fun ref() = LastReferee()

    @Test fun nobodyCatchingMeansTheyGetAway() {
        val r = ref()
        r.openCatch(2, 10_000)
        assertTrue(r.due(10_000 + LastReferee.CATCH_WINDOW_MS).isEmpty())          // still inside the grace
        val out = r.due(10_000 + LastReferee.CATCH_WINDOW_MS + LastReferee.GRACE_MS)
        assertEquals(listOf<LastReferee.Ruling>(LastReferee.Ruling.Catch(2, LastReferee.CatchVerdict.Safe)), out)
        assertTrue(r.due(99_999).isEmpty())                                         // ruled once
    }

    @Test fun aCatchInTimeCatches() {
        val r = ref()
        r.openCatch(2, 10_000)
        r.catchPress(2, 1, 11_000, now = 11_050)
        assertTrue(r.due(11_100).isEmpty())
        val out = r.due(11_050 + LastReferee.GRACE_MS)
        assertEquals(listOf<LastReferee.Ruling>(LastReferee.Ruling.Catch(2, LastReferee.CatchVerdict.Caught(1))), out)
    }

    @Test fun aLateCatchIsTooLate() {
        val r = ref()
        r.openCatch(2, 10_000)
        r.catchPress(2, 1, 10_000 + LastReferee.CATCH_WINDOW_MS + 1, now = 13_100)
        val out = r.due(14_000)
        assertEquals(LastReferee.CatchVerdict.Safe, (out.single() as LastReferee.Ruling.Catch).verdict)
    }

    @Test fun theEarlierStampWinsWhateverArrivedFirst() {
        // the player's call is stamped before the catch, but arrives after it over a slow link
        val r = ref()
        r.openCatch(2, 10_000)
        r.catchPress(2, 1, 11_200, now = 11_210)
        r.call(2, 11_100, now = 11_400)
        val out = r.due(11_700)
        assertEquals(LastReferee.CatchVerdict.Saved, (out.single() as LastReferee.Ruling.Catch).verdict)
    }

    @Test fun aCatchStampedBeforeTheCallStillCatches() {
        val r = ref()
        r.openCatch(2, 10_000)
        r.call(2, 11_300, now = 11_310)
        r.catchPress(2, 1, 11_250, now = 11_500)    // made first, arrived second
        val out = r.due(11_800)
        assertEquals(LastReferee.CatchVerdict.Caught(1), (out.single() as LastReferee.Ruling.Catch).verdict)
    }

    @Test fun theEarliestOfSeveralCatchersGetsTheCredit() {
        val r = ref()
        r.openCatch(0, 0)
        r.catchPress(0, 3, 1_500, now = 1_510)
        r.catchPress(0, 2, 1_400, now = 1_600)
        val v = (r.due(2_000).single() as LastReferee.Ruling.Catch).verdict
        assertEquals(LastReferee.CatchVerdict.Caught(2), v)
    }

    @Test fun youCannotCatchYourself() {
        val r = ref()
        r.openCatch(1, 0)
        r.catchPress(1, 1, 500, now = 500)
        val v = (r.due(5_000).single() as LastReferee.Ruling.Catch).verdict
        assertEquals(LastReferee.CatchVerdict.Safe, v)
    }

    @Test fun windowsAreIndependent() {
        val r = ref()
        r.openCatch(1, 0)
        r.openCatch(2, 1_000)
        r.catchPress(2, 0, 1_500, now = 1_500)
        val first = r.due(1_900)
        assertEquals(listOf<LastReferee.Ruling>(LastReferee.Ruling.Catch(2, LastReferee.CatchVerdict.Caught(0))), first)
        assertEquals(1, r.openCatches.size)
    }

    @Test fun droppedWindowsAreForgotten() {
        val r = ref()
        r.openCatch(1, 0)
        r.drop(1)
        assertTrue(r.due(100_000).isEmpty())
    }

    @Test fun theLastToSlapLoses() {
        val r = ref()
        r.openSlap(1, 3, 0)
        r.slapPress(1, 0, 300)
        r.slapPress(1, 2, 250)
        assertTrue(r.due(400).isEmpty())                // seat 1 still out
        r.slapPress(1, 1, 900)
        assertEquals(listOf<LastReferee.Ruling>(LastReferee.Ruling.Slap(1, 1 shl 1)), r.due(901))
    }

    @Test fun aSlapTieGoesAgainstTheHigherSeat() {
        val r = ref()
        r.openSlap(1, 2, 0)
        r.slapPress(1, 0, 500)
        r.slapPress(1, 1, 500)
        assertEquals(1 shl 1, (r.due(500).single() as LastReferee.Ruling.Slap).losers)
    }

    @Test fun slapStampsNotArrivalDecideWhoWasLast() {
        // seat 1 slapped first but the packet was slow; seat 0 slapped later but arrived first
        val r = ref()
        r.openSlap(1, 2, 0)
        r.slapPress(1, 0, 900)
        r.slapPress(1, 1, 400)
        assertEquals(1 shl 0, (r.due(1_000).single() as LastReferee.Ruling.Slap).losers)
    }

    @Test fun everyoneWhoNeverSlapsDrawsWhenTheRoundTimesOut() {
        val r = ref()
        r.openSlap(1, 4, 1_000)
        r.slapPress(1, 2, 1_300)
        assertTrue(r.due(1_000 + LastReferee.SLAP_TIMEOUT_MS).isEmpty())
        val out = r.due(1_000 + LastReferee.SLAP_TIMEOUT_MS + LastReferee.GRACE_MS)
        assertEquals(0b1011, (out.single() as LastReferee.Ruling.Slap).losers)
    }

    @Test fun aSlapOnTheWrongRoundIsIgnored() {
        val r = ref()
        r.openSlap(2, 2, 0)
        r.slapPress(1, 0, 100)
        r.slapPress(2, 1, 200)
        assertEquals(1 shl 0, (r.due(10_000).single() as LastReferee.Ruling.Slap).losers)
    }

    @Test fun aNewSixCanSettleTheOldRoundFirst() {
        val r = ref()
        r.openSlap(1, 2, 0)
        r.slapPress(1, 0, 100)
        assertEquals(LastReferee.Ruling.Slap(1, 1 shl 1), r.closeSlap())
        assertTrue(r.openSlap == null)
    }
}
