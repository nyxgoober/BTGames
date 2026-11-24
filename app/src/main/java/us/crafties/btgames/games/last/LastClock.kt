package us.crafties.btgames.games.last

/**
 * Keeps one phone's clock lined up with the host's, so a tap carries a time the host can compare
 * with everybody else's. Pure: all times are plain milliseconds handed in by the caller.
 *
 * A client sends PING(t0) and the host answers with its own clock reading; with the time the
 * answer arrived (t1) the host reading is taken to be from the middle of the round trip, which
 * gives an offset. Bluetooth latency is lumpy, so the sample with the shortest round trip among
 * the most recent few wins: it has the least room for error.
 */
class ClockSync {
    private class Sample(val rtt: Long, val offset: Long)

    private val samples = ArrayDeque<Sample>()

    val synced: Boolean get() = samples.isNotEmpty()

    /** Host time minus this phone's time, in ms. */
    var offset: Long = 0
        private set

    /** Round trip of the sample currently trusted, or -1 before any. */
    val rtt: Long get() = samples.minByOrNull { it.rtt }?.rtt ?: -1

    /** [t0] = my clock when I sent the ping, [host] = the host's clock in its answer, [t1] = my clock on arrival. */
    fun sample(t0: Long, host: Long, t1: Long) {
        val rtt = t1 - t0
        if (rtt < 0) return
        samples.addLast(Sample(rtt, host - (t0 + t1) / 2))
        while (samples.size > WINDOW) samples.removeFirst()
        offset = samples.minBy { it.rtt }.offset
    }

    /** My clock reading expressed in host time. */
    fun toHost(local: Long): Long = local + offset

    companion object { const val WINDOW = 8 }
}

/**
 * The host's stopwatch for the two timed moments in Last!: the 3 seconds in which anybody may
 * catch a player who forgot to call Last!, and the slap after a 6. Pure and driven by the
 * caller, who passes in the host's clock and asks what is [due].
 *
 * Every tap arrives stamped with the time it happened in host time. Bluetooth delays them by
 * different amounts, so the host judges by the stamps, not by arrival order, and waits a short
 * [GRACE_MS] after the deadline for taps that were made in time but are still on the way.
 */
class LastReferee {

    /** Verdict for a Last! window. */
    sealed interface CatchVerdict {
        /** The player called Last! in time. */
        object Saved : CatchVerdict
        /** [catcher] pressed "!" first and in time. */
        data class Caught(val catcher: Int) : CatchVerdict
        /** Nobody caught them, nobody called: they got away with it. */
        object Safe : CatchVerdict
    }

    class CatchCase internal constructor(val target: Int, val openedAt: Long) {
        internal var callTs: Long? = null
        internal var catchTs: Long? = null
        internal var catcher: Int = -1
        internal var firstArrival: Long? = null

        val deadline: Long get() = openedAt + CATCH_WINDOW_MS

        internal fun touch(arrival: Long) { if (firstArrival == null) firstArrival = arrival }

        internal fun dueAt(): Long {
            val end = deadline + GRACE_MS
            val early = firstArrival?.plus(GRACE_MS) ?: return end
            return minOf(early, end)
        }

        internal fun verdict(): CatchVerdict {
            val call = callTs
            val catch = catchTs
            val calledInTime = call != null && call <= deadline
            val caughtInTime = catch != null && catch <= deadline
            return when {
                calledInTime && (!caughtInTime || call!! <= catch!!) -> CatchVerdict.Saved
                caughtInTime -> CatchVerdict.Caught(catcher)
                else -> CatchVerdict.Safe
            }
        }
    }

    class SlapCase internal constructor(val round: Int, val openedAt: Long, val players: Int) {
        internal val slaps = HashMap<Int, Long>()
        internal fun dueAt(): Long = openedAt + SLAP_TIMEOUT_MS + GRACE_MS
        internal fun complete(): Boolean = slaps.size >= players

        /** Seats that never slapped; if everybody did, only the one whose slap came last (a tie goes to the higher seat). */
        internal fun losers(): Int {
            var mask = 0
            for (s in 0 until players) if (s !in slaps) mask = mask or (1 shl s)
            if (mask != 0) return mask
            val last = slaps.entries.maxWithOrNull(compareBy({ it.value }, { it.key }))
            return if (last == null) 0 else 1 shl last.key
        }
    }

    sealed interface Ruling {
        data class Catch(val target: Int, val verdict: CatchVerdict) : Ruling
        data class Slap(val round: Int, val losers: Int) : Ruling
    }

    private val catches = HashMap<Int, CatchCase>()
    private var slap: SlapCase? = null

    val openCatches: Collection<CatchCase> get() = catches.values
    val openSlap: SlapCase? get() = slap

    fun openCatch(target: Int, now: Long) { catches[target] = CatchCase(target, now) }

    /** [target] pressed Last! at host time [ts]; [now] is when the host heard of it. */
    fun call(target: Int, ts: Long, now: Long) {
        val c = catches[target] ?: return
        if (c.callTs == null || ts < c.callTs!!) c.callTs = ts
        c.touch(now)
    }

    /** [catcher] pressed "!" on [target] at host time [ts]. Earliest valid press wins. */
    fun catchPress(target: Int, catcher: Int, ts: Long, now: Long) {
        val c = catches[target] ?: return
        if (catcher == target) return
        if (c.catchTs == null || ts < c.catchTs!! || (ts == c.catchTs && catcher < c.catcher)) {
            c.catchTs = ts
            c.catcher = catcher
        }
        c.touch(now)
    }

    /** Start slap [round]. An older round still running is settled by [closeSlap] first. */
    fun openSlap(round: Int, players: Int, now: Long) { slap = SlapCase(round, now, players) }

    fun slapPress(round: Int, seat: Int, ts: Long) {
        val s = slap ?: return
        if (s.round != round || seat !in 0 until s.players) return
        if (!s.slaps.containsKey(seat)) s.slaps[seat] = ts
    }

    /** Settle the running slap right now (a new 6 came before the old round timed out). */
    fun closeSlap(): Ruling.Slap? {
        val s = slap ?: return null
        slap = null
        return Ruling.Slap(s.round, s.losers())
    }

    /** Forget a window whose player went out or took cards; nothing left to rule on. */
    fun drop(target: Int) { catches.remove(target) }

    fun clear() { catches.clear(); slap = null }

    /** Everything that is ready to be ruled on at host time [now]; the rulings are removed. */
    fun due(now: Long): List<Ruling> {
        val out = ArrayList<Ruling>()
        val iter = catches.values.iterator()
        while (iter.hasNext()) {
            val c = iter.next()
            if (now >= c.dueAt()) { out.add(Ruling.Catch(c.target, c.verdict())); iter.remove() }
        }
        val s = slap
        if (s != null && (s.complete() || now >= s.dueAt())) {
            slap = null
            out.add(Ruling.Slap(s.round, s.losers()))
        }
        return out
    }

    companion object {
        const val CATCH_WINDOW_MS = 3000L
        const val SLAP_TIMEOUT_MS = 4000L
        /** How long after a deadline the host still waits for taps made in time but delayed in the air. */
        const val GRACE_MS = 400L
    }
}
