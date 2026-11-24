package us.crafties.btgames.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTableTest {

    private fun peer(id: Int, address: String, nick: String = "Nyx", rssi: Int = -60) = Peer(id, address, nick, rssi)

    @Test
    fun aPhoneThatRestartsAdvertisingUnderANewAddressIsListedOnce() {
        // This is "Search again": same phone (same id), fresh random Bluetooth address.
        val t = PeerTable(ttlMs = 6000)
        t.sighted(peer(id = 42, address = "AA:AA"), now = 0)
        t.sighted(peer(id = 42, address = "BB:BB"), now = 500)

        val list = t.snapshot()
        assertEquals(1, list.size)
        assertEquals("BB:BB", list.single().address) // connect to where it is NOW, not where it was
    }

    @Test
    fun theOldAddressGoingQuietDoesNotBringTheGhostBack() {
        val t = PeerTable(ttlMs = 6000)
        t.sighted(peer(42, "AA:AA"), 0)
        t.sighted(peer(42, "BB:BB"), 500)
        t.prune(now = 10_000) // the old address would have timed out here
        assertTrue(t.snapshot().isEmpty()) // BB:BB has also been quiet for > ttl
        t.sighted(peer(42, "BB:BB"), 10_000)
        assertEquals(1, t.snapshot().size)
    }

    @Test
    fun differentPlayersStaySeparate() {
        val t = PeerTable(6000)
        t.sighted(peer(1, "AA", nick = "Ann"), 0)
        t.sighted(peer(2, "BB", nick = "Bo"), 0)
        assertEquals(setOf("Ann", "Bo"), t.snapshot().map { it.nick }.toSet())
    }

    @Test
    fun aRenamedPlayerReplacesTheirOldEntry() {
        val t = PeerTable(6000)
        t.sighted(peer(1, "AA", nick = "Ann"), 0)
        t.sighted(peer(1, "CC", nick = "Anna"), 100)
        assertEquals(listOf("Anna"), t.snapshot().map { it.nick })
    }

    @Test
    fun strongestSignalFirstAndUpdatedRssiReorders() {
        val t = PeerTable(6000)
        t.sighted(peer(1, "AA", rssi = -80), 0)
        t.sighted(peer(2, "BB", rssi = -50), 0)
        assertEquals(listOf(2, 1), t.snapshot().map { it.id })
        t.sighted(peer(1, "AA", rssi = -30), 10)
        assertEquals(listOf(1, 2), t.snapshot().map { it.id })
    }

    @Test
    fun equalSignalsKeepAStableOrder() {
        val t = PeerTable(6000)
        t.sighted(peer(9, "AA", rssi = -60), 0)
        t.sighted(peer(3, "BB", rssi = -60), 0)
        t.sighted(peer(5, "CC", rssi = -60), 0)
        assertEquals(listOf(3, 5, 9), t.snapshot().map { it.id })
    }

    @Test
    fun pruneDropsOnlyThePlayersNotHeardFromRecently() {
        val t = PeerTable(ttlMs = 6000)
        t.sighted(peer(1, "AA"), 0)
        t.sighted(peer(2, "BB"), 5000)
        assertTrue(t.prune(now = 7000))            // #1 is 7 s old, #2 is 2 s old
        assertEquals(listOf(2), t.snapshot().map { it.id })
        assertFalse(t.prune(now = 7100))           // nothing further to drop: reports no change
    }

    @Test
    fun aPlayerWhoSwitchedGamesIsDroppedByIdOrByAddress() {
        val t = PeerTable(6000)
        t.sighted(peer(1, "AA"), 0)
        t.sighted(peer(2, "BB"), 0)
        // Player 1 now advertises another game from a NEW address: only the id gives them away.
        assertTrue(t.notForThisLobby(address = "ZZ", id = 1))
        assertEquals(listOf(2), t.snapshot().map { it.id })
        // Player 2's advert could not be decoded at all: the address is all we have.
        assertTrue(t.notForThisLobby(address = "BB", id = null))
        assertTrue(t.snapshot().isEmpty())
        assertFalse(t.notForThisLobby(address = "BB", id = null))
    }

    @Test
    fun clearEmptiesTheList() {
        val t = PeerTable(6000)
        t.sighted(peer(1, "AA"), 0)
        t.clear()
        assertTrue(t.snapshot().isEmpty())
    }
}
