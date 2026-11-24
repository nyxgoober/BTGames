package us.crafties.btgames.games.picturematch

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
import us.crafties.btgames.net.toMatch
import us.crafties.btgames.shell.GameSession
import kotlin.random.Random

/** Everything the screen draws. Scores and nicks are indexed by seat (0 = host). */
data class PictureMatchUi(
    val ready: Boolean,
    val isHost: Boolean,
    val match: Int,
    val grid: Int,
    val icons: List<String>,
    val states: List<TileState>,
    val scores: List<Int>,
    val nicks: List<String>,
    val turn: Int,
    val mySeat: Int,
    val myTurn: Boolean,
    val mySide: Side,
    val iWantAgain: Boolean,
) {
    val over: Boolean get() = states.isNotEmpty() && states.all { it == TileState.MATCHED }
    /** The sole top scorer, or -1 for a tie. */
    val winner: Int get() {
        val best = scores.maxOrNull() ?: return -1
        val top = scores.indices.filter { scores[it] == best }
        return if (top.size == 1) top.first() else -1
    }
}

/**
 * Picture Match over a plain two-player link or in a 2-6 player room. The host (seat 0) picks the
 * grid, shuffles the mixed fruit/vegetable/device board from a shared seed and referees every flip.
 * Everyone else sends flip requests; the host broadcasts packed board snapshots. A pair scores a
 * point and keeps the turn; a miss flips back and passes the turn to the next seat.
 */
class PictureMatchSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    playsFirst: Boolean,
    private val mySide: Side,
    private val myNick: String,
    private val otherNick: String,
    private val twoPlayer: Boolean = true,
) : GameSession {

    private var isHost: Boolean = playsFirst
    private var mySeat: Int = if (playsFirst) 0 else 1
    private var totalPlayers: Int = 2
    private var nicks: List<String> = if (playsFirst) listOf(myNick, otherNick) else listOf(otherNick, myNick)

    private var match = 0
    private var grid = PictureIcons.VALID_GRIDS.first()
    private var seed = 0
    private var engine: PictureMatchEngine? = null
    private var ready = false
    private val acks = mutableSetOf<Int>()
    private val againVotes = mutableSetOf<Int>()

    private var listeners: Job? = null
    private var roomJob: Job? = null
    private var loop: Job? = null
    private var missJob: Job? = null

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<PictureMatchUi> = _ui.asStateFlow()

    override fun start() {
        if (!twoPlayer) {
            roomJob = scope.launch {
                transport.room.collect { state ->
                    state.toMatch()?.let { m ->
                        isHost = m.isHost
                        mySeat = m.myIndex
                        totalPlayers = m.nicks.size
                        nicks = m.nicks
                        publish()
                    }
                }
            }
        }
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) PictureMatchCodec.decode(msg.payload)?.let { onMessage(it, senderSeat(msg)) }
            }
        }
        loop = scope.launch {
            while (true) {
                if (isHost && !ready && engine != null) sendSeed()
                if (engine?.isOver == true && mySeat in againVotes) send(PictureMatchCodec.Msg.Again(match))
                delay(RESEND_MS)
            }
        }
    }

    override fun stop() {
        roomJob?.cancel(); listeners?.cancel(); loop?.cancel(); missJob?.cancel()
    }

    /** In a 2-player link the transport stamps FROM_PEER; in a room it stamps the seat index. */
    private fun senderSeat(msg: Control.Game): Int =
        if (msg.from == Control.Game.FROM_PEER) 1 - mySeat else msg.from

    // ---- setup ----------------------------------------------------------------------------

    fun chooseGrid(value: Int) {
        if (!isHost || ready || value !in PictureIcons.VALID_GRIDS) return
        grid = value
        publish()
    }

    /** Host only: build the mixed board and announce it. */
    fun deal() {
        if (!isHost || ready || totalPlayers < 2) return
        startMatch(Random.nextInt())
    }

    private fun startMatch(newSeed: Int) {
        seed = newSeed
        engine = PictureMatchEngine(grid, seed, totalPlayers)
        ready = false
        acks.clear()
        againVotes.clear()
        publish()
        if (isHost) sendSeed()
    }

    // ---- play ------------------------------------------------------------------------------

    /** Turn a tile up. The host applies it at once; other players ask the host to apply it. */
    fun flip(tile: Int) {
        val e = engine ?: return
        if (!ready || e.isOver || e.turn != mySeat || e.tiles.getOrNull(tile) != TileState.HIDDEN) return
        if (isHost) applyPick(mySeat, tile)
        else send(PictureMatchCodec.Msg.Pick(match, tile))
    }

    private fun applyPick(seat: Int, tile: Int) {
        val e = engine ?: return
        when (e.pick(seat, tile)) {
            PickResult.ILLEGAL -> return
            PickResult.MISS_OPEN -> {
                broadcastState()
                scheduleMiss()
                publish()
            }
            else -> {
                broadcastState()
                publish()
            }
        }
    }

    /** Everyone flips the two unmatched tiles back after a beat, but only the host decides it. */
    private fun scheduleMiss() {
        missJob?.cancel()
        missJob = scope.launch {
            delay(MISS_MS)
            val e = engine ?: return@launch
            e.resolveMiss()
            if (isHost) broadcastState()
            publish()
        }
    }

    fun requestAgain() {
        val e = engine ?: return
        if (!e.isOver) return
        againVotes.add(mySeat)
        send(PictureMatchCodec.Msg.Again(match))
        maybeStartAgain()
        publish()
    }

    private fun maybeStartAgain() {
        if (!isHost || againVotes.size < totalPlayers) return
        match = (match + 1) and 0xFF
        startMatch(Random.nextInt())
    }

    // ---- messages -------------------------------------------------------------------------

    private fun onMessage(m: PictureMatchCodec.Msg, sender: Int) {
        when (m) {
            is PictureMatchCodec.Msg.Seed -> {
                val next = (match + 1) and 0xFF
                if (isHost || (m.match != match && m.match != next)) return
                if (ready && m.match == match) {
                    send(PictureMatchCodec.Msg.Ready(m.match))
                    return
                }
                match = m.match
                grid = m.grid
                seed = m.seed
                engine = PictureMatchEngine(m.grid, m.seed, totalPlayers.coerceAtLeast(2))
                ready = true
                acks.clear()
                againVotes.clear()
                publish()
                send(PictureMatchCodec.Msg.Ready(m.match))
            }
            is PictureMatchCodec.Msg.Ready -> {
                if (!isHost || m.match != match || ready || sender !in 0 until totalPlayers) return
                acks.add(sender)
                if (acks.size >= totalPlayers - 1) {
                    ready = true
                    publish()
                }
            }
            is PictureMatchCodec.Msg.Pick -> {
                if (!isHost || !ready || m.match != match || sender !in 0 until totalPlayers) return
                applyPick(sender, m.tile)
            }
            is PictureMatchCodec.Msg.State -> {
                if (isHost || m.match != match) return
                val e = engine ?: return
                e.applyState(m.turn, m.scores, m.pending, m.last, m.packed)
                ready = true
                publish()
            }
            is PictureMatchCodec.Msg.Again -> {
                if (m.match != match || sender !in 0 until totalPlayers) return
                againVotes.add(sender)
                maybeStartAgain()
                publish()
            }
        }
    }

    private fun send(m: PictureMatchCodec.Msg) {
        transport.sendControl(Control.Game(PictureMatchCodec.encode(m)))
    }

    private fun sendSeed() {
        send(PictureMatchCodec.Msg.Seed(match, grid, seed))
    }

    private fun broadcastState() {
        val e = engine ?: return
        send(
            PictureMatchCodec.Msg.State(match, grid, e.turn, e.scores, e.pending, e.last, e.packedTiles()),
        )
    }

    // ---- snapshot -------------------------------------------------------------------------

    private fun snapshot(): PictureMatchUi {
        val e = engine
        return PictureMatchUi(
            ready = ready,
            isHost = isHost,
            match = match,
            grid = grid,
            icons = e?.icons ?: emptyList(),
            states = e?.tiles?.toList() ?: emptyList(),
            scores = (e?.scores?.toList() ?: emptyList()) + List((totalPlayers - (e?.players ?: 0)).coerceAtLeast(0)) { 0 },
            nicks = nicks,
            turn = e?.turn ?: 0,
            mySeat = mySeat,
            myTurn = ready && e != null && !e.isOver && e.turn == mySeat,
            mySide = mySide,
            iWantAgain = mySeat in againVotes,
        )
    }

    private fun publish() { _ui.value = snapshot() }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        PictureMatchScreen(
            state = state,
            onGrid = ::chooseGrid,
            onDeal = ::deal,
            onFlip = ::flip,
            onAgain = ::requestAgain,
            onLeave = onLeave,
        )
    }

    private companion object {
        const val RESEND_MS = 1500L
        const val MISS_MS = 900L
    }
}
