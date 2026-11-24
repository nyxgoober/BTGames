package us.crafties.btgames.games.cupshuffle

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameSession
import kotlin.random.Random

enum class CupPhase { SETUP, SHOW, SHUFFLING, GUESSING, REVEAL }

/** Everything the Cup Shuffle screen draws. A fresh copy is published on every tap and tick. */
data class CupUi(
    val phase: CupPhase,
    val round: Int,
    /** This phone hides and shuffles when true, guesses when false. RED shuffles even rounds. */
    val iShuffle: Boolean,
    /** Cups in play this round (0 until the shuffler hides the ball). */
    val cups: Int,
    /** The shuffler's cup-count picker. */
    val cupsChoice: Int,
    /** Cup id per display slot, left to right. Empty until the round starts. */
    val order: List<Int>,
    /**
     * Slot holding the ball, or -1 when hidden. The shuffler (x-ray vision) always knows it;
     * the guesser sees it during the show and at the reveal — never while tracking.
     */
    val ballSlot: Int,
    /** Swaps so far (sent, or applied). */
    val swaps: Int,
    /** Total swaps, once the shuffler calls time (the guesser). */
    val totalSwaps: Int,
    /** Milliseconds left in the 5-second shuffle window (the shuffler). */
    val windowLeftMs: Long,
    /** The guesser's pick, or -1. */
    val myGuess: Int,
    /** True in REVEAL when the guess found the ball. */
    val guessCorrect: Boolean?,
    val myNick: String,
    val theirNick: String,
    val mySide: Side,
    val iWantRematch: Boolean,
    val theyWantRematch: Boolean,
    val myWins: Int,
    val theirWins: Int,
)

/**
 * Cup Shuffle (the shell game). One phone shuffles, the other guesses; roles swap every round.
 *
 * A round: the shuffler picks the cup count (3..6, minimum 3) and hides the ball under a
 * cup. Both phones then SEE the ball for a moment (so the guesser can play skill, not luck),
 * then the cups cover it and the shuffler taps as fast as they can for 5 seconds — one tap
 * is one shuffle (a swap of neighbouring cups), at most one tap per 250 ms, every swap
 * animated so the guesser can track it with their eye. The shuffler keeps x-ray vision
 * throughout (the ball's cup stays outlined); the guesser sees identical cups. Then the
 * guesser picks a cup and both phones reveal: a right guess scores the guesser, a wrong one
 * the shuffler.
 *
 * Rules of the road:
 *  - RED shuffles even rounds, BLUE odd ones (random per match, like Keymash's chooser).
 *  - Every SHUFFLE carries the whole order, so a lost tap heals on the next one; only newer
 *    sequence numbers apply. CHOICE and END are re-sent until the round moves on.
 *  - RESULT is authoritative for where the ball is; GUESS is re-sent until RESULT arrives.
 *  - The next round starts only when BOTH players ask for it.
 */
class CupShuffleSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val rnd: Random = Random.Default,
) : GameSession {

    private var round = 0
    private var phase = CupPhase.SETUP
    private var cups = 0
    private var cupsChoice = CupShuffleLogic.MIN_CUPS
    private var order: List<Int> = emptyList()
    private var ballCup = -1
    private var seq = 0
    private var appliedSeq = 0
    private var totalSwaps = 0
    private var showEndsAt = 0L
    private var shuffleEndsAt = 0L
    private var lastTapAt = -10_000L
    private var lastSentAt = 0L
    private var myGuess = -1
    private var guessCorrect: Boolean? = null
    private var iWantRematch = false
    private var theyWantRematch = false
    private var myWins = 0
    private var theirWins = 0

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<CupUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var loop: Job? = null

    private fun iShuffle(): Boolean = (round % 2 == 0) == (mySide == Side.RED)

    /** The display slot holding the ball, or -1 while it is hidden from this phone. */
    private fun ballSlot(): Int {
        if (ballCup < 0 || order.isEmpty()) return -1
        if (iShuffle()) return order.indexOf(ballCup)
        // The guesser sees it during the show and at the reveal — never while tracking.
        return if (phase == CupPhase.SHOW || phase == CupPhase.REVEAL) order.indexOf(ballCup) else -1
    }

    override fun start() {
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) CupShuffleCodec.decode(msg.payload)?.let(::onMessage)
            }
        }
        loop = scope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    override fun stop() {
        listeners?.cancel(); loop?.cancel()
    }

    // ---- local input --------------------------------------------------------------------

    /** The shuffler's cup-count stepper. */
    fun setCups(n: Int) {
        if (phase != CupPhase.SETUP || !iShuffle()) return
        if (n !in CupShuffleLogic.MIN_CUPS..CupShuffleLogic.MAX_CUPS) return
        cupsChoice = n
        publish()
    }

    /**
     * Every cup tap comes through here and means whatever the phase asks: hide the ball
     * (shuffler in SETUP), shuffle (shuffler in SHUFFLING), or guess (guesser in GUESSING).
     */
    fun tapSlot(slot: Int) {
        if (iShuffle()) {
            if (phase == CupPhase.SETUP) hideBall(slot) else tapShuffle()
        } else {
            guess(slot)
        }
    }

    fun requestRematch() {
        if (phase != CupPhase.REVEAL || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(CupShuffleCodec.encodeRematch(round + 1)))
        maybeStartRematch()
        publish()
    }

    private fun hideBall(slot: Int) {
        if (phase != CupPhase.SETUP || !iShuffle()) return
        if (slot !in 0 until cupsChoice) return
        cups = cupsChoice
        order = CupShuffleLogic.initialOrder(cups)
        ballCup = slot // order is initial, so the ball starts in the tapped slot
        seq = 0
        appliedSeq = 0
        totalSwaps = 0
        showEndsAt = clock() + SHOW_MS
        shuffleEndsAt = showEndsAt + SHUFFLE_WINDOW_MS
        lastTapAt = -10_000L
        phase = CupPhase.SHOW
        sendChoice()
        publish()
    }

    private fun tapShuffle() {
        if (phase != CupPhase.SHUFFLING || !iShuffle()) return
        val now = clock()
        if (now >= shuffleEndsAt) return
        if (now - lastTapAt < TAP_COOLDOWN_MS) return // one tap per 250 ms: followable
        lastTapAt = now
        val (a, b) = CupShuffleLogic.randomAdjacentPair(rnd, cups)
        order = CupShuffleLogic.swapped(order, a, b)
        seq++
        sendShuffle()
        publish()
    }

    private fun guess(slot: Int) {
        if (phase != CupPhase.GUESSING || iShuffle() || myGuess >= 0) return
        if (slot !in 0 until cups) return
        myGuess = slot
        sendGuess()
        publish()
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: CupShuffleCodec.Msg) {
        when (m) {
            is CupShuffleCodec.Msg.Choice -> {
                if (m.round != round || iShuffle()) return
                if (phase != CupPhase.SETUP) return // first choice wins
                cups = m.cups
                order = CupShuffleLogic.initialOrder(cups)
                ballCup = m.ball // shown openly until the show ends, then hidden by ballSlot()
                appliedSeq = 0
                totalSwaps = 0
                myGuess = -1
                showEndsAt = clock() + SHOW_MS
                phase = CupPhase.SHOW
                publish()
            }
            is CupShuffleCodec.Msg.Shuffle -> {
                if (m.round != round || iShuffle()) return
                if (m.cups != cups || phase != CupPhase.SHUFFLING) return
                if (m.seq <= appliedSeq) return // duplicate or resend: harmless
                order = m.order
                appliedSeq = m.seq
                publish()
            }
            is CupShuffleCodec.Msg.End -> {
                if (m.round != round || iShuffle()) return
                if (phase == CupPhase.REVEAL) return
                if (phase == CupPhase.SETUP) {
                    // Missed the choice and the show: still play the guessing half.
                    cups = m.cups
                    order = CupShuffleLogic.initialOrder(cups)
                    ballCup = -1
                    appliedSeq = 0
                    myGuess = -1
                }
                if (phase == CupPhase.SETUP || phase == CupPhase.SHOW || phase == CupPhase.SHUFFLING) {
                    totalSwaps = m.total
                    phase = CupPhase.GUESSING
                    publish()
                }
            }
            is CupShuffleCodec.Msg.Guess -> {
                if (m.round != round || !iShuffle()) return
                if (m.pos >= cups) return
                if (phase == CupPhase.REVEAL) {
                    sendResult(m.pos) // they missed the result: heal it
                    return
                }
                if (phase != CupPhase.GUESSING) return
                reveal(guessedSlot = m.pos)
            }
            is CupShuffleCodec.Msg.Result -> {
                if (m.round != round || iShuffle()) return
                if (phase != CupPhase.GUESSING && phase != CupPhase.REVEAL) return
                if (m.ball >= cups || m.guess >= cups) return
                if (phase == CupPhase.REVEAL) return
                myGuess = m.guess
                // The result is authoritative: place the ball where the shuffler says it is.
                ballCup = order.getOrElse(m.ball) { -1 }
                reveal(guessedSlot = m.guess)
            }
            is CupShuffleCodec.Msg.Rematch -> {
                if (m.round == round + 1 && phase == CupPhase.REVEAL) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    /** Score the round from the guessed slot and enter the reveal. */
    private fun reveal(guessedSlot: Int) {
        val guesserWon = guessedSlot == order.indexOf(ballCup)
        guessCorrect = guesserWon
        // From my seat: I win if I guessed right, or if I shuffled and they guessed wrong.
        val iWin = if (iShuffle()) !guesserWon else guesserWon
        if (iWin) myWins++ else theirWins++
        phase = CupPhase.REVEAL
        if (iShuffle()) sendResult(guessedSlot)
        publish()
    }

    // ---- loop -------------------------------------------------------------------------------

    private fun tick() {
        val now = clock()
        if (iShuffle()) {
            when (phase) {
                CupPhase.SHOW -> {
                    // The show must land too: re-send until the shuffling proves it did.
                    if (now - lastSentAt > RESEND_MS) {
                        lastSentAt = now
                        sendChoice()
                    }
                    if (now >= showEndsAt) {
                        phase = CupPhase.SHUFFLING
                        publish()
                    }
                }
                CupPhase.SHUFFLING -> {
                    // Re-send the choice until the round is visibly moving (a swap proved it
                    // landed) so a guesser that missed it still joins.
                    if (now - lastSentAt > RESEND_MS) {
                        lastSentAt = now
                        if (seq == 0) sendChoice() else sendShuffle()
                    }
                    if (now >= shuffleEndsAt) {
                        totalSwaps = seq
                        phase = CupPhase.GUESSING
                        sendEnd()
                        publish()
                    }
                }
                CupPhase.GUESSING -> {
                    if (now - lastSentAt > RESEND_MS) {
                        lastSentAt = now
                        sendEnd() // their guess is the ack; until then keep calling time
                    }
                    publish()
                }
                CupPhase.REVEAL -> {
                    if (iWantRematch && now - lastSentAt > RESEND_MS) {
                        lastSentAt = now
                        transport.sendControl(Control.Game(CupShuffleCodec.encodeRematch(round + 1)))
                    }
                    publish()
                }
                CupPhase.SETUP -> Unit
            }
        } else {
            if (phase == CupPhase.SHOW && now >= showEndsAt) {
                phase = CupPhase.SHUFFLING // the show is timed locally; the swaps do the rest
                publish()
            }
            if (phase == CupPhase.GUESSING && myGuess >= 0 && now - lastSentAt > RESEND_MS) {
                lastSentAt = now
                sendGuess() // the result is the ack; until then keep asking
            }
            if (phase == CupPhase.REVEAL && iWantRematch && now - lastSentAt > RESEND_MS) {
                lastSentAt = now
                transport.sendControl(Control.Game(CupShuffleCodec.encodeRematch(round + 1)))
            }
        }
    }

    private fun maybeStartRematch() {
        if (!iWantRematch || !theyWantRematch) return
        round++
        phase = CupPhase.SETUP
        cups = 0
        order = emptyList()
        ballCup = -1
        seq = 0
        appliedSeq = 0
        totalSwaps = 0
        myGuess = -1
        guessCorrect = null
        iWantRematch = false
        theyWantRematch = false
        publish()
    }

    // ---- sends ----------------------------------------------------------------------------------

    private fun sendChoice() {
        lastSentAt = clock()
        transport.sendControl(Control.Game(CupShuffleCodec.encodeChoice(round, cups, ballCup)))
    }

    private fun sendShuffle() {
        lastSentAt = clock()
        transport.sendControl(Control.Game(CupShuffleCodec.encodeShuffle(round, seq, order)))
    }

    private fun sendEnd() {
        lastSentAt = clock()
        transport.sendControl(Control.Game(CupShuffleCodec.encodeEnd(round, totalSwaps, cups)))
    }

    private fun sendGuess() {
        lastSentAt = clock()
        transport.sendControl(Control.Game(CupShuffleCodec.encodeGuess(round, myGuess)))
    }

    private fun sendResult(guessedSlot: Int) {
        lastSentAt = clock()
        transport.sendControl(
            Control.Game(CupShuffleCodec.encodeResult(round, order.indexOf(ballCup), guessedSlot)),
        )
    }

    // ---- snapshot -------------------------------------------------------------------------------

    private fun snapshot() = CupUi(
        phase = phase,
        round = round,
        iShuffle = iShuffle(),
        cups = cups,
        cupsChoice = cupsChoice,
        order = order,
        ballSlot = ballSlot(),
        swaps = if (iShuffle()) seq else appliedSeq,
        totalSwaps = totalSwaps,
        windowLeftMs = if (phase == CupPhase.SHUFFLING && iShuffle()) {
            (shuffleEndsAt - clock()).coerceAtLeast(0)
        } else 0,
        myGuess = myGuess,
        guessCorrect = guessCorrect,
        myNick = nickMe,
        theirNick = nickThem,
        mySide = mySide,
        iWantRematch = iWantRematch,
        theyWantRematch = theyWantRematch,
        myWins = myWins,
        theirWins = theirWins,
    )

    private fun publish() { _ui.value = snapshot() }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        CupShuffleScreen(
            state = state,
            onSlot = ::tapSlot,
            onCups = ::setCups,
            onRematch = ::requestRematch,
            onLeave = onLeave,
        )
    }

    companion object {
        const val TICK_MS = 50L
        /** Both phones display the ball openly this long before the cups cover it. */
        const val SHOW_MS = 2500L
        /** One tap-shuffle window per round, starting when the show ends. */
        const val SHUFFLE_WINDOW_MS = 5000L
        /** Minimum time between accepted taps: the guesser can follow every swap. */
        const val TAP_COOLDOWN_MS = 250L
        const val RESEND_MS = 1500L
    }
}
