package us.crafties.btgames.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectDeadlineTest {

    /** A clock the test advances by hand. Only one callback can be pending, like the real Handler wrapper. */
    private class FakeTimer : ConnectDeadline.Timer {
        var now = 0L
        private var dueAt = -1L
        private var block: (() -> Unit)? = null
        override fun postDelayed(delayMs: Long, block: () -> Unit) { dueAt = now + delayMs; this.block = block }
        override fun cancel() { dueAt = -1L; block = null }
        val armed: Boolean get() = block != null
        fun advance(ms: Long) {
            now += ms
            if (dueAt in 0..now) { val b = block; dueAt = -1L; block = null; b?.invoke() }
        }
    }

    private val timer = FakeTimer()
    private var expiries = 0
    private val deadline = ConnectDeadline(timer) { expiries++ }

    @Test
    fun theLimitIsTenSeconds() {
        assertEquals(10_000L, ConnectDeadline.LIMIT_MS)
    }

    @Test
    fun theErrorTextIsWhatTheUserSees() {
        assertEquals(
            "Couldn't connect. Try again, and make sure you don't both tap each other at the same moment.",
            ConnectDeadline.MESSAGE,
        )
    }

    @Test
    fun aRequestThatNeverGetsThroughExpiresAtTenSeconds() {
        deadline.onTapped()
        timer.advance(9_999)
        assertEquals(0, expiries)
        timer.advance(1)
        assertEquals(1, expiries)
        assertFalse(deadline.isRunning)
    }

    @Test
    fun aSlowHumanIsNeverCutOff() {
        // Our request was delivered and nobody asked us back: the other person is reading a dialog.
        deadline.onTapped()
        timer.advance(400)
        deadline.onRequestDelivered()
        assertFalse("clock must stop once a person owns the wait", deadline.isRunning)
        timer.advance(10 * 60_000L) // ten minutes
        assertEquals(0, expiries)
    }

    @Test
    fun aMutualRequestKeepsItsClockAfterDelivery() {
        // Their request reached us BEFORE our own was acknowledged. Delivery must not stop the clock,
        // because this is a handshake between machines, not a person deciding.
        deadline.onTapped()
        timer.advance(100)
        deadline.onPeerAskedUsToo()
        timer.advance(300)
        deadline.onRequestDelivered()
        assertTrue("mutual handshake must stay under the limit", deadline.isRunning)
        timer.advance(9_599)
        assertEquals(0, expiries)
        timer.advance(1)
        assertEquals(1, expiries)
    }

    @Test
    fun theClockKeepsCountingFromTheTapWhenTheyAskUsToo() {
        // Their request arriving must not restart the 10 seconds, or two phones that each wait a bit
        // could stretch the limit well past ten.
        deadline.onTapped()
        timer.advance(6_000)
        deadline.onPeerAskedUsToo()
        timer.advance(3_999)
        assertEquals(0, expiries)
        timer.advance(1)
        assertEquals(1, expiries)
    }

    @Test
    fun ifDeliveryAlreadyStoppedTheClockAMutualRequestRestartsIt() {
        // Our request was acknowledged first (clock stopped), then theirs arrives: it turned mutual.
        // From here there is no person to wait for, so a fresh limit must apply.
        deadline.onTapped()
        deadline.onRequestDelivered()
        assertFalse(deadline.isRunning)
        timer.advance(2_000)
        deadline.onPeerAskedUsToo()
        assertTrue(deadline.isRunning)
        timer.advance(9_999)
        assertEquals(0, expiries)
        timer.advance(1)
        assertEquals(1, expiries)
    }

    @Test
    fun aMutualHandshakeThatResolvesInTimeNeverErrors() {
        deadline.onTapped()
        deadline.onPeerAskedUsToo()
        timer.advance(1_500)
        deadline.onResolved()
        timer.advance(60_000)
        assertEquals(0, expiries)
        assertFalse(timer.armed)
    }

    @Test
    fun resetCancelsAnythingPending() {
        deadline.onTapped()
        deadline.reset()
        timer.advance(60_000)
        assertEquals(0, expiries)
        assertFalse(deadline.isRunning)
    }

    @Test
    fun aFreshAttemptAfterAMutualOneIsTreatedAsOrdinary() {
        // Attempt 1 turned mutual, then was torn down. Attempt 2 is an ordinary request. If the old
        // "they asked us too" memory survived the tap, delivery would keep the clock running and cut
        // off a slow human. Starting an attempt must always begin from a clean slate.
        deadline.onTapped()
        deadline.onPeerAskedUsToo()
        deadline.reset()
        deadline.onTapped()
        deadline.onRequestDelivered()
        assertFalse("stale mutual memory leaked into a fresh attempt", deadline.isRunning)
        timer.advance(60_000)
        assertEquals(0, expiries)
    }

    @Test
    fun aSecondTapRestartsTheClockRatherThanStackingTwo() {
        deadline.onTapped()
        timer.advance(8_000)
        deadline.onTapped()
        timer.advance(9_999)
        assertEquals(0, expiries)
        timer.advance(1)
        assertEquals("exactly one expiry, not two", 1, expiries)
    }

    @Test
    fun expiryFiresOnlyOnce() {
        deadline.onTapped()
        timer.advance(10_000)
        timer.advance(60_000)
        assertEquals(1, expiries)
    }
}
