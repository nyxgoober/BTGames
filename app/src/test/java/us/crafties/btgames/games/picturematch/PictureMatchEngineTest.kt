package us.crafties.btgames.games.picturematch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureMatchEngineTest {

    @Test fun deckMixesAllPictureKindsAndIsDeterministic() {
        val a = PictureMatchEngine(6, 7, 2)
        val b = PictureMatchEngine(6, 7, 2)
        assertEquals(a.icons, b.icons)
        assertNotEquals(a.icons, PictureMatchEngine(6, 8, 2).icons)
        assertEquals(36, a.icons.size)
        val unique = a.icons.toSet()
        assertTrue("all three kinds are present", PictureIcons.FRUITS.any { it in unique })
        assertTrue(PictureIcons.VEGGIES.any { it in unique })
        assertTrue(PictureIcons.DEVICES.any { it in unique })
        assertTrue(a.icons.groupingBy { it }.eachCount().values.all { it % 2 == 0 })
        assertEquals(18, a.pairCount)
    }

    @Test fun aMatchScoresAndKeepsTheTurn() {
        val e = PictureMatchEngine(6, 3, 2)
        val first = e.icons.indices.first()
        val partner = e.icons.indices.first { it != first && e.icons[it] == e.icons[first] }
        assertEquals(PickResult.FIRST, e.pick(0, first))
        assertEquals(TileState.PEEK, e.tiles[first])
        assertEquals(PickResult.MATCH, e.pick(0, partner))
        assertEquals(1, e.scores[0])
        assertEquals(0, e.turn)
        assertEquals(TileState.MATCHED, e.tiles[first])
        assertEquals(PickResult.ILLEGAL, e.pick(1, 0))
    }

    @Test fun aMissFlipsBackAndPassesTheTurn() {
        val e = PictureMatchEngine(6, 5, 3)
        val first = e.icons.indices.first()
        val other = e.icons.indices.first { e.icons[it] != e.icons[first] }
        assertEquals(PickResult.FIRST, e.pick(0, first))
        assertEquals(PickResult.MISS_OPEN, e.pick(0, other))
        assertEquals(0, e.turn)
        assertEquals(
            PickResult.ILLEGAL,
            e.pick(0, e.icons.indices.first { it != first && it != other }),
        )
        e.resolveMiss()
        assertEquals(TileState.HIDDEN, e.tiles[first])
        assertEquals(1, e.turn)                 // next player in the room
        assertEquals(0, e.scores[0])
    }

    @Test fun theBoardEndsWhenEveryPairIsMatched() {
        val e = PictureMatchEngine(6, 9, 2)
        while (!e.isOver) {
            val seat = e.turn
            val first = e.tiles.indices.first { e.tiles[it] == TileState.HIDDEN }
            val partner = e.tiles.indices.first {
                it != first && e.tiles[it] == TileState.HIDDEN && e.icons[it] == e.icons[first]
            }
            assertEquals(PickResult.FIRST, e.pick(seat, first))
            assertEquals(PickResult.MATCH, e.pick(seat, partner))
        }
        assertEquals(18, e.scores.sum())
        assertTrue(e.isOver)
        assertFalse(e.tiles.any { it == TileState.HIDDEN })
    }

    @Test fun packedStatesRoundTrip() {
        val host = PictureMatchEngine(10, 11, 6)
        host.pick(0, 0)
        host.pick(0, 1)
        host.resolveMiss()
        val client = PictureMatchEngine(10, 11, 6)
        client.applyState(host.turn, host.scores, host.pending, host.last, host.packedTiles())
        assertEquals(host.tiles, client.tiles)
        assertEquals(host.turn, client.turn)
        assertEquals(host.pending, client.pending)
        assertTrue(host.scores.contentEquals(client.scores))
    }
}
