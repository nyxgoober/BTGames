package us.crafties.btgames.net

/**
 * The lobby's list of nearby players. Pure Kotlin (no Android, no clock of its own) so the rules
 * can be unit tested.
 *
 * Players are keyed by [Peer.id], never by Bluetooth address. A phone that restarts its advertising
 * (which is what "Search again" does) comes back under a NEW random address; keyed by address, the
 * scanner would list it twice until the old entry timed out. Keyed by id, the new sighting simply
 * replaces the old one and the player never appears twice.
 */
class PeerTable(private val ttlMs: Long) {

    private class Entry(var peer: Peer, var seenAt: Long)

    private val byId = HashMap<Int, Entry>()

    /** Record an advertisement. A later sighting of the same id replaces the earlier one, address included. */
    fun sighted(peer: Peer, now: Long) {
        val e = byId[peer.id]
        if (e == null) byId[peer.id] = Entry(peer, now) else { e.peer = peer; e.seenAt = now }
    }

    /**
     * The phone at [address] (whose advertised id is [id], if we could read one) is advertising
     * something that is not for this lobby: another game, or not our app. Drop whoever we had listed
     * for it, by address or by id, so a player who switched games does not linger as a ghost.
     * Returns true if the list changed.
     */
    fun notForThisLobby(address: String, id: Int?): Boolean =
        byId.values.removeAll { it.peer.address == address || it.peer.id == id }

    /** Drop players not heard from within the TTL. Returns true if the list changed. */
    fun prune(now: Long): Boolean = byId.values.removeAll { now - it.seenAt > ttlMs }

    fun clear() = byId.clear()

    /** Strongest signal first; ties broken by id so the order never flickers between equal signals. */
    fun snapshot(): List<Peer> =
        byId.values.map { it.peer }.sortedWith(compareByDescending<Peer> { it.rssi }.thenBy { it.id })
}
