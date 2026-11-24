package us.crafties.btgames.games.xo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XoBoardTest {

    private val X = Mark.X
    private val O = Mark.O
    private val E = Mark.EMPTY

    /** Build a board by playing the given cells in order, alternating from [first]. */
    private fun play(vararg cells: Int, first: Mark = X): XoBoard {
        var b = XoBoard(firstMover = first)
        for (c in cells) b = b.play(c, b.turn) ?: error("illegal move $c in ${b.cells}")
        return b
    }

    private fun board(s: String, first: Mark = X): XoBoard =
        XoBoard(s.filter { !it.isWhitespace() }.map { if (it == 'X') X else if (it == 'O') O else E }, first)

    // ---- turns ------------------------------------------------------------------------

    @Test fun firstMoverGoesFirstAndTurnsAlternate() {
        var b = XoBoard(firstMover = X)
        assertEquals(X, b.turn)
        b = b.play(0, X)!!; assertEquals(O, b.turn)
        b = b.play(1, O)!!; assertEquals(X, b.turn)
    }

    @Test fun ohCanBeTheFirstMover() {
        val b = XoBoard(firstMover = O)
        assertEquals(O, b.turn)
        assertNull("X cannot open when O is first", b.play(4, X))
        assertEquals(O, b.play(4, O)!!.cells[4])
    }

    // ---- illegal moves ----------------------------------------------------------------

    @Test fun cannotPlayOutOfTurn() {
        val b = XoBoard()
        assertNull(b.play(0, O))
    }

    @Test fun cannotPlayAnOccupiedCell() {
        val b = play(4)
        assertNull(b.play(4, b.turn))
    }

    @Test fun cannotPlayOutsideTheBoard() {
        val b = XoBoard()
        assertNull(b.play(-1, X)); assertNull(b.play(9, X)); assertNull(b.play(100, X))
    }

    @Test fun cannotPlayAfterTheGameIsWon() {
        val b = play(0, 3, 1, 4, 2) // X: 0,1,2
        assertEquals(Outcome.X_WINS, b.outcome)
        assertNull(b.play(8, b.turn))
    }

    @Test fun cannotPlayAnEmptyMark() {
        assertNull(XoBoard().play(0, Mark.EMPTY))
    }

    @Test fun playDoesNotMutateTheOriginalBoard() {
        val a = XoBoard()
        a.play(0, X)
        assertEquals(0, a.moveCount)
    }

    // ---- wins -------------------------------------------------------------------------

    @Test fun allEightLinesWin() {
        val lines = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6),
        )
        for (line in lines) {
            val cells = MutableList(9) { E }
            line.forEach { cells[it] = X }
            val b = XoBoard(cells, X)
            assertEquals("line $line", Outcome.X_WINS, b.outcome)
            assertEquals(line.sorted(), b.winningLine!!.sorted())
        }
    }

    @Test fun ohCanWinToo() {
        val b = board("XX. OOO X..")
        assertEquals(Outcome.O_WINS, b.outcome)
    }

    @Test fun winOnTheNinthMoveIsAWinNotADraw() {
        // X plays last and completes the diagonal on a full board.
        val b = board("XOX OXO OXX")
        assertEquals(Outcome.X_WINS, b.outcome)
    }

    @Test fun fullBoardWithNoLineIsADraw() {
        val b = board("XOX XOO OXX")
        assertEquals(Outcome.DRAW, b.outcome)
        assertNull(b.winningLine)
        assertTrue(b.isOver)
    }

    @Test fun anUnfinishedGameIsInProgress() {
        assertEquals(Outcome.IN_PROGRESS, XoBoard().outcome)
        assertEquals(Outcome.IN_PROGRESS, play(0, 1, 2).outcome)
        assertFalse(XoBoard().isOver)
    }

    @Test fun shortestPossibleWinIsFiveMoves() {
        val b = play(0, 3, 1, 4, 2)
        assertEquals(5, b.moveCount)
        assertEquals(Outcome.X_WINS, b.outcome)
    }

    // ---- reachability guard (protects against corrupt or hostile packets) -------------

    @Test fun anEmptyBoardIsReachable() {
        assertTrue(XoBoard.isReachable(List(9) { E }, X))
        assertTrue(XoBoard.isReachable(List(9) { E }, O))
    }

    @Test fun boardsProducedByLegalPlayAreReachable() {
        assertTrue(XoBoard.isReachable(play(0, 4, 8).cells, X))
        assertTrue(XoBoard.isReachable(play(0, 3, 1, 4, 2).cells, X))
        assertTrue(XoBoard.isReachable(play(4, 0, first = O).cells, O))
    }

    @Test fun tooManyMarksForOnePlayerIsUnreachable() {
        assertFalse(XoBoard.isReachable(board("XXX XX. ...").cells, X)) // 5 X vs 0 O
        assertFalse(XoBoard.isReachable(board("XX. ... ...").cells, X)) // 2 X vs 0 O
    }

    @Test fun theWrongPlayerHavingMoreMarksIsUnreachable() {
        // O has more marks than X although X moved first.
        assertFalse(XoBoard.isReachable(board("OO. X.. ...").cells, X))
    }

    @Test fun twoWinnersIsUnreachable() {
        assertFalse(XoBoard.isReachable(board("XXX OOO ...").cells, X))
    }

    @Test fun moveAfterAWinIsUnreachable() {
        // X (moving first) completes the top row, then O plays again. A win ends the game, so
        // "X has a line AND the counts are equal" can never arise from legal play.
        assertFalse(XoBoard.isReachable(board("XXX OO. O..").cells, X))
        // Contrast: the same row with X holding one extra mark IS a legitimate finished game.
        assertTrue(XoBoard.isReachable(board("XXX OO. ...").cells, X))
    }

    @Test fun firstMoverWinNeedsOneExtraMark() {
        assertTrue(XoBoard.isReachable(board("XXX OO. ...").cells, X))
        assertFalse("first mover cannot win with equal marks", XoBoard.isReachable(board("XXX OOO ...").cells, X))
    }

    @Test fun secondMoverWinNeedsEqualMarks() {
        assertTrue(XoBoard.isReachable(board("XX. OOO X..").cells, X))
        assertFalse(XoBoard.isReachable(board("XX. OOO ...").cells, X)) // 2 X, 3 O
    }

    @Test fun wrongSizedBoardIsUnreachable() {
        assertFalse(XoBoard.isReachable(List(8) { E }, X))
        assertFalse(XoBoard.isReachable(List(10) { E }, X))
    }

    @Test fun reachabilityDependsOnWhoMovedFirst() {
        val cells = board("O.. ... ...").cells
        assertTrue(XoBoard.isReachable(cells, O))
        assertFalse(XoBoard.isReachable(cells, X))
    }

    @Test fun constructorRejectsBadBoards() {
        val threw = try { XoBoard(List(8) { E }); false } catch (_: IllegalArgumentException) { true }
        assertTrue(threw)
        val threw2 = try { XoBoard(List(9) { E }, Mark.EMPTY); false } catch (_: IllegalArgumentException) { true }
        assertTrue(threw2)
    }
}
