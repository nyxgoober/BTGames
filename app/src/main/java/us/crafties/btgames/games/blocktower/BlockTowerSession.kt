package us.crafties.btgames.games.blocktower

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
import us.crafties.btgames.games.blocktower.BlockTowerPhysics.TowerState

enum class BlockTowerPhase { SYNC, PLAYING, OVER }

enum class BlockTowerResult { WIN, LOSS }

/** Everything the Block Tower screen draws. A fresh copy is published after every move. */
data class BlockTowerUi(
    val phase: BlockTowerPhase,
    val round: Int,
    val tower: TowerState?,
    /** The seeder is still waiting for the seed to be acknowledged. */
    val waitingForPeer: Boolean,
    /** It is my turn to pull. */
    val myTurn: Boolean,
    /** Selected block, or null. */
    val selLayer: Int,
    val selSlot: Int,
    /** Who brought it down, once it has fallen. */
    val felledByMe: Boolean?,
    /** The latest pulled block, for the fly-out animation. Counts every applied pull. */
    val pullSeq: Int,
    val pullLayer: Int,
    val pullSlot: Int,
    val pullJx: Float,
    val pullJz: Float,
    val pullShade: Float,
    val outcome: BlockTowerResult?,
    /** Wall-clock moment the collapse was observed locally (drives the animation). */
    val fellAt: Long,
    val myWins: Int,
    val theirWins: Int,
    /** Pieces each side has pulled out this round — the scoreboard's per-player count. */
    val myPulls: Int,
    val theirPulls: Int,
    val iWantRematch: Boolean,
    val theyWantRematch: Boolean,
    val myNick: String,
    val theirNick: String,
    val mySide: Side,
)

/**
 * Turn-based block tower. Both phones simulate the same tower from one shared seed: the seeder
 * (who also moves first, alternating every round) deals the seed, the other ACKs it, and from
 * there every pull is a reliable MOVE both sides apply to the same state. The physics never
 * crosses the wire — only pulls do — so the tower falls smoothly at 60 fps on each phone and
 * both phones agree on exactly when and by whose hand.
 *
 * Whoever makes the move the tower cannot survive loses, like real Jenga.
 */
class BlockTowerSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
) : GameSession {

    private var round = 0
    private var phase = BlockTowerPhase.SYNC
    private var seed = 0L
    private var tower: TowerState? = null
    private var ackReceived = false
    private var firstMover = true

    private var selLayer = -1
    private var selSlot = -1

    private var felledByMe: Boolean? = null
    private var outcome: BlockTowerResult? = null
    private var fellAt = 0L

    private var pullSeq = 0
    private var pullLayer = -1
    private var pullSlot = -1
    private var pullJx = 0f
    private var pullJz = 0f
    private var pullShade = 0f

    private var myWins = 0
    private var theirWins = 0

    /** Pieces each side has pulled out this round, reset every rematch. */
    private var myPulls = 0
    private var theirPulls = 0

    private var iWantRematch = false
    private var theyWantRematch = false

    private var lastSentAt = 0L

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<BlockTowerUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var loop: Job? = null

    /** The seeder deals the seed and moves first; both alternate every round. */
    private fun iSeed(): Boolean = (round % 2 == 0) == playsFirst

    override fun start() {
        firstMover = iSeed()
        if (iSeed()) {
            seed = kotlin.random.Random.nextLong() and 0xFFFFFFFFL
            tower = BlockTowerPhysics.buildTower(seed)
            // Our tower exists locally from this moment: play starts now, and moves stay
            // gated on the peer's ACK (see waitingForPeer) until they prove theirs exists too.
            phase = BlockTowerPhase.PLAYING
            transport.sendControl(Control.Game(BlockTowerCodec.encodeSeed(round, seed)))
            lastSentAt = SystemClock.uptimeMillis()
        }
        publish()
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) BlockTowerCodec.decode(msg.payload)?.let(::onMessage)
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

    /** Tap a block: first tap selects, tapping the selection again pulls it. */
    fun tapBlock(layer: Int, slot: Int) {
        val t = tower
        if (phase != BlockTowerPhase.PLAYING || t == null || !myTurn() || waitingForPeer()) return
        if (layer == selLayer && slot == selSlot) {
            pull(layer, slot)
            return
        }
        if (!BlockTowerPhysics.isLegalPull(t, layer, slot)) return
        selLayer = layer; selSlot = slot
        publish()
    }

    /** The on-screen Pull button: pulls the current selection. */
    fun pullSelected() {
        if (selLayer < 0) return
        tapBlock(selLayer, selSlot) // second tap on the selection pulls
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: BlockTowerCodec.Msg) {
        when (m) {
            is BlockTowerCodec.Msg.Seed -> {
                if (m.round != round || iSeed()) return
                // Idempotent: a re-sent SEED just gets another ACK and rebuilds the same tower.
                // firstMover is already right (start() set it from iSeed(), which is false here
                // exactly when we accept a seed): flipping it would make both phones believe
                // they move first, trade ignored move-0 pulls, and wait forever.
                seed = m.seed
                tower = BlockTowerPhysics.buildTower(seed)
                phase = BlockTowerPhase.PLAYING
                transport.sendControl(Control.Game(BlockTowerCodec.encodeAck(round)))
                publish()
            }
            is BlockTowerCodec.Msg.Ack -> {
                if (m.round != round || !iSeed()) return
                ackReceived = true
                publish()
            }
            is BlockTowerCodec.Msg.Move -> {
                val t = tower
                if (m.round != round || phase != BlockTowerPhase.PLAYING || t == null) return
                if (m.n != t.moveCount) return // duplicate or from the future: ignore
                applyRemoteMove(m.layer, m.slot)
            }
            is BlockTowerCodec.Msg.Rematch -> {
                if (m.round == round + 1 && phase == BlockTowerPhase.OVER) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    // ---- moves ----------------------------------------------------------------------------

    private fun myTurn(): Boolean {
        val t = tower ?: return false
        val iMoveFirst = firstMover
        // Move numbers alternate strictly: even moves belong to the first mover.
        val firstMoves = t.moveCount % 2 == 0
        return iMoveFirst == firstMoves
    }

    private fun waitingForPeer(): Boolean = iSeed() && !ackReceived

    private fun pull(layer: Int, slot: Int) {
        val t = tower ?: return
        if (!BlockTowerPhysics.isLegalPull(t, layer, slot)) return
        val n = t.moveCount
        transport.sendControl(Control.Game(BlockTowerCodec.encodeMove(round, n, layer, slot)))
        applyMove(layer, slot, byMe = true)
    }

    private fun applyRemoteMove(layer: Int, slot: Int) {
        val t = tower ?: return
        if (!BlockTowerPhysics.isLegalPull(t, layer, slot)) return // corrupt packet: ignore
        applyMove(layer, slot, byMe = false)
    }

    /** The one place the tower advances: mover and peer run this identically. */
    private fun applyMove(layer: Int, slot: Int, byMe: Boolean) {
        val t = tower ?: return
        val removed = t.layers[layer][slot]
        val r = BlockTowerPhysics.applyPull(t, layer, slot, seed)
        tower = r.state
        selLayer = -1; selSlot = -1
        // Remember the pulled block for the fly-out animation (it is already gone from the tower).
        pullSeq++
        pullLayer = layer; pullSlot = slot
        pullJx = removed.jx; pullJz = removed.jz; pullShade = removed.shade
        if (byMe) myPulls++ else theirPulls++
        if (r.collapsed) {
            felledByMe = byMe
            outcome = if (byMe) BlockTowerResult.LOSS else BlockTowerResult.WIN
            if (byMe) theirWins++ else myWins++
            fellAt = SystemClock.uptimeMillis()
            phase = BlockTowerPhase.OVER
        }
        publish()
    }

    fun requestRematch() {
        if (phase != BlockTowerPhase.OVER || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(BlockTowerCodec.encodeRematch(round + 1)))
        maybeStartRematch()
        publish()
    }

    private fun maybeStartRematch() {
        if (!iWantRematch || !theyWantRematch) return
        round++
        phase = BlockTowerPhase.SYNC
        tower = null
        seed = 0L
        ackReceived = false
        firstMover = iSeed()
        selLayer = -1; selSlot = -1
        felledByMe = null
        outcome = null
        pullSeq = 0
        pullLayer = -1; pullSlot = -1
        myPulls = 0; theirPulls = 0
        iWantRematch = false
        theyWantRematch = false
        if (iSeed()) {
            seed = kotlin.random.Random.nextLong() and 0xFFFFFFFFL
            tower = BlockTowerPhysics.buildTower(seed)
            phase = BlockTowerPhase.PLAYING
            transport.sendControl(Control.Game(BlockTowerCodec.encodeSeed(round, seed)))
            lastSentAt = SystemClock.uptimeMillis()
        }
        publish()
    }

    // ---- loop -------------------------------------------------------------------------------

    private fun tick() {
        // The seeder re-sends the seed until the peer proves the tower exists over there.
        // This runs in PLAYING as well as SYNC: the seeder's own tower is local from the start.
        if (iSeed() && !ackReceived && tower != null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSentAt > RESEND_MS) {
                lastSentAt = now
                transport.sendControl(Control.Game(BlockTowerCodec.encodeSeed(round, seed)))
            }
        }
        if (phase == BlockTowerPhase.OVER && iWantRematch) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSentAt > RESEND_MS) {
                lastSentAt = now
                transport.sendControl(Control.Game(BlockTowerCodec.encodeRematch(round + 1)))
            }
        }
    }

    // ---- snapshot -----------------------------------------------------------------------------

    private fun snapshot() = BlockTowerUi(
        phase = phase,
        round = round,
        tower = tower,
        waitingForPeer = waitingForPeer(),
        myTurn = phase == BlockTowerPhase.PLAYING && tower != null && myTurn() && !waitingForPeer(),
        selLayer = selLayer,
        selSlot = selSlot,
        felledByMe = felledByMe,
        pullSeq = pullSeq,
        pullLayer = pullLayer,
        pullSlot = pullSlot,
        pullJx = pullJx,
        pullJz = pullJz,
        pullShade = pullShade,
        outcome = outcome,
        fellAt = fellAt,
        myWins = myWins,
        theirWins = theirWins,
        myPulls = myPulls,
        theirPulls = theirPulls,
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
        BlockTowerScreen(
            state = state,
            seed = seed,
            onTapBlock = ::tapBlock,
            onPull = ::pullSelected,
            onRematch = ::requestRematch,
            onLeave = onLeave,
        )
    }

    companion object {
        const val TICK_MS = 500L
        const val RESEND_MS = 1500L
    }
}
