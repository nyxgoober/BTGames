package us.crafties.btgames.games.fourchips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.crafties.btgames.games.fourchips.FourChipsBoard.Companion.COLS
import us.crafties.btgames.games.fourchips.FourChipsBoard.Companion.ROWS

class FourChipsBoardTest {

    private val F = Chip.FIRST
    private val S = Chip.SECOND
    private val E = Chip.EMPTY

    /** Play the columns in order, alternating from [first]. */
    private fun play(vararg cols: Int, first: Chip = F): FourChipsBoard {
        var b = FourChipsBoard(firstMover = first)
        for (c in cols) {
            val r = b.drop(c, b.turn) ?: error("illegal drop in column $c: $b")
            b = r.board
        }
        return b
    }

    /** Build a board from 6 strings of 7, top row first. F = first, S = second, . = empty. */
    private fun board(vararg rows: String, first: Chip = F): FourChipsBoard {
        require(rows.size == ROWS)
        val cells = rows.flatMap { row ->
            require(row.length == COLS)
            row.map { when (it) { 'F' -> F; 'S' -> S; else -> E } }
        }
        return FourChipsBoard(cells, first)
    }

    // ---- turns and gravity ------------------------------------------------------------------

    @Test fun firstMoverGoesFirstAndTurnsAlternate() {
        var b = FourChipsBoard(firstMover = F)
        assertEquals(F, b.turn)
        b = b.drop(3, F)!!.board; assertEquals(S, b.turn)
        b = b.drop(3, S)!!.board; assertEquals(F, b.turn)
    }

    @Test fun secondCanMoveFirst() {
        val b = FourChipsBoard(firstMover = S)
        assertEquals(S, b.turn)
        assertNull("first cannot open when second starts", b.drop(0, F))
        assertEquals(S, b.drop(0, S)!!.board.cells[5 * COLS])
    }

    @Test fun discsStackFromTheBottom() {
        val b = play(2, 2, 2)
        assertEquals(F, b.cells[5 * COLS + 2])
        assertEquals(S, b.cells[4 * COLS + 2])
        assertEquals(F, b.cells[3 * COLS + 2])
        assertEquals(3, b.moveCount)
    }

    @Test fun dropReportsTheLandingRow() {
        val b = FourChipsBoard()
        assertEquals(5, b.drop(0, F)!!.row)
        assertEquals(4, b.drop(0, F)!!.board.drop(0, S)!!.row)
    }

    @Test fun fullColumnRefuses() {
        val b = play(0, 0, 0, 0, 0, 0)
        assertNull(b.drop(0, b.turn))
    }

    @Test fun cannotDropOutOfTurnOrOffBoard() {
        val b = FourChipsBoard()
        assertNull(b.drop(0, S))
        assertNull(b.drop(-1, F))
        assertNull(b.drop(7, F))
    }

    // ---- wins -----------------------------------------------------------------------------------

    @Test fun horizontalFourWins() {
        val b = play(0, 6, 1, 6, 2, 6, 3)
        assertEquals(FourOutcome.FIRST_WINS, b.outcome)
        assertEquals(listOf(35, 36, 37, 38), b.winningCells!!.sorted())
        assertTrue(b.isOver)
        assertNull("no drops after the win", b.drop(4, b.turn))
    }

    @Test fun verticalFourWins() {
        val b = play(0, 1, 0, 1, 0, 2, 0)
        assertEquals(FourOutcome.FIRST_WINS, b.outcome)
        assertEquals(listOf(2 * COLS, 3 * COLS, 4 * COLS, 5 * COLS), b.winningCells!!.sorted())
    }

    @Test fun diagonalFourWins() {
        val b = play(0, 1, 1, 2, 6, 2, 2, 3, 6, 3, 6, 3, 3)
        assertEquals(FourOutcome.FIRST_WINS, b.outcome)
        assertEquals(
            listOf(2 * COLS + 3, 3 * COLS + 2, 4 * COLS + 1, 5 * COLS),
            b.winningCells!!.sorted(),
        )
    }

    @Test fun secondMoverCanWinToo() {
        val b = play(6, 0, 6, 0, 6, 0, 5, 0)
        assertEquals(FourOutcome.SECOND_WINS, b.outcome)
    }

    @Test fun fullBoardWithNoLineIsADraw() {
        // Machine-checked: every run is 3 or shorter, counts are 21/21.
        val b = board(
            "FSFSFSS",
            "FSFSFSF",
            "SFSFSFS",
            "FSFFFSF",
            "FSSSFSF",
            "SFSFSFS",
        )
        assertEquals(FourOutcome.DRAW, b.outcome)
        assertNull(b.winningCells)
        assertTrue(b.isOver)
        assertTrue(FourChipsBoard.isReachable(b.cells, F))
    }

    @Test fun unfinishedBoardIsInProgress() {
        assertEquals(FourOutcome.IN_PROGRESS, FourChipsBoard().outcome)
        assertEquals(FourOutcome.IN_PROGRESS, play(0, 1, 2).outcome)
        assertFalse(FourChipsBoard().isOver)
    }

    // ---- reachability guard -----------------------------------------------------------------------

    @Test fun emptyBoardIsReachable() {
        assertTrue(FourChipsBoard.isReachable(List(42) { E }, F))
        assertTrue(FourChipsBoard.isReachable(List(42) { E }, S))
    }

    @Test fun boardsFromLegalPlayAreReachable() {
        assertTrue(FourChipsBoard.isReachable(play(0, 1, 2).cells, F))
        assertTrue(FourChipsBoard.isReachable(play(0, 6, 1, 6, 2, 6, 3).cells, F))
    }

    @Test fun floatingDiscsAreUnreachable() {
        val b = board(
            ".......",
            ".......",
            ".......",
            ".......",
            "F......",
            ".S.....",
        )
        assertFalse(FourChipsBoard.isReachable(b.cells, F))
    }

    @Test fun wrongCountsAreUnreachable() {
        val b = board(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "FFFS...",
        )
        assertFalse(FourChipsBoard.isReachable(b.cells, F)) // 3 first vs 1 second
    }

    @Test fun twoWinnersAreUnreachable() {
        val b = board(
            ".......",
            ".......",
            ".......",
            ".......",
            "...SSSS",
            "FFFF...",
        )
        assertFalse(FourChipsBoard.isReachable(b.cells, F))
    }

    @Test fun winnerMustHaveMovedLast() {
        // First has a line but the counts are equal: second supposedly moved after the win.
        val b = board(
            ".......",
            ".......",
            ".......",
            ".......",
            ".....SS",
            "FFFF.SS",
        )
        assertFalse(FourChipsBoard.isReachable(b.cells, F))
    }

    @Test fun constructorRejectsBadBoards() {
        val threw = try {
            FourChipsBoard(List(41) { E }); false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(threw)
        val threw2 = try {
            FourChipsBoard(List(42) { E }, Chip.EMPTY); false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(threw2)
    }
}
