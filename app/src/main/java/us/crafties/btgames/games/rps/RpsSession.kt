package us.crafties.btgames.games.rps

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

enum class RpsPhase { PLAYING, OVER }

/** Everything the RPS screen draws. */
data class RpsUi(
    val phase: RpsPhase,
    val match: Int,
    val round: Int,
    val myThrow: Throw?,
    val theirThrow: Throw?,
    /** Round result, once both throws are in. */
    val roundResult: RpsResult?,
    val myScore: Int,
    val theirScore: Int,
    val draws: Int,
    val outcome: RpsResult?,
    val myMatches: Int,
    val theirMatches: Int,
    val iWantRematch: Boolean,
    val theyWantRematch: Boolean,
    val myNick: String,
    val theirNick: String,
    val mySide: Side,
)

/**
 * Rock-paper-scissors, first to [RpsRules.MATCH_POINT]. Both phones throw at once; throws ride
 * reliable messages and the round resolves the moment both are in, identically on both sides.
 * A lost packet heals itself on resend; a stale one (wrong match or round) is ignored.
 */
class RpsSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
) : GameSession {

    private var match = 0
    private var round = 0
    private var phase = RpsPhase.PLAYING
    private var myThrow: Throw? = null
    private var theirThrow: Throw? = null
    private var roundResult: RpsResult? = null
    private var advanceAt = 0L

    private var myScore = 0
    private var theirScore = 0
    private var draws = 0
    private var outcome: RpsResult? = null
    private var myMatches = 0
    private var theirMatches = 0

    private var iWantRematch = false
    private var theyWantRematch = false

    private var lastSentAt = 0L

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<RpsUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var loop: Job? = null

    override fun start() {
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) RpsCodec.decode(msg.payload)?.let(::onMessage)
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

    fun throw_(t: Throw) {
        if (phase != RpsPhase.PLAYING || myThrow != null) return
        myThrow = t
        sendThrow()
        maybeResolve()
        publish()
    }

    fun requestRematch() {
        if (phase != RpsPhase.OVER || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(RpsCodec.encodeRematch(match + 1)))
        maybeStartRematch()
        publish()
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: RpsCodec.Msg) {
        when (m) {
            is RpsCodec.Msg.ThrowMsg -> {
                if (m.match != match || m.round != round) return
                if (theirThrow != null) return // duplicate: harmless
                theirThrow = m.throw_
                maybeResolve()
                publish()
            }
            is RpsCodec.Msg.Rematch -> {
                if (m.match == match + 1 && phase == RpsPhase.OVER) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    // ---- rounds ---------------------------------------------------------------------------

    private fun maybeResolve() {
        val mine = myThrow
        val theirs = theirThrow
        if (mine == null || theirs == null || roundResult != null) return
        roundResult = RpsRules.outcome(mine, theirs)
        when (roundResult) {
            RpsResult.WIN -> myScore++
            RpsResult.LOSS -> theirScore++
            RpsResult.DRAW -> draws++
            null -> Unit
        }
        advanceAt = SystemClock.uptimeMillis() + REVEAL_MS
    }

    private fun nextRound() {
        if (myScore >= RpsRules.MATCH_POINT || theirScore >= RpsRules.MATCH_POINT) {
            outcome = if (myScore > theirScore) RpsResult.WIN else RpsResult.LOSS
            when (outcome) {
                RpsResult.WIN -> myMatches++
                RpsResult.LOSS -> theirMatches++
                else -> Unit
            }
            phase = RpsPhase.OVER
        } else {
            round++
            myThrow = null
            theirThrow = null
            roundResult = null
        }
        publish()
    }

    private fun maybeStartRematch() {
        if (!iWantRematch || !theyWantRematch) return
        match++
        round = 0
        phase = RpsPhase.PLAYING
        myThrow = null
        theirThrow = null
        roundResult = null
        myScore = 0
        theirScore = 0
        draws = 0
        outcome = null
        iWantRematch = false
        theyWantRematch = false
        publish()
    }

    private fun sendThrow() {
        val t = myThrow ?: return
        lastSentAt = SystemClock.uptimeMillis()
        transport.sendControl(Control.Game(RpsCodec.encodeThrow(match, round, t)))
    }

    // ---- loop -------------------------------------------------------------------------------

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (phase == RpsPhase.PLAYING) {
            // Still waiting on their throw: re-send mine in case it went missing.
            if (myThrow != null && theirThrow == null && now - lastSentAt > RESEND_MS) sendThrow()
            // Both throws in: savour the reveal, then deal the next round.
            if (roundResult != null && now >= advanceAt) nextRound()
        } else if (iWantRematch && now - lastSentAt > RESEND_MS) {
            lastSentAt = now
            transport.sendControl(Control.Game(RpsCodec.encodeRematch(match + 1)))
        }
    }

    // ---- snapshot -------------------------------------------------------------------------------

    private fun snapshot() = RpsUi(
        phase = phase,
        match = match,
        round = round,
        myThrow = myThrow,
        theirThrow = theirThrow,
        roundResult = roundResult,
        myScore = myScore,
        theirScore = theirScore,
        draws = draws,
        outcome = outcome,
        myMatches = myMatches,
        theirMatches = theirMatches,
        iWantRematch = iWantRematch,
        theyWantRematch = theyWantRematch,
        myNick = nickMe,
        theirNick = nickThem,
        mySide = mySide,
    )

    private fun publish() { _ui.value = snapshot() }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        RpsScreen(
            state = state,
            onThrow = ::throw_,
            onRematch = ::requestRematch,
            onLeave = onLeave,
        )
    }

    companion object {
        const val TICK_MS = 100L
        /** How long the reveal lingers before the next round is dealt. */
        const val REVEAL_MS = 2500L
        const val RESEND_MS = 1500L
    }
}
