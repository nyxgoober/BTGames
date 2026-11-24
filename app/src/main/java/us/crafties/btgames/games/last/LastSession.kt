package us.crafties.btgames.games.last

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.Transport
import us.crafties.btgames.net.toMatch
import us.crafties.btgames.shell.GameSession
import java.util.Random

/** Everything the screen draws. Other players' hands are never in here, only how many cards they hold. */
data class LastUi(
    /** False until the deal has reached this phone (and, on the host, every other phone). */
    val ready: Boolean,
    val isHost: Boolean,
    /** The host is picking rules: show the rules panel. */
    val rulesOpen: Boolean,
    /** The rules as picked so far (host) or as dealt. */
    val rules: LastRules,
    /** I asked for another match and the host has not dealt yet. */
    val askedAgain: Boolean,
    val nicks: List<String>,
    val mySeat: Int,
    /** My hand in the engine's order. Cards are played by their index here. */
    val myHand: List<LastCard>,
    /** Parallel to [myHand]: can this card be played right now? */
    val playable: List<Boolean>,
    val counts: List<Int>,
    val top: LastCard?,
    val playPile: List<LastCard>,
    val playPileIds: List<Int>,
    val colour: LastColor,
    val turn: Int,
    /** +1 or -1. */
    val direction: Int,
    val drawLeft: Int,
    /** I just drew a card I may play; I can also pass. */
    val drewPlayable: Boolean,
    val winner: Int,
    val event: LastEvent?,
    /** Seats with one uncalled card, open to a catch right now. */
    val catchable: Set<Int>,
    /** I said Last! ahead of playing down to one card. */
    val lastArmed: Boolean,
    /** A slap is on and I have not slapped yet. */
    val canSlap: Boolean,
    /** I slapped and am waiting on the others. */
    val slapped: Boolean,
    /** Bumps on every accepted move so the screen can react (haptics) once per move. */
    val moveNo: Int,
    /** Which deal this is: the screen plays the shuffle-and-deal intro once per new value. */
    val match: Int,
    /** This phone is the table screen: it referees and shows the table but holds no seat. */
    val screen: Boolean,
    /** A slap round is open (for the table screen, which is not one of the slappers). */
    val slapOpen: Boolean,
    /** Doubling: the number the mover may still follow up with, or -1 when no double window is open. */
    val doubleNumber: Int = -1,
    /** A +2/+4 is waiting for the target to defend. */
    val defenseOpen: Boolean = false,
    /** The seat being attacked, or -1. */
    val defenseTarget: Int = -1,
    /** Colour a Ban/Spin must match to defend. */
    val defenseColor: LastColor? = null,
    /** Indices in my hand that can legally defend right now. */
    val defenseCards: List<Int> = emptyList(),
    /** Cards the target would draw if nobody defends. */
    val defenseAmount: Int = 0,
) {
    val myTurn: Boolean get() = ready && winner < 0 && turn == mySeat
    val over: Boolean get() = winner >= 0
    /** The round LAST! button: two cards, my turn, and one of them can be played. */
    val showLast: Boolean
        get() = ready && winner < 0 && myTurn && myHand.size == 2 && playable.any { it }
}

/**
 * Last! for a plain 2-player link and for N-player rooms (2-6).
 *
 * The host (seat 0) picks the rules and a random seed and repeats them until everyone has
 * answered READY; every phone then builds the same [LastEngine]. After that the host is the
 * referee: clients only send requests, the host checks them against its own engine, numbers each
 * accepted move and broadcasts it, and every phone (the sender too) applies moves in that order.
 * That is what lets a slap or a Last! catch land at one exact point for everybody.
 *
 * Taps that race each other (slap, Last!, "!") carry a timestamp in host time. Each client keeps
 * a [ClockSync] against the host, and the host's [LastReferee] compares the stamps rather than
 * trusting which packet happened to arrive first.
 */
class LastSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    hostGuess: Boolean,
    private val myNick: String,
    private val otherNick: String,
    private val twoPlayer: Boolean,
    /** True on the tablet / TV that hosts as the table screen: the Gamemaster, but not a player. */
    private val screenHost: Boolean = false,
) : GameSession {

    private var isHost = hostGuess
    private val random = Random()

    private var mySeat = -1
    private var totalPlayers = 0
    private var nicks: List<String> = emptyList()
    /**
     * Room roster in transport order (index 0 = the Gamemaster) and where I am in it. When the
     * Gamemaster is a table screen it holds no seat, so player seats start at transport index 1:
     * [seatOffset] is 1. Clients learn that from the SEED; the screen itself knows from the start.
     */
    private var roomNicks: List<String> = emptyList()
    private var roomIndex = -1
    private var seatOffset = if (screenHost) 1 else 0

    /** Work out seats, player count and nicknames from the roster and [seatOffset]. */
    private fun refreshSeats() {
        nicks = roomNicks.drop(seatOffset)
        totalPlayers = nicks.size
        mySeat = if (roomIndex >= seatOffset) roomIndex - seatOffset else -1
    }

    /** Ready answers the host still needs before it can start: every phone that plays (all clients). */
    private fun acksNeeded(): Int = if (screenHost) totalPlayers else totalPlayers - 1

    private var engine: LastEngine? = null
    private var match = 0
    private var ready = false
    private var seedMsg: LastCodec.Msg.Seed? = null
    private val acks = mutableSetOf<Int>()

    /** What the host has picked on the rules panel; becomes the engine's rules when it deals. */
    private var rulesDraft = LastRules()
    private var rulesOpen = hostGuess
    private var askedAgain = false
    private var slappedRound = -1

    // ---- clocks ----------------------------------------------------------------------------
    private val clockBase = System.nanoTime() / 1_000_000L
    private val clock = ClockSync()
    private val referee = LastReferee()
    /** Host only: when the open double (see [LastRules.doubling]) runs out of time. */
    private var doubleDeadline = Long.MAX_VALUE
    /** Host only: when an open defence window runs out. */
    private var defenseDeadline = Long.MAX_VALUE
    /** Moves from the host that arrived ahead of their turn, by move number. */
    private val pending = HashMap<Int, LastMove>()

    /** This phone's own clock in ms since the session began. */
    private fun localNow(): Long = System.nanoTime() / 1_000_000L - clockBase

    /** Now, in the host's time: the host's own clock, or mine corrected by the sync. */
    private fun stampNow(): Int =
        if (isHost) localNow().toInt()
        else if (clock.synced) clock.toHost(localNow()).toInt()
        else LastCodec.NO_TS

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<LastUi> = _ui.asStateFlow()

    private var roomJob: Job? = null
    private var controlJob: Job? = null
    private var seedJob: Job? = null
    private var pingJob: Job? = null
    private var tickJob: Job? = null

    private fun snapshot(): LastUi {
        val e = engine
        val me = mySeat
        val hand = if (e != null && me in 0 until e.players) e.hands[me].toList() else emptyList()
        return LastUi(
            ready = ready && e != null,
            isHost = isHost,
            rulesOpen = isHost && rulesOpen,
            rules = if (isHost && rulesOpen) rulesDraft else e?.rules ?: rulesDraft,
            askedAgain = askedAgain,
            nicks = nicks,
            mySeat = me,
            myHand = hand,
            playable = hand.mapIndexed { i, c ->
                e != null && e.playableAt(i, c)
            },
            counts = e?.hands?.map { it.size } ?: emptyList(),
            top = e?.topCard,
            playPile = e?.playPileSnapshot ?: emptyList(),
            playPileIds = e?.playPileIds ?: emptyList(),
            colour = e?.currentColor ?: LastColor.RED,
            turn = e?.turn ?: 0,
            direction = e?.direction ?: 1,
            drawLeft = e?.drawCount ?: 0,
            drewPlayable = e?.drewPlayable ?: false,
            winner = e?.winner ?: -1,
            event = e?.lastEvent,
            catchable = e?.catchable?.toSet() ?: emptySet(),
            lastArmed = e != null && me in 0 until e.players && e.armed(me),
            canSlap = ready && e != null && e.slapOpen && slappedRound != e.slapRound,
            slapped = ready && e != null && e.slapOpen && slappedRound == e.slapRound,
            moveNo = e?.moveNo ?: 0,
            match = match,
            screen = screenHost,
            slapOpen = ready && e != null && e.slapOpen,
            doubleNumber = if (ready && e != null) e.doubleNumber else -1,
            defenseOpen = ready && e != null && e.defenseOpen,
            defenseTarget = e?.defenseTarget ?: -1,
            defenseColor = e?.defenseColor,
            defenseCards = if (ready && e != null && me in 0 until e.players) e.defenseCards(me) else emptyList(),
            defenseAmount = e?.defenseAmount ?: 0,
        )
    }

    private fun publish() { _ui.value = snapshot() }

    private fun send(msg: LastCodec.Msg) {
        transport.sendControl(Control.Game(LastCodec.encode(msg)))
    }

    override fun start() {
        if (twoPlayer) {
            mySeat = if (isHost) 0 else 1
            totalPlayers = 2
            nicks = if (isHost) listOf(myNick, otherNick) else listOf(otherNick, myNick)
            publish()
        } else {
            roomJob = scope.launch {
                transport.room.collect { state ->
                    state.toMatch()?.let { m ->
                        isHost = m.isHost
                        roomIndex = m.myIndex
                        roomNicks = m.nicks
                        refreshSeats()
                        if (!isHost) rulesOpen = false
                        publish()
                    }
                }
            }
        }
        controlJob = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) LastCodec.decode(msg.payload)?.let { handle(it, senderSeat(msg)) }
            }
        }
        // Clients keep their clock lined up with the host: a quick burst to start, then now and then.
        pingJob = scope.launch {
            var sent = 0
            while (isActive) {
                if (!isHost && mySeat >= 0 && totalPlayers > 0) {
                    send(LastCodec.Msg.Ping(localNow().toInt()))
                    sent++
                }
                delay(if (sent < PING_BURST) PING_BURST_GAP_MS else PING_EVERY_MS)
            }
        }
        // The host's stopwatch for slaps and Last! catches.
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (isHost && ready) tick()
            }
        }
    }

    override fun stop() {
        roomJob?.cancel()
        controlJob?.cancel()
        seedJob?.cancel()
        pingJob?.cancel()
        tickJob?.cancel()
    }

    // ---- dealing ---------------------------------------------------------------------------

    /** Host only: deal a fresh match with the rules picked and keep announcing it until every other phone has it. */
    private fun dealNew() {
        if (!isHost || totalPlayers < LastEngine.MIN_PLAYERS) return
        match = (match + 1) and 0xFF
        val seed = random.nextInt()
        seedMsg = LastCodec.Msg.Seed(match, seed, totalPlayers, rulesDraft, screenHost = screenHost)
        engine = LastEngine(totalPlayers, seed.toLong(), rulesDraft)
        referee.clear()
        doubleDeadline = Long.MAX_VALUE
        defenseDeadline = Long.MAX_VALUE
        pending.clear()
        slappedRound = -1
        askedAgain = false
        rulesOpen = false
        acks.clear()
        ready = false
        seedJob?.cancel()
        seedJob = scope.launch {
            while (isActive && !ready) {
                seedMsg?.let { send(it) }
                delay(SEED_RESEND_MS)
            }
        }
        // Alone with nobody to wait for (cannot happen with 2+ players, but never leave it hanging).
        if (acksNeeded() <= 0) { ready = true; seedJob?.cancel() }
        publish()
    }

    /** The player seat of whoever sent [msg], or -1 when it came from nobody who plays (the table screen). */
    private fun senderSeat(msg: Control.Game): Int =
        if (msg.from == Control.Game.FROM_PEER) 1 - mySeat else if (msg.from < seatOffset) -1 else msg.from - seatOffset

    private fun handle(msg: LastCodec.Msg, sender: Int) {
        when (msg) {
            is LastCodec.Msg.Seed -> {
                if (isHost) return
                val offset = if (msg.screenHost) 1 else 0
                // In a 2-player link there is no roster: seats are already fixed and never offset.
                if (!twoPlayer && msg.players != roomNicks.size - offset) return
                if (twoPlayer && msg.players != totalPlayers) return
                if (engine == null || msg.match != match) {
                    if (!twoPlayer && offset != seatOffset) { seatOffset = offset; refreshSeats() }
                    match = msg.match
                    engine = LastEngine(msg.players, msg.seed.toLong(), msg.rules)
                    pending.clear()
                    slappedRound = -1
                    askedAgain = false
                    ready = true
                    publish()
                }
                send(LastCodec.Msg.Ready(msg.match))   // also for repeats: the host may have missed our last one
            }
            is LastCodec.Msg.Ready -> {
                if (!isHost || msg.match != match || sender < 0) return
                acks.add(sender)
                if (!ready && acks.size >= acksNeeded()) { ready = true; seedJob?.cancel(); publish() }
            }
            is LastCodec.Msg.Again -> {
                val e = engine
                if (isHost && msg.match == match && e != null && e.winner >= 0) { rulesOpen = true; publish() }
            }

            // ---- clock ----
            is LastCodec.Msg.Ping ->
                if (isHost && sender >= 0) send(LastCodec.Msg.Pong(sender, msg.t0, localNow().toInt()))
            is LastCodec.Msg.Pong ->
                if (!isHost && msg.seat == mySeat) clock.sample(msg.t0.toLong(), msg.host.toLong(), localNow())

            // ---- requests: only the host listens ----
            is LastCodec.Msg.Play -> request(msg.match, sender) { commit(LastMove.Play(sender, msg.hand, msg.colour)) }
            is LastCodec.Msg.Draw -> request(msg.match, sender) { commit(LastMove.Draw(sender)) }
            is LastCodec.Msg.Pass -> request(msg.match, sender) { commit(LastMove.Pass(sender)) }
            is LastCodec.Msg.Call -> request(msg.match, sender) { hostCall(sender, msg.ts) }
            is LastCodec.Msg.Catch -> request(msg.match, sender) { hostCatch(sender, msg.target, msg.ts) }
            is LastCodec.Msg.Slap -> request(msg.match, sender) { hostSlap(sender, msg.round, msg.ts) }
            is LastCodec.Msg.Swap -> request(msg.match, sender) { commit(LastMove.Exchange(sender, msg.hand, msg.target)) }
            is LastCodec.Msg.Defend -> request(msg.match, sender) { commit(LastMove.Defend(sender, msg.hand)) }

            // ---- the host's numbered moves: only clients listen ----
            is LastCodec.Msg.Move -> {
                val e = engine ?: return
                if (isHost || !ready || msg.match != match) return
                pending[msg.seq] = msg.move
                var changed = false
                while (true) {
                    val m = pending.remove(e.moveNo and 0xFF) ?: break
                    if (e.apply(m)) changed = true else break
                }
                if (pending.size > MAX_PENDING) pending.clear()
                if (changed) publish()
            }
        }
    }

    private inline fun request(msgMatch: Int, sender: Int, action: () -> Unit) {
        if (!isHost || !ready || msgMatch != match || sender !in 0 until totalPlayers) return
        action()
    }

    // ---- the host as referee ---------------------------------------------------------------

    /** Host only: apply one move to my engine, and if it was legal send it, numbered, to everyone. */
    private fun commit(move: LastMove): Boolean {
        val e = engine ?: return false
        if (!isHost || !ready) return false
        // A new 6 while the last slap is still running settles the old round first.
        // (A follow-up card inside a double does not count: it never starts or restarts a slap.)
        if (move is LastMove.Play && e.rules.slapSix && e.slapOpen && !e.doubleOpen) {
            val c = e.hands.getOrNull(move.seat)?.getOrNull(move.hand)
            if (c != null && c.kind == LastKind.NUMBER && c.number == LastEngine.SLAP_NUMBER) {
                referee.closeSlap()?.let { commit(LastMove.Slap(it.round, it.losers)) }
            }
        }
        val seq = e.moveNo and 0xFF
        if (!e.apply(move)) return false
        // Doubling: every card played in a double restarts the 2 second window.
        if (move is LastMove.Play) doubleDeadline = if (e.doubleOpen) localNow() + LastRules.DOUBLE_WINDOW_MS else Long.MAX_VALUE
        if (move is LastMove.EndDouble) doubleDeadline = Long.MAX_VALUE
        // A play or a stack can open the next response window; re-arm the timer whenever one is open.
        defenseDeadline = if (e.defenseOpen) localNow() + DEFENSE_WINDOW_MS else Long.MAX_VALUE
        send(LastCodec.Msg.Move(match, seq, move))
        syncReferee(e)
        publish()
        return true
    }

    /** Open a stopwatch for every window or slap the engine now has, and drop those it no longer has. */
    private fun syncReferee(e: LastEngine) {
        if (e.winner >= 0) { referee.clear(); return }
        val now = localNow()
        val open = referee.openCatches.map { it.target }.toSet()
        for (s in e.catchable) if (s !in open) referee.openCatch(s, now)
        for (t in open) if (t !in e.catchable) referee.drop(t)
        if (e.slapOpen && referee.openSlap?.round != e.slapRound) referee.openSlap(e.slapRound, e.players, now)
    }

    private fun stamp(ts: Int): Long = if (ts == LastCodec.NO_TS) localNow() else ts.toLong()

    private fun hostCall(seat: Int, ts: Int) {
        val e = engine ?: return
        val size = e.hands[seat].size
        when {
            size == 2 -> commit(LastMove.Call(seat))                                   // before: no race at all
            size == 1 && seat in e.catchable -> referee.call(seat, stamp(ts), localNow())   // after: the timestamps decide
        }
    }

    private fun hostCatch(catcher: Int, target: Int, ts: Int) {
        val e = engine ?: return
        if (target in e.catchable && catcher != target) referee.catchPress(target, catcher, stamp(ts), localNow())
    }

    private fun hostSlap(seat: Int, round: Int, ts: Int) {
        referee.slapPress(round, seat, stamp(ts))
    }

    /** Host only, every 100 ms: rule on whatever has run out of time. */
    private fun tick() {
        engine?.let { e ->
            if (e.doubleOpen && localNow() >= doubleDeadline) commit(LastMove.EndDouble(e.turn))
        if (e.defenseOpen && localNow() >= defenseDeadline) commit(LastMove.DefenseTimeout(e.defenseRound))
        }
        for (r in referee.due(localNow())) when (r) {
            is LastReferee.Ruling.Catch -> commit(
                when (val v = r.verdict) {
                    is LastReferee.CatchVerdict.Saved -> LastMove.Call(r.target)
                    is LastReferee.CatchVerdict.Caught -> LastMove.Catch(r.target, v.catcher)
                    is LastReferee.CatchVerdict.Safe -> LastMove.Catch(r.target, -1)
                },
            )
            is LastReferee.Ruling.Slap -> commit(LastMove.Slap(r.round, r.losers))
        }
    }

    // ---- what the screen can do ------------------------------------------------------------

    private fun canAct(): Boolean = _ui.value.myTurn

    /** Host picks the rules before dealing. */
    fun setRules(rules: LastRules) {
        if (!isHost) return
        rulesDraft = rules
        publish()
    }

    /** Host: deal with the rules on the panel. */
    fun startDeal() {
        if (isHost && rulesOpen) dealNew()
    }

    /** Play the card at [handIndex] of my hand. [colour] is needed for a wild. */
    fun play(handIndex: Int, colour: LastColor?) {
        if (!canAct()) return
        if (isHost) commit(LastMove.Play(mySeat, handIndex, colour))
        else send(LastCodec.Msg.Play(match, handIndex, colour))
    }

    fun draw() {
        if (!canAct()) return
        if (isHost) commit(LastMove.Draw(mySeat)) else send(LastCodec.Msg.Draw(match))
    }

    fun pass() {
        if (!canAct()) return
        if (isHost) commit(LastMove.Pass(mySeat)) else send(LastCodec.Msg.Pass(match))
    }

    /** Special 7: play the 7 at [handIndex] and swap hands with [target], one move. */
    fun exchange(handIndex: Int, target: Int) {
        if (!canAct()) return
        if (isHost) commit(LastMove.Exchange(mySeat, handIndex, target)) else send(LastCodec.Msg.Swap(match, handIndex, target))
    }

    /** The round LAST! button. */
    fun callLast() {
        val e = engine ?: return
        if (!ready || e.winner >= 0) return
        if (isHost) hostCall(mySeat, stampNow()) else send(LastCodec.Msg.Call(match, stampNow()))
    }

    /** The "!" on [target]'s cards: they have one card left and never called it. */
    fun catchPlayer(target: Int) {
        val e = engine ?: return
        if (!ready || e.winner >= 0 || target !in e.catchable) return
        if (isHost) hostCatch(mySeat, target, stampNow()) else send(LastCodec.Msg.Catch(match, target, stampNow()))
    }

    /** Answer a +2/+4 with a same-colour Ban (block) or Spin (reverse). */
    fun defend(handIndex: Int) {
        val e = engine ?: return
        if (!ready || !e.defenseOpen || mySeat != e.defenseTarget || handIndex !in e.defenseCards(mySeat)) return
        if (isHost) commit(LastMove.Defend(mySeat, handIndex))
        else send(LastCodec.Msg.Defend(match, handIndex))
    }

    /** Slap the pile after a 6. */
    fun slap() {
        val e = engine ?: return
        if (!ready || !e.slapOpen || slappedRound == e.slapRound) return
        slappedRound = e.slapRound
        if (isHost) hostSlap(mySeat, e.slapRound, stampNow()) else send(LastCodec.Msg.Slap(match, e.slapRound, stampNow()))
        publish()
    }

    /** Only after somebody has won. The host reopens the rules; anyone else asks the host. */
    fun playAgain() {
        val e = engine ?: return
        if (e.winner < 0) return
        if (isHost) { rulesOpen = true; publish() }
        else { askedAgain = true; send(LastCodec.Msg.Again(match)); publish() }
    }

    @androidx.compose.runtime.Composable
    override fun Content(onLeave: () -> Unit) {
        if (screenHost) LastTableScreen(session = this, onLeave = onLeave)
        else LastScreen(session = this, onLeave = onLeave)
    }

    private companion object {
        const val SEED_RESEND_MS = 700L
        const val TICK_MS = 100L
        const val PING_BURST = 6
        const val PING_BURST_GAP_MS = 250L
        const val PING_EVERY_MS = 8000L
        const val MAX_PENDING = 16
        const val DEFENSE_WINDOW_MS = 5000L
    }
}
