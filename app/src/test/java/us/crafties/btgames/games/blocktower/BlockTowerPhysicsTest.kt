package us.crafties.btgames.games.blocktower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.crafties.btgames.games.blocktower.BlockTowerPhysics.TowerState

class BlockTowerPhysicsTest {

    private fun tower(seed: Long = 1234L): TowerState = BlockTowerPhysics.buildTower(seed)

    // ---- the deal ---------------------------------------------------------------------------------

    @Test fun freshTowerIsFullAndUpright() {
        val t = tower()
        assertEquals(BlockTowerPhysics.START_LAYERS, t.layers.size)
        assertTrue(t.layers.all { it.size == 3 && it.all { b -> b.present } })
        assertEquals(0f, t.leanMag, 0f)
        assertFalse(BlockTowerPhysics.hasCollapsed(t))
    }

    @Test fun sameSeedSameTower() {
        val a = tower(99L)
        val b = tower(99L)
        assertEquals(a, b)
    }

    @Test fun differentSeedDifferentTower() {
        val a = tower(1L)
        val b = tower(2L)
        assertTrue("seeded imperfections must differ", a != b)
    }

    // ---- legal pulls ----------------------------------------------------------------------------------

    @Test fun topLayerIsOffLimits() {
        val t = tower()
        val top = BlockTowerPhysics.topLayer(t)
        assertFalse(BlockTowerPhysics.isLegalPull(t, top, 0))
        assertFalse(BlockTowerPhysics.isLegalPull(t, top, 1))
        assertTrue(BlockTowerPhysics.isLegalPull(t, top - 1, 1))
    }

    @Test fun emptySlotsAndOutsidersAreIllegal() {
        val t = tower()
        assertFalse(BlockTowerPhysics.isLegalPull(t, -1, 0))
        assertFalse(BlockTowerPhysics.isLegalPull(t, 0, 3))
        assertFalse(BlockTowerPhysics.isLegalPull(t, 999, 0))
    }

    // ---- pulls -------------------------------------------------------------------------------------------

    @Test fun pullRemovesTheBlockForGood() {
        val t = tower()
        val before = t.layers.sumOf { layer -> layer.count { it.present } }
        val r = BlockTowerPhysics.applyPull(t, 0, 0, 1234L)
        assertFalse("pulled block is gone", r.state.layers[0][0].present)
        assertEquals(1, r.state.moveCount)
        // Nothing is stacked back: exactly one block left the tower, nowhere gained one.
        val after = r.state.layers.sumOf { layer -> layer.count { it.present } }
        assertEquals(before - 1, after)
        assertEquals(BlockTowerPhysics.START_LAYERS, r.state.layers.size)
    }

    @Test fun freshTowerHasMovesLeft() {
        assertTrue(BlockTowerPhysics.hasLegalPull(tower()))
    }

    @Test fun sameSeedSameMovesSameTower() {
        fun play(seed: Long): TowerState {
            var t = BlockTowerPhysics.buildTower(seed)
            val moves = listOf(Triple(0, 1, 0), Triple(1, 2, 0), Triple(2, 0, 0), Triple(0, 2, 0))
            for ((l, s, _) in moves) {
                if (!BlockTowerPhysics.isLegalPull(t, l, s)) break
                t = BlockTowerPhysics.applyPull(t, l, s, seed).state
            }
            return t
        }
        assertEquals(play(555L), play(555L))
    }

    @Test fun samePullsDifferentSeedDifferentLean() {
        fun lean(seed: Long): Float {
            var t = BlockTowerPhysics.buildTower(seed)
            repeat(6) { i ->
                val layer = i % 5
                if (BlockTowerPhysics.isLegalPull(t, layer, 0)) {
                    t = BlockTowerPhysics.applyPull(t, layer, 0, seed).state
                }
            }
            return t.leanMag
        }
        // Ten seeds must not all lean identically: the wobble is really seeded.
        val leans = (1L..10L).map { lean(it) }.toSet()
        assertTrue("leans were all identical: $leans", leans.size > 1)
    }

    @Test fun leanGrowsTowardThePulledSide() {
        // Layer 0 runs along X: slots spread across z. Pulling slot 2 must lean +z.
        val t = tower(77L)
        val r = BlockTowerPhysics.applyPull(t, 0, 2, 77L)
        assertTrue("lean should tip toward the gap, was ${r.state.leanZ}", r.state.leanZ > 0f)
    }

    @Test fun edgePullShakesHarderThanCentre() {
        // Same tower, same stream position: only the slot differs.
        val edge = BlockTowerPhysics.applyPull(tower(11L), 0, 0, 11L).state.leanMag
        val centre = BlockTowerPhysics.applyPull(tower(11L), 0, 1, 11L).state.leanMag
        assertTrue("edge $edge should beat centre $centre", edge > centre)
    }

    @Test fun guttingALayerCollapses() {
        // Pull two blocks of layer 0, then the last one: nothing holds layer 1 anymore.
        val seed = 4242L
        var t = tower(seed)
        t = BlockTowerPhysics.applyPull(t, 0, 0, seed).state
        t = BlockTowerPhysics.applyPull(t, 0, 2, seed).state
        assertTrue(BlockTowerPhysics.isLegalPull(t, 0, 1))
        val r = BlockTowerPhysics.applyPull(t, 0, 1, seed)
        assertTrue("a layer with no blocks cannot hold the tower", r.collapsed)
    }

    @Test fun illegalPullThrows() {
        var threw = false
        try {
            BlockTowerPhysics.applyPull(tower(), BlockTowerPhysics.START_LAYERS - 1, 0, 1L)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ---- collapse scatter ----------------------------------------------------------------------------------

    @Test fun scatterIsDeterministic() {
        val t = tower(31337L)
        val a = BlockTowerPhysics.collapseScatter(t, BlockTowerPhysics.fallSeed(31337L, 7))
        val b = BlockTowerPhysics.collapseScatter(t, BlockTowerPhysics.fallSeed(31337L, 7))
        assertEquals(a, b)
        assertEquals(30, a.size)
        assertTrue(a.all { it.vy > 0f }) // every block pops up before it falls
    }

    @Test fun mixSeedSeparatesMoveStreams() {
        assertFalse(BlockTowerPhysics.mixSeed(9L, 0) == BlockTowerPhysics.mixSeed(9L, 1))
    }
}
