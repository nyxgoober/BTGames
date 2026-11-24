package us.crafties.btgames.net

/**
 * One phone in an N-player room. [seat] is the transport's routing id (0 is always the
 * Gamemaster); the position in the roster list is the turn order, which is join order and
 * never renumbers while the room is open.
 */
data class RoomSeat(val seat: Int, val nick: String)

/**
 * Roster bookkeeping for an N-player room. Pure: no Android, no IO, no clocks — both ends
 * of the wire fold [RoomMsg]s through it, and the Gamemaster also allocates seats with it.
 *
 * The Gamemaster creates it with the game's capacity and adds itself at seat 0; a joiner
 * creates it from the [RoomMsg.Info] the Gamemaster sends on acceptance. Seats are reused
 * after a leave (the lowest free one), so a client that saw the matching [RoomMsg.Unseat]
 * can never confuse an old member with the new one.
 */
class RoomRoster(val min: Int, val max: Int) {

    /** Insertion order = join order = turn order. Seat 0 (the Gamemaster) is always first. */
    private val bySeat = LinkedHashMap<Int, String>()

    /** Members in turn order. */
    val members: List<RoomSeat> get() = bySeat.map { RoomSeat(it.key, it.value) }

    val size: Int get() = bySeat.size

    val full: Boolean get() = bySeat.size >= max

    /** The Gamemaster's own seat, added when it opens the room. */
    fun addHost(nick: String) {
        bySeat[0] = nick
    }

    /**
     * Seat a new arrival: the lowest free seat above the Gamemaster's, or null when the
     * room is already full. Returns the assigned seat.
     */
    fun join(nick: String): Int? {
        if (full) return null
        val seat = (1 until max).firstOrNull { it !in bySeat } ?: return null
        bySeat[seat] = nick
        return seat
    }

    /** A member left (or was never a member: dropping an unknown seat is a no-op). */
    fun drop(seat: Int) {
        bySeat.remove(seat)
    }

    fun nickOf(seat: Int): String? = bySeat[seat]

    fun seatOfTurn(turnIndex: Int): Int? = bySeat.keys.elementAtOrNull(turnIndex)

    /** Which turn (0-based) the phone at [seat] plays; -1 if it is not in the room. */
    fun turnIndexOf(seat: Int): Int = bySeat.keys.indexOf(seat)

    /**
     * The messages that describe this whole roster, in turn order. A fresh joiner gets
     * these after [RoomMsg.Info]; an existing member only ever needs the one message
     * that changed, because [apply] is an upsert.
     */
    fun seatMessages(): List<RoomMsg.Seat> = members.map { RoomMsg.Seat(it.seat, it.nick) }

    /** Fold one membership message into a client's view of the room. */
    fun apply(msg: RoomMsg) {
        when (msg) {
            is RoomMsg.Seat -> bySeat[msg.seat] = msg.nick
            is RoomMsg.Unseat -> drop(msg.seat)
            is RoomMsg.Info, RoomMsg.Start -> Unit // capacity is fixed at construction; Start is not roster state
        }
    }

    /** This phone's turn index, or -1 when [seat] is not (or no longer) in the room. */
    fun myTurnIndex(seat: Int): Int = turnIndexOf(seat)
}
