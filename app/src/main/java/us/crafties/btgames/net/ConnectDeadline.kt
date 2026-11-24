package us.crafties.btgames.net

/**
 * The 10 second limit on the part of a connection attempt that involves no human.
 *
 * It exists for one failure: both people tap each other at the same instant. Each phone then sees
 * the other's request, but the two "I saw yours" confirmations can cross badly and neither side ever
 * proceeds. Nothing else was watching that window, so a phone could sit on "Asking..." forever.
 *
 * What it must NOT do is cut off a person who is simply slow to press Accept. So the clock only
 * covers machine work:
 *
 *   tap  ->  [connect, discover, subscribe, deliver HELLO]  ->  HELLO acknowledged
 *                                                                 |
 *                       nobody asked us back --------------------+--> clock stops, a person owns the wait
 *                       they asked us too (mutual) ---------------+--> clock keeps running until the
 *                                                                      handshake proves both directions
 *
 * This class holds only that decision. It knows nothing about Bluetooth: the transport reports what
 * happened and supplies a [Timer], which a test can drive by hand.
 */
class ConnectDeadline(
    private val timer: Timer,
    private val limitMs: Long = LIMIT_MS,
    private val onExpired: () -> Unit,
) {
    /** Schedules one callback. Real code wraps a Handler; tests use a fake clock. */
    interface Timer {
        fun postDelayed(delayMs: Long, block: () -> Unit)
        fun cancel()
    }

    private var running = false
    private var peerAsked = false

    /** The person tapped a name. The machine phase starts now. */
    fun onTapped() {
        peerAsked = false
        start()
    }

    /** Our request reached the other phone's stack. From here a human decides, unless they asked us too. */
    fun onRequestDelivered() {
        if (!peerAsked) stop()
    }

    /**
     * The other phone's request reached us while we are also asking it: a mutual attempt. The clock
     * is the one begun at the tap and keeps counting from there; it is only restarted if delivery
     * had already stopped it, so a mutual handshake is never left without a limit.
     */
    fun onPeerAskedUsToo() {
        peerAsked = true
        if (!running) start()
    }

    /** Both directions are proven, or a person now owns the decision. Nothing left to time. */
    fun onResolved() = stop()

    /**
     * The attempt was torn down. Per-attempt memory ([peerAsked]) is cleared by the NEXT [onTapped],
     * which every attempt begins with, so there is nothing else to reset here.
     */
    fun reset() = stop()

    val isRunning: Boolean get() = running

    private fun start() {
        timer.cancel()
        running = true
        timer.postDelayed(limitMs) {
            running = false
            onExpired()
        }
    }

    private fun stop() {
        running = false
        timer.cancel()
    }

    companion object {
        const val LIMIT_MS = 10_000L
        const val MESSAGE = "Couldn't connect. Try again, and make sure you don't both tap each other at the same moment."
    }
}
