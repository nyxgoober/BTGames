package us.crafties.btgames.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.GameIds
import kotlin.random.Random

/**
 * The opponent on the other end of a match. Test mode only: receives everything the player's
 * session sends and answers with random-but-legal moves, so one phone can play every game solo.
 *
 * A bot must never send synchronously from [start]: the player's session subscribes to the
 * transport only after the link is delivered, so anything sent without a delay (or in reply
 * to a player message) would fall on the floor. Rule of thumb: first sends go out on player
 * input, or after at least half a second.
 */
interface BotBrain {
    /** The match is starting. Must not send yet (see above); arm timers or nothing. */
    fun start()

    /** The match is over. Cancel everything; must be safe to call more than once. */
    fun stop()

    /** Reliable message from the player's session. */
    fun onPlayerControl(msg: Control)

    /** Unreliable stream packet from the player's session. */
    fun onPlayerStream(bytes: ByteArray)
}

/**
 * A [Transport] with no radio behind it, for test mode. It speaks only to the fake TestMode
 * peer: tapping that peer skips discovery and the handshake entirely and drops straight into
 * [Link.Ready], with a [BotBrain] wired up as the opponent.
 *
 * Player-to-bot traffic goes to [BotBrain.onPlayerControl]/[onPlayerStream]; bot-to-player
 * traffic comes back out of [control]/[stream]. The two directions never mix, so (unlike a
 * naive echo) the session never hears its own messages.
 */
class LoopbackTransport(val scope: CoroutineScope) : Transport {
    private var gameId: Int = GameIds.NONE
    companion object {
        const val FAKE_ID = -1
        const val FAKE_NICK = "TestMode"

        /** Test mode's opponent introduces itself differently in every game. */
        fun botNick(gameId: Int): String = when (gameId) {
            GameIds.PADDLE -> "bt?pong"
            GameIds.XO -> "bt?xoxo"
            GameIds.TETRIMINOES -> "bt?four"
            GameIds.KEYMASH -> "bt?board"
            GameIds.BLOCK_TOWER -> "bt?topple"
            GameIds.RPS -> "bt?hand"
            GameIds.FOUR_CHIPS -> "bt?connect"
            GameIds.CHECKERS -> "bt?kings"
            GameIds.CHESS -> "bt?castle"
            GameIds.CUP_SHUFFLE -> "bt?cups"
            GameIds.SHUT_THE_BOX -> "bt?box"
            GameIds.LAST -> "bt?cards"
            GameIds.PICTURE_MATCH -> "bt?pairs"
            else -> FAKE_NICK
        }

        /** The device that is always nearby when test mode is on. */
        fun fakePeer(gameId: Int = GameIds.NONE): Peer =
            Peer(FAKE_ID, "loopback", botNick(gameId), -40)
    }

    /**
     * Builds the opponent for a game. Injected by the shell so this stays game-agnostic like
     * every other transport: given the game id and the colour the PLAYER drew, return the bot.
     */
    var botFactory: ((transport: LoopbackTransport, gameId: Int, playerSide: Side) -> BotBrain?)? = null

    private val _link = MutableStateFlow<Link>(Link.Idle)
    override val link: StateFlow<Link> = _link.asStateFlow()

    // Discovery never runs here; the shell adds [fakePeer] to the real list itself.
    override val peers: StateFlow<List<Peer>> = MutableStateFlow(emptyList())

    private val _control = MutableSharedFlow<Control>(extraBufferCapacity = 64)
    override val control: Flow<Control> = _control

    private val _stream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val stream: Flow<ByteArray> = _stream

    private val _room = MutableStateFlow<RoomState>(RoomState.None)
    override val room: StateFlow<RoomState> = _room.asStateFlow()

    // N-player room stubs – Loopback is only used for single‑player test mode, so these are no‑ops.
    override fun createRoom(localNick: String, gameId: Int, min: Int, max: Int) {
        // No real room, but we keep a placeholder state for compatibility.
        _room.value = RoomState.Hosting(listOf(RoomMember(0, localNick, true)), min, max)
    }

    override fun findRooms(localNick: String, gameId: Int) {
        // No scanning in loopback mode; do nothing.
    }

    override fun startMatch() {
        // Immediately transition to Ready state for a single player.
        // In test mode the host is also the only player, so we just set link ready.
        _link.value = Link.Ready(peerNick = botNick(gameId), weInitiated = true, playsFirst = true, mySide = Side.RED)
    }

    private var bot: BotBrain? = null

    /** Which game the next match will play. Called before [connect], while still in the lobby. */
    fun prepare(gameId: Int) {
        this.gameId = gameId
    }

    override fun connect(peer: Peer) {
        if (peer.id != FAKE_ID) return
        bot?.stop()
        val side = if (Random.nextBoolean()) Side.RED else Side.BLUE
        val b = botFactory?.invoke(this, gameId, side)
        bot = b
        // The player always plays first against the bot: every bot is written to reply (or wait),
        // never to open, so the match can never stall waiting on a silent opponent.
        _link.value = Link.Ready(peerNick = botNick(gameId), weInitiated = true, playsFirst = true, mySide = side)
        b?.start()
    }

    override fun sendControl(msg: Control) {
        bot?.onPlayerControl(msg)
    }

    override fun sendStream(bytes: ByteArray) {
        bot?.onPlayerStream(bytes)
    }

    /** Bot-to-player reliable message. Never blocks; buffered far beyond anything a bot sends. */
    fun botSend(msg: Control) {
        if (!_control.tryEmit(msg)) scope.launch { _control.emit(msg) }
    }

    /** Bot-to-player stream packet. Same deal as [botSend]. */
    fun botSendStream(bytes: ByteArray) {
        if (!_stream.tryEmit(bytes)) scope.launch { _stream.emit(bytes) }
    }

    override fun startDiscovery(localNick: String, gameId: Int) {}
    override fun stopDiscovery() {}
    override fun accept() {}
    override fun decline(reason: Int) {}
    override fun chooseFirst(iPlayFirst: Boolean) {}

    override fun leave() {
        bot?.stop()
        bot = null
        _link.value = Link.Idle
    }

    override fun release() {
        leave()
    }
}
