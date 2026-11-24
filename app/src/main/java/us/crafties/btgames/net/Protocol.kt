package us.crafties.btgames.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Wire protocol shared by every game. It knows about handshakes and about carrying opaque
 * game payloads; it knows nothing about paddles, boards or scores.
 *
 * Every message is: [type: 1 byte][body...]. Bodies are little-endian.
 * Messages travel over two GATT characteristics:
 *
 *  - CONTROL (reliable):   handshake + game events. Written with response / indicated.
 *  - STREAM  (unreliable): high-rate game data. Write-no-response / notified. Latest wins.
 *
 * Roles:
 *  - "Advertiser" runs the GATT server and waits to be asked.
 *  - "Initiator" scans, connects and sends HELLO. Games decide what that means (in Paddle
 *    the initiator is the physics host and gets the first serve; in XO they play first).
 *
 * N-player rooms (games whose GameDef.maxPlayers > 2) use a star: one phone is the
 * "Gamemaster" (advertises the room, accepts every joiner, relays game messages), everyone
 * else is a client that talks only to the Gamemaster. Room messages ([RoomMsg]) never
 * leave the transport; sessions only ever see [Control.Game] with a sender index.
 */
object Proto {
    const val VERSION: Int = 9

    /** Random per-install-session id, used only to break ties when both phones ask at once. */
    const val TOKEN_BYTES = 4

    val SERVICE_UUID: UUID = UUID.fromString("7e57ed00-0b17-4c0d-9a51-0b7c0ffee001")
    val CONTROL_UUID: UUID = UUID.fromString("7e57ed01-0b17-4c0d-9a51-0b7c0ffee001")
    val STREAM_UUID: UUID = UUID.fromString("7e57ed02-0b17-4c0d-9a51-0b7c0ffee001")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Control messages (reliable)
    const val T_HELLO: Byte = 1     // initiator -> advertiser: version, gameId, nickname
    const val T_ACCEPT: Byte = 2    // advertiser -> initiator: nickname
    const val T_DECLINE: Byte = 3   // advertiser -> initiator: reason code
    const val T_BYE: Byte = 6       // either direction: leaving
    const val T_GAME: Byte = 7      // either direction: opaque, game-defined reliable payload

    // Mutual-initiation handshake (both phones asked each other at the same moment)
    const val T_MUTUAL: Byte = 8    // "I asked you, and I have seen that you asked me"
    const val T_FIRST: Byte = 9     // chooser -> other: who plays first (a session token)
    const val T_FALLBACK: Byte = 10 // "mutual did not confirm here; treat it as an ordinary request"

    // N-player room messages (Gamemaster -> clients only; see RoomMsg)
    const val T_ROOM_INFO: Byte = 11 // capacity of the room
    const val T_SEAT: Byte = 12      // upsert one member (seat + nickname)
    const val T_UNSEAT: Byte = 13    // a member left
    const val T_ROOM_START: Byte = 14 // the Gamemaster starts the match

    const val DECLINE_USER: Int = 0
    const val DECLINE_VERSION: Int = 1
    const val DECLINE_GAME: Int = 2
    const val DECLINE_BUSY: Int = 3
    const val DECLINE_FULL: Int = 4  // room already has maxPlayers phones

    /**
     * High bit of the advertised game id: "this advertiser is an open N-player room".
     * Plain (2-player) lobbies never match it, and room joiners match only rooms, so the
     * two kinds of lobby can share one advertisement format without seeing each other.
     */
    const val ADVERT_ROOM_FLAG: Int = 0x80

    /**
     * Fits the 31-byte scan response: 2 (AD header) + 2 (company id) + 1 (game) + 2 (advert id) + nick.
     * That is exactly 31 with a 24-byte nick, so there is no room left to grow the advert.
     */
    const val MAX_NICK_BYTES = 24
    const val MAX_GAME_PAYLOAD = 40
}

/** Stable ids for the games this app can host. Both phones must agree on these. */
object GameIds {
    const val NONE = 0
    const val PADDLE = 1
    const val XO = 2
    const val TETRIMINOES = 3
    const val KEYMASH = 4
    const val BLOCK_TOWER = 5
    const val RPS = 6
    const val FOUR_CHIPS = 7
    const val CHECKERS = 8
    const val CUP_SHUFFLE = 9
    const val CHESS = 10
    const val SHUT_THE_BOX = 11
    const val LAST = 12
    const val PICTURE_MATCH = 13
}

sealed interface Control {
    data class Hello(val version: Int, val gameId: Int, val token: Int, val nick: String) : Control
    data class Accept(val nick: String) : Control
    data class Decline(val reason: Int) : Control
    /** Both phones asked each other. Carries the sender's token so the peer can confirm it. */
    data class Mutual(val token: Int) : Control
    /** The chooser's decision: [firstToken] is the token of the phone that plays first. */
    data class First(val firstToken: Int) : Control
    /** "Our mutual attempt did not confirm on my side": drop back to an ordinary request. */
    object Fallback : Control
    /**
     * A reliable message that belongs to the running game. The shell never looks inside.
     *
     * [from] is the sender's turn index inside an N-player room (0 = the Gamemaster) and
     * [FROM_PEER] in a 2-player match, where there is only one possible sender. It is
     * transport metadata: it never appears on the wire in the sender's own packet, only in
     * the stamp the Gamemaster adds when relaying to the other clients.
     */
    class Game(val payload: ByteArray, val from: Int = FROM_PEER) : Control {
        companion object {
            const val FROM_PEER: Int = -1
        }
    }
    object Bye : Control
}

/**
 * Membership messages inside an N-player room. They are decoded and consumed by the
 * transport on both ends and never reach a game session: the roster itself is exposed
 * as [us.crafties.btgames.net.Transport.room].
 */
sealed interface RoomMsg {
    /**
     * Sent once to a freshly accepted client: how many phones the room takes and which
     * seat it was given ([yourSeat] is meaningless in any other message, so it lives here).
     */
    data class Info(val min: Int, val max: Int, val yourSeat: Int) : RoomMsg
    /** Upsert one member. Sent as a full replay to a joiner, then one per change to everyone. */
    data class Seat(val seat: Int, val nick: String) : RoomMsg
    /** A member left; drop them from the roster. */
    data class Unseat(val seat: Int) : RoomMsg
    /** The Gamemaster is starting the match: every client moves to Link.Ready. */
    object Start : RoomMsg
}

object Codec {
    /** type(1) + version(1) + gameId(1) + token(4) + nick(<=24) = 31. Needs MTU >= 34, which we request (64). */
    private const val HELLO_MAX = 3 + Proto.TOKEN_BYTES + Proto.MAX_NICK_BYTES

    private fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun sanitizeNick(raw: String): String {
        val cleaned = raw.trim().filter { !it.isISOControl() }
        if (cleaned.toByteArray(Charsets.UTF_8).size <= Proto.MAX_NICK_BYTES) return cleaned
        // Clamp on grapheme-cluster boundaries, not code points, so a Thai consonant is never
        // separated from its vowel/tone marks (and an emoji is never cut in half).
        val breaks = java.text.BreakIterator.getCharacterInstance()
        breaks.setText(cleaned)
        var end = 0
        var next = breaks.next()
        while (next != java.text.BreakIterator.DONE) {
            if (cleaned.substring(0, next).toByteArray(Charsets.UTF_8).size > Proto.MAX_NICK_BYTES) break
            end = next
            next = breaks.next()
        }
        return cleaned.substring(0, end)
    }

    private fun putNick(b: ByteBuffer, nick: String) {
        val bytes = sanitizeNick(nick).toByteArray(Charsets.UTF_8)
        b.put(bytes.size.toByte())
        b.put(bytes)
    }

    private fun getNick(b: ByteBuffer): String {
        val len = b.get().toInt() and 0xFF
        val bytes = ByteArray(minOf(len, b.remaining()))
        b.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun ByteBuffer.trimmed(): ByteArray = array().copyOf(position())

    fun encode(c: Control): ByteArray = when (c) {
        is Control.Hello -> buf(HELLO_MAX).apply {
            put(Proto.T_HELLO); put(c.version.toByte()); put(c.gameId.toByte()); putInt(c.token)
            put(sanitizeNick(c.nick).toByteArray(Charsets.UTF_8)) // nick runs to the end of the packet
        }.trimmed()
        is Control.Accept -> buf(2 + Proto.MAX_NICK_BYTES).apply {
            put(Proto.T_ACCEPT); putNick(this, c.nick)
        }.trimmed()
        is Control.Decline -> byteArrayOf(Proto.T_DECLINE, c.reason.toByte())
        is Control.Mutual -> buf(1 + Proto.TOKEN_BYTES).put(Proto.T_MUTUAL).putInt(c.token).array()
        is Control.First -> buf(1 + Proto.TOKEN_BYTES).put(Proto.T_FIRST).putInt(c.firstToken).array()
        Control.Fallback -> byteArrayOf(Proto.T_FALLBACK)
        is Control.Game -> byteArrayOf(Proto.T_GAME) + c.payload.copyOf(minOf(c.payload.size, Proto.MAX_GAME_PAYLOAD))
        Control.Bye -> byteArrayOf(Proto.T_BYE)
    }

    /**
     * The Gamemaster's relay form of a game message: [T_GAME][from][payload], where [from]
     * is the sender's turn index. Clients strip the stamp before the session sees it; a
     * client's own upload stays an ordinary [encode]d [Control.Game] because the Gamemaster
     * already knows who sent it from the connection it arrived on.
     */
    fun encodeGameFrom(payload: ByteArray, from: Int): ByteArray =
        byteArrayOf(Proto.T_GAME, from.toByte()) +
            payload.copyOf(minOf(payload.size, Proto.MAX_GAME_PAYLOAD))

    fun encodeRoom(m: RoomMsg): ByteArray = when (m) {
        is RoomMsg.Info -> byteArrayOf(Proto.T_ROOM_INFO, m.min.toByte(), m.max.toByte(), m.yourSeat.toByte())
        is RoomMsg.Seat -> buf(3 + Proto.MAX_NICK_BYTES).apply {
            put(Proto.T_SEAT); put(m.seat.toByte()); putNick(this, m.nick)
        }.trimmed()
        is RoomMsg.Unseat -> byteArrayOf(Proto.T_UNSEAT, m.seat.toByte())
        RoomMsg.Start -> byteArrayOf(Proto.T_ROOM_START)
    }

    fun decodeRoom(data: ByteArray): RoomMsg? {
        if (data.isEmpty()) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (b.get()) {
                Proto.T_ROOM_INFO -> {
                    if (b.remaining() < 3) return null
                    RoomMsg.Info(
                        b.get().toInt() and 0xFF,
                        b.get().toInt() and 0xFF,
                        b.get().toInt() and 0xFF,
                    )
                }
                Proto.T_SEAT -> {
                    val seat = b.get().toInt() and 0xFF
                    RoomMsg.Seat(seat, getNick(b))
                }
                Proto.T_UNSEAT -> {
                    if (b.remaining() < 1) return null
                    RoomMsg.Unseat(b.get().toInt() and 0xFF)
                }
                Proto.T_ROOM_START -> RoomMsg.Start
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeControl(data: ByteArray): Control? {
        if (data.isEmpty()) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (b.get()) {
                Proto.T_HELLO -> {
                    val version = b.get().toInt() and 0xFF
                    val gameId = b.get().toInt() and 0xFF
                    val token = b.int
                    val rest = ByteArray(b.remaining()).also { b.get(it) }
                    Control.Hello(version, gameId, token, String(rest, Charsets.UTF_8))
                }
                Proto.T_ACCEPT -> Control.Accept(getNick(b))
                Proto.T_DECLINE -> Control.Decline(b.get().toInt())
                Proto.T_MUTUAL -> Control.Mutual(b.int)
                Proto.T_FIRST -> Control.First(b.int)
                Proto.T_FALLBACK -> Control.Fallback
                Proto.T_GAME -> Control.Game(ByteArray(b.remaining()).also { b.get(it) })
                Proto.T_BYE -> Control.Bye
                else -> null
            }
        } catch (_: Exception) {
            null // malformed packet from a stranger: ignore rather than crash
        }
    }

    // ---- Advertisement payload (scan response manufacturer data) ---------------------------
    // [gameId: 1 byte][advertId: 2 bytes, little-endian][nick utf-8...].
    //
    // The game id lets the lobby show only the phones waiting in the SAME game, so a Paddle player
    // never sees an XO player. The advert id names the phone itself. It stays the same for as long
    // as the app runs, which is what lets a scanner recognise a phone that restarted its
    // advertising under a new random Bluetooth address, instead of listing it twice.

    /** What one advertisement says. [id] is 0..65535. */
    data class Advert(val gameId: Int, val id: Int, val nick: String)

    fun encodeAdvert(gameId: Int, id: Int, nick: String): ByteArray {
        val nickBytes = sanitizeNick(nick).toByteArray(Charsets.UTF_8)
        return byteArrayOf(gameId.toByte(), id.toByte(), (id ushr 8).toByte()) + nickBytes
    }

    fun decodeAdvert(data: ByteArray?): Advert? {
        if (data == null || data.size < 3) return null
        val id = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        return Advert(data[0].toInt() and 0xFF, id, String(data, 3, data.size - 3, Charsets.UTF_8))
    }

    /** What an open N-player room advertises: the game id with [Proto.ADVERT_ROOM_FLAG] set. */
    fun roomAdvertId(gameId: Int): Int = gameId or Proto.ADVERT_ROOM_FLAG
}
