package us.crafties.btgames.games.shutthebox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.Transport
import us.crafties.btgames.net.toMatch
import us.crafties.btgames.shell.GameSession
import java.util.Random

/** What every phone knows about one player's box. Everyone sees every box. */
data class StbBoardView(
    /** Bit n-1 set = number n is shut. */
    val mask: Int,
    val die1: Int,
    val die2: Int,
    /** Bumps on every roll so the screen tumbles the dice even if the same faces come up twice. */
    val rollId: Int,
    /** True once this player is finished for the match. */
    val out: Boolean,
    /** Locked score when [out], otherwise the live sum of open numbers. */
    val score: Int,
)

data class ShutTheBoxUi(
    val boards: List<StbBoardView>,
    val mode: Int,
    /** 9 or 12 tiles on every box. */
    val tiles: Int,
    val myTurn: Boolean,
    /** I have rolled and still have to shut numbers (or find out there is no move). */
    val rolled: Boolean,
    val moveAvailable: Boolean,
    val boxShut: Boolean,
    /** Only the seat-0 player, and only before anyone has rolled. */
    val canPickMode: Boolean,
    val nicks: List<String>,
    val mySeat: Int,
    val currentTurn: Int,
    val finalWinners: List<Int>?,
    /** This phone is the table screen: it referees and shows every box but holds no seat. */
    val screen: Boolean = false,
) {
    val myBoard: StbBoardView? get() = boards.getOrNull(mySeat)
    val diceSum: Int? get() = myBoard?.takeIf { rolled && it.die1 != 0 }?.let { it.die1 + it.die2 }
}

/**
 * Session for Shut-The-Box, for a plain 2-player link and for N-player rooms.
 *
 * Every phone keeps every player's box: the player whose turn it is broadcasts their box and dice
 * after each roll and move, so everyone watches the same board. The seat-0 phone keeps the turn
 * order and announces the winners. Two modes (see [StbMode]).
 */
class ShutTheBoxSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    hostGuess: Boolean,
    private val myNick: String,
    private val otherNick: String,
    private val twoPlayer: Boolean,
    /** True on the tablet / TV that hosts as the table screen: the Gamemaster, but not a player. */
    private val screenHost: Boolean = false,
) : GameSession {

    private var isHost: Boolean = hostGuess
    private val random = Random()

    private var mySeat = -1
    private var totalPlayers = 0
    /**
     * Roster in transport order (index 0 = the Gamemaster) and where I am in it. With a table
     * screen as Gamemaster, player seats start at transport index 1: [seatOffset] is 1. The screen
     * knows from the start; phones learn it from the Mode / Turn messages.
     */
    private var roomNicks: List<String> = emptyList()
    private var roomIndex = -1
    private var seatOffset = if (screenHost) 1 else 0

    private fun refreshSeats() {
        nicks = roomNicks.drop(seatOffset)
        totalPlayers = nicks.size
        mySeat = if (roomIndex >= seatOffset) roomIndex - seatOffset else -1
    }

    /** A message told me whether the Gamemaster holds a seat. */
    private fun learnOffset(screen: Boolean) {
        val off = if (screen) 1 else 0
        if (twoPlayer || off == seatOffset) return
        seatOffset = off
        refreshSeats()
    }
    private var currentTurn = 0
    private var finished = false
    private var nicks: List<String> = emptyList()
    private var mode = StbMode.UNTIL_DONE
    private var tiles = StbTiles.NINE

    private var board = ShutTheBoxBoard(0, StbTiles.NINE)
    private val masks = IntArray(MAX_SEATS)
    private val dice1 = IntArray(MAX_SEATS)
    private val dice2 = IntArray(MAX_SEATS)
    private val rollIds = IntArray(MAX_SEATS)
    private val outs = BooleanArray(MAX_SEATS)
    private val scores = IntArray(MAX_SEATS) { -1 }
    private var pending = false
    private var turnDone = false
    private var winners: List<Int>? = null

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<ShutTheBoxUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var control: Job? = null
    private var announce: Job? = null

    private fun snapshot(): ShutTheBoxUi {
        val views = (0 until totalPlayers.coerceAtMost(MAX_SEATS)).map { s ->
            StbBoardView(
                mask = masks[s], die1 = dice1[s], die2 = dice2[s], rollId = rollIds[s], out = outs[s],
                score = if (scores[s] >= 0) scores[s] else ShutTheBoxBoard.scoreOf(masks[s], tiles),
            )
        }
        val sum = if (pending && mySeat in 0 until MAX_SEATS) dice1[mySeat] + dice2[mySeat] else null
        return ShutTheBoxUi(
            boards = views,
            mode = mode,
            tiles = tiles,
            myTurn = isMyTurn(),
            rolled = pending,
            moveAvailable = sum != null && board.hasMove(sum),
            boxShut = board.isShut(),
            canPickMode = isHost && winners == null && currentTurn == 0 && rollIds.all { it == 0 },
            nicks = nicks,
            mySeat = mySeat,
            currentTurn = currentTurn,
            finalWinners = winners,
            screen = screenHost,
        )
    }

    private fun publish() { _ui.value = snapshot() }

    private fun send(msg: ShutTheBoxCodec.Msg) {
        transport.sendControl(Control.Game(ShutTheBoxCodec.encode(msg)))
    }

    override fun start() {
        if (twoPlayer) {
            mySeat = if (isHost) 0 else 1
            totalPlayers = 2
            nicks = if (isHost) listOf(myNick, otherNick) else listOf(otherNick, myNick)
            publish()
        } else {
            listeners = scope.launch {
                transport.room.collect { state ->
                    state.toMatch()?.let { match ->
                        isHost = match.isHost
                        roomIndex = match.myIndex
                        roomNicks = match.nicks
                        refreshSeats()
                        publish()
                    }
                }
            }
        }
        control = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) ShutTheBoxCodec.decodeControl(msg)?.let { handleMessage(it) }
            }
        }
        // The table screen tells the phones it holds no seat, over and over until the match ends:
        // a phone whose session started a moment after the screen's would otherwise miss it.
        if (screenHost) {
            announce = scope.launch {
                while (isActive && winners == null) {
                    send(ShutTheBoxCodec.Msg.Mode(mode, tiles, screen = true))
                    delay(ANNOUNCE_MS)
                }
            }
        }
    }

    override fun stop() {
        listeners?.cancel()
        control?.cancel()
        announce?.cancel()
    }

    /** Seat 0 only, before anyone has rolled. */
    fun pickMode(newMode: Int) {
        if (!_ui.value.canPickMode || newMode == mode) return
        mode = newMode
        send(ShutTheBoxCodec.Msg.Mode(mode, tiles, screenHost))
        publish()
    }

    /** Seat 0 only, before anyone has rolled: 9 or 12 tiles for everyone. */
    fun pickTiles(count: Int) {
        if (!_ui.value.canPickMode || !StbTiles.valid(count) || count == tiles) return
        applyConfig(mode, count)
        send(ShutTheBoxCodec.Msg.Mode(mode, tiles, screenHost))
        publish()
    }

    /** Mode and tile count as the host set them. The tile count only ever changes before the first roll. */
    private fun applyConfig(newMode: Int, newTiles: Int) {
        mode = newMode
        if (StbTiles.valid(newTiles) && newTiles != tiles) {
            tiles = newTiles
            board = ShutTheBoxBoard(0, tiles)
        }
    }

    /** Roll the dice: two, or one once 7, 8 and 9 are all shut. */
    fun rollDice() {
        if (!isMyTurn() || pending) return
        dice1[mySeat] = random.nextInt(6) + 1
        dice2[mySeat] = if (board.rollsOneDie()) 0 else random.nextInt(6) + 1
        rollIds[mySeat] = rollIds[mySeat] % 255 + 1
        pending = true
        broadcastState()
        publish()
    }

    /** Shut the given numbers if they add up to the roll. Returns true if the move was legal. */
    fun closeNumbers(nums: List<Int>): Boolean {
        if (!isMyTurn() || !pending) return false
        if (!board.canClose(nums, dice1[mySeat] + dice2[mySeat])) return false
        board.applyClose(nums)
        masks[mySeat] = board.closedMask()
        pending = false
        broadcastState()
        // One flip per turn: the move is the whole turn. Shutting the box ends my match.
        if (mode == StbMode.ONE_FLIP) finishTurn(out = board.isShut()) else publish()
        return true
    }

    /** The turn can only end now by choice-less necessity: box shut, or the roll has no legal move. */
    fun mustFinish(): Boolean =
        isMyTurn() && (board.isShut() || (pending && !board.hasMove(dice1[mySeat] + dice2[mySeat])))

    fun endTurn() {
        if (mustFinish()) finishTurn(out = true)
    }

    private fun broadcastState() {
        send(ShutTheBoxCodec.Msg.State(mySeat, masks[mySeat], dice1[mySeat], dice2[mySeat], rollIds[mySeat]))
    }

    private fun finishTurn(out: Boolean) {
        val score = board.score()
        pending = false
        turnDone = true
        send(ShutTheBoxCodec.Msg.Done(mySeat, score, out))
        applyDone(mySeat, score, out)
    }

    private fun isMyTurn(): Boolean =
        mySeat in 0 until MAX_SEATS && mySeat == currentTurn && winners == null && !outs[mySeat] && !turnDone

    private fun applyDone(seat: Int, score: Int, out: Boolean) {
        if (seat !in 0 until MAX_SEATS) return
        if (out) { outs[seat] = true; scores[seat] = score }
        if (isHost) afterDone(seat, score, out) else publish()
    }

    /** Seat 0 only: pass the turn on, or end the match. */
    private fun afterDone(seat: Int, score: Int, out: Boolean) {
        val shutEndsMatch = mode == StbMode.ONE_FLIP && out && score == 0
        val next = if (shutEndsMatch) null else nextSeat(seat)
        if (next == null) {
            val live = (0 until totalPlayers).map { s -> if (scores[s] >= 0) scores[s] else ShutTheBoxBoard.scoreOf(masks[s], tiles) }
            val best = live.minOrNull() ?: 0
            val w = live.indices.filter { live[it] == best }
            send(ShutTheBoxCodec.Msg.Final(w))
            finished = true
            winners = w
        } else {
            setTurn(next)
            send(ShutTheBoxCodec.Msg.Turn(next, mode, tiles, screenHost))
        }
        publish()
    }

    private fun nextSeat(from: Int): Int? {
        for (k in 1..totalPlayers) {
            val s = (from + k) % totalPlayers
            if (!outs[s]) return s
        }
        return null
    }

    private fun setTurn(seat: Int) {
        currentTurn = seat
        if (seat == mySeat) { turnDone = false; pending = false }
    }

    private fun handleMessage(msg: ShutTheBoxCodec.Msg) {
        when (msg) {
            is ShutTheBoxCodec.Msg.Mode -> { learnOffset(msg.screen); applyConfig(msg.mode, msg.tiles); publish() }
            is ShutTheBoxCodec.Msg.State -> {
                if (msg.seat in 0 until MAX_SEATS && msg.seat != mySeat) {
                    masks[msg.seat] = msg.mask
                    dice1[msg.seat] = msg.die1
                    dice2[msg.seat] = msg.die2
                    rollIds[msg.seat] = msg.rollId
                    publish()
                }
            }
            is ShutTheBoxCodec.Msg.Done -> applyDone(msg.seat, msg.score, msg.out)
            is ShutTheBoxCodec.Msg.Turn -> { learnOffset(msg.screen); applyConfig(msg.mode, msg.tiles); setTurn(msg.seat); publish() }
            is ShutTheBoxCodec.Msg.Final -> { finished = true; winners = msg.winners; publish() }
        }
    }

    @androidx.compose.runtime.Composable
    override fun Content(onLeave: () -> Unit) {
        if (screenHost) ShutTheBoxTableScreen(session = this, onLeave = onLeave)
        else ShutTheBoxScreen(session = this, onLeave = onLeave)
    }

    private companion object {
        const val MAX_SEATS = 6
        const val ANNOUNCE_MS = 2000L
    }
}
