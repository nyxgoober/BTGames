package us.crafties.btgames.games.tetriminoes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TetriminoesEngineTest {

    private fun empty(): TetriminoesEngine =
        TetriminoesEngine(random = kotlin.random.Random(7)).also {
            // Wipe the dealt position so tests start from a known board; the piece stays.
            for (row in it.grid) row.fill(0)
        }

    // ---- movement ---------------------------------------------------------------------

    @Test fun freshBoardIsEmptyAndAlive() {
        val e = empty()
        assertFalse(e.gameOver)
        assertEquals(0, e.score)
        assertEquals(0, e.lines)
        assertTrue(e.grid.all { row -> row.all { it == 0 } })
    }

    @Test fun wallsStopSidewaysMoves() {
        val e = empty()
        e.setPiece(Kind.O, x = 0, y = 5)
        assertFalse(e.moveLeft())
        e.setPiece(Kind.O, x = 8, y = 5)
        assertFalse(e.moveRight())
        e.setPiece(Kind.O, x = 4, y = 5)
        assertTrue(e.moveLeft())
        assertTrue(e.moveRight())
    }

    @Test fun squareNeverTurns() {
        val e = empty()
        e.setPiece(Kind.O, x = 4, y = 5)
        val before = e.currentCells().toSet()
        assertTrue(e.rotateCW())
        assertEquals(before, e.currentCells().toSet())
    }

    @Test fun teeTurnsClockwise() {
        val e = empty()
        e.setPiece(Kind.T, rot = 0, x = 3, y = 0)
        val before = e.currentCells().toSet()
        assertTrue(e.rotateCW())
        assertEquals(
            setOf(4 to 0, 4 to 1, 5 to 1, 4 to 2),
            e.currentCells().toSet(),
        )
        assertFalse(before == e.currentCells().toSet())
    }

    // ---- falling and locking ------------------------------------------------------------

    @Test fun softDropScoresOneACell() {
        val e = empty()
        e.setPiece(Kind.O, x = 4, y = 0)
        assertTrue(e.softDrop())
        assertEquals(1, e.score)
    }

    @Test fun gravityNeverScores() {
        val e = empty()
        e.setPiece(Kind.O, x = 4, y = 0)
        assertTrue(e.gravityTick())
        assertEquals(0, e.score)
    }

    @Test fun hardDropScoresTwoACell() {
        val e = empty()
        e.setPiece(Kind.O, x = 4, y = 0)
        // O is 2 tall: from rows 0-1 down to rows 18-19 is 18 rows.
        assertEquals(18, e.ghostDistance())
        assertEquals(18, e.hardDrop())
        assertEquals(36, e.score)
        assertEquals("....22....", e.grid[18].joinToString("") { if (it == 0) "." else "2" })
        assertEquals("....22....", e.grid[19].joinToString("") { if (it == 0) "." else "2" })
    }

    @Test fun singleLineClearsForOneHundredTimesLevel() {
        val e = empty()
        e.level = 3
        e.setRows(
            *Array(18) { ".........." },
            "..........",
            "XXXX..XXXX",
        )
        e.setPiece(Kind.O, x = 4, y = 18)
        e.hardDrop() // distance 0: locks in place and completes the bottom row
        assertEquals(1, e.lines)
        assertEquals(100 * 3, e.score)
        // The O's top half drops into the cleared row.
        assertEquals("....22....", e.grid[19].joinToString("") { if (it == 0) "." else "2" })
    }

    @Test fun doubleLineClearsForThreeHundred() {
        val e = empty()
        e.setRows(
            *Array(18) { ".........." },
            "XXXX..XXXX",
            "XXXX..XXXX",
        )
        e.setPiece(Kind.O, x = 4, y = 18)
        e.hardDrop()
        assertEquals(2, e.lines)
        assertEquals(300, e.score)
    }

    @Test fun stackingToTheTopEndsTheGame() {
        val e = TetriminoesEngine(random = kotlin.random.Random(1))
        repeat(300) { if (!e.gameOver) e.hardDrop() }
        assertTrue("straight stacking in the middle must top out", e.gameOver)
    }

    // ---- speed curve --------------------------------------------------------------------

    @Test fun levelRunsOneToTenAcrossTheMatch() {
        assertEquals(1, TetriminoesEngine.levelFor(0, 120))
        assertEquals(1, TetriminoesEngine.levelFor(6, 120))
        assertEquals(5, TetriminoesEngine.levelFor(60, 120))
        assertEquals(10, TetriminoesEngine.levelFor(120, 120))
        assertEquals(10, TetriminoesEngine.levelFor(999, 120))
    }

    @Test fun gravityStartsSlowEndsFastAndNeverSpeedsUp() {
        assertEquals(750L, TetriminoesEngine.intervalMs(0, 120))
        assertEquals(110L, TetriminoesEngine.intervalMs(120, 120))
        var last = Long.MAX_VALUE
        for (t in 0..120 step 5) {
            val cur = TetriminoesEngine.intervalMs(t.toLong(), 120)
            assertTrue("interval must not rise ($last -> $cur at $t)", cur <= last)
            last = cur
        }
    }
}
