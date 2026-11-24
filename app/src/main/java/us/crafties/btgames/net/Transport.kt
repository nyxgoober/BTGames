package us.crafties.btgames.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A nearby device that is running this app and currently advertising. */
data class Peer(
    /**
     * Who this is, as the phone itself announces it in its advertisement. This is what identifies a
     * player in the list. [address] does NOT: Android gives a phone a fresh random Bluetooth
     * address every time it restarts advertising, so one player can show up under several addresses.
     */
    val id: Int,
    /** Where to connect right now: the address of the most recent sighting. */
    val address: String,
    val nick: String,        // taken from the advertisement; may be blank
    val rssi: Int,
)

sealed interface Link {
    object Idle : Link
    /** Advertising and/or scanning; no connection yet. */
    object Discovering : Link
    /** We connected and sent HELLO; waiting for the other side to accept or decline. */
    data class Requesting(val peerNick: String) : Link
    /** Somebody sent us HELLO; the UI must ask the user to accept or decline. */
    data class Incoming(val peerNick: String) : Link
    /**
     * Both phones asked each other at the same moment. That counts as acceptance on both sides,
     * so nobody is asked to accept. This phone was picked (lower token) to decide who plays first.
     */
    data class Choosing(val peerNick: String) : Link
    /** Mutual request; the OTHER phone is choosing who plays first. */
    data class WaitingForChoice(val peerNick: String) : Link
    /**
     * Handshake complete; the game may start.
     * [weInitiated]  true when we alone sent the request (ordinary flow). For a mutual request
     *                this is instead decided by [playsFirst].
     * [playsFirst]   true when this phone takes the first turn / first serve. Games should use
     *                this rather than [weInitiated] to decide who starts.
     * [mySide]       which colour this phone plays as. Chosen at random per match, and always
     *                opposite to the other phone, so both players may see themselves as either colour.
     *
     * In an N-player room match (GameDef.maxPlayers > 2) the colour is the game's own business —
     * seats come from [Transport.room] via RoomMatch — [playsFirst] is true on the Gamemaster
     * (turn index 0), and [peerNick] is the first other member.
     */
    data class Ready(
        val peerNick: String,
        val weInitiated: Boolean,
        val playsFirst: Boolean,
        /** This phone's colour for the match. The other phone's is always the opposite. */
        val mySide: Side,
    ) : Link
    data class Ended(val reason: String) : Link
}

/** One phone in an N-player room. [seat] is the routing id (0 = the Gamemaster). */
data class RoomMember(val seat: Int, val nick: String, val you: Boolean)

/**
 * The lobby state of an N-player room (a game with GameDef.maxPlayers > 2). [RoomState.None]
 * everywhere else, and before the user picks "start a room" or "join a room".
 */
sealed interface RoomState {
    object None : RoomState
    /** This phone opened the room; [members] starts with us at seat 0. */
    data class Hosting(val members: List<RoomMember>, val min: Int, val max: Int) : RoomState
    /** We joined [hostNick]'s room and are waiting for them to start the match. */
    data class Joined(val hostNick: String, val members: List<RoomMember>, val min: Int, val max: Int) : RoomState
}

/**
 * What a room match is handed over as: everyone in turn order, which one we are, and whether
 * we are the Gamemaster. Seats (routing ids) never leave the transport; games only ever
 * index into [nicks].
 */
data class RoomMatch(
    /** Every player in turn order — the Gamemaster first, then join order. */
    val nicks: List<String>,
    /** Index into [nicks] of this phone. */
    val myIndex: Int,
    /** True on the phone that opened the room (the Gamemaster, turn index 0). */
    val isHost: Boolean,
) {
    val myNick: String get() = nicks[myIndex]
    val hostNick: String get() = nicks[0]
}

/** Turn this room state into the match a session starts with, or null before anyone is seated. */
fun RoomState.toMatch(): RoomMatch? {
    val members = when (this) {
        RoomState.None -> return null
        is RoomState.Hosting -> members
        is RoomState.Joined -> members
    }
    // The list is already in turn order (join order); seats are routing ids, not positions.
    val index = members.indexOfFirst { it.you }
    if (index < 0) return null
    return RoomMatch(members.map { it.nick }, index, this is RoomState.Hosting)
}

/**
 * Everything a game needs from Bluetooth, and nothing Bluetooth-specific.
 * A later game (tic-tac-toe, air hockey...) can reuse this unchanged.
 */
interface Transport {
    val link: StateFlow<Link>
    val peers: StateFlow<List<Peer>>

    /**
     * Lobby state of an N-player room (GameDef.maxPlayers > 2). [RoomState.None] in every
     * 2-player game and before the user chooses to start or join a room.
     */
    val room: StateFlow<RoomState>

    /** Reliable control messages from the other device (handshake, events, bye). */
    val control: Flow<Control>
    /** Unreliable stream messages (raw bytes; caller decodes). */
    val stream: Flow<ByteArray>

    /**
     * Begin advertising our nickname and scanning for other players who are waiting in the
     * SAME game (matched on [gameId]). Call again with a different id to switch games.
     */
    fun startDiscovery(localNick: String, gameId: Int)
    fun stopDiscovery()

    /** Connect to a discovered peer and send HELLO. We become the initiator/host. */
    fun connect(peer: Peer)

    /** Answer an Incoming request. */
    fun accept()
    fun decline(reason: Int = Proto.DECLINE_USER)

    /**
     * Answer a [Link.Choosing] prompt. [iPlayFirst] true means this phone takes the first turn.
     * Only valid while in [Link.Choosing]; otherwise ignored.
     */
    fun chooseFirst(iPlayFirst: Boolean)

    // ---- N-player rooms (only called for games with maxPlayers > 2) --------------------

    /**
     * Open a room for [min]..[max] players: we become the Gamemaster — advertise, accept
     * every joiner up to [max], and hold the roster. Replaces any discovery in progress.
     */
    fun createRoom(localNick: String, gameId: Int, min: Int, max: Int)

    /** Scan for open rooms of the same game (join mode). We do not advertise in this mode. */
    fun findRooms(localNick: String, gameId: Int)

    /** Gamemaster only: start the match now. Every phone moves to [Link.Ready]. */
    fun startMatch()

    fun sendControl(msg: Control)
    fun sendStream(bytes: ByteArray)

    /** Leave the game or cancel; sends BYE if connected. Returns to Idle. */
    fun leave()
    fun release()
}
