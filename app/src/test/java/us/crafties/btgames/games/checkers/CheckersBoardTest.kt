package us.crafties.btgames.games.checkers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckersBoardTest {

    private val FM = Piece.FIRST_MAN
    private val FK = Piece.FIRST_KING
    private val SM = Piece.SECOND_MAN
    private val SK = Piece.SECOND_KING
    private val E = Piece.EMPTY

    /** Build a board from 8 strings of 8, top row first. F/f = first man/king, S/s = second, . = empty. */
    private fun board(vararg rows: String, turn: CheckerSide = CheckerSide.FIRST): CheckersBoard {
        require(rows.size == 8)
        val cells = rows.flatMap { row ->
            require(row.length == 8)
            row.map {
                when (it) {
                    'F' -> FM; 'f' -> FK; 'S' -> SM; 's' -> SK
                    else -> E
                }
            }
        }
        return CheckersBoard(cells, CheckerSide.FIRST, turn)
    }

    private fun cell(r: Int, c: Int) = r * 8 + c

    @Test fun initialSetupHoldsEightEachAndFirstMoves() {
        val b = CheckersBoard()
        assertEquals(8, b.cells.count { it.side() == CheckerSide.FIRST })
        assertEquals(8, b.cells.count { it.side() == CheckerSide.SECOND })
        assertTrue("light squares stay empty", b.cells.indices.all {
            CheckersBoard.isDark(it) || b.cells[it] == E
        })
        assertEquals(CheckerSide.FIRST, b.turn)
        // Seven opening slides: the rear rank is blocked by its own front rank, and the four
        // front men share four landings (edge men have one slide, the rest two).
        assertEquals(7, b.nextBoards().size)
    }

    @Test fun manSlidesForwardOnly() {
        // FIRST man on a dark centre square with room ahead (a far SECOND man keeps the game live).
        val b = board(
            ".S......",
            "........",
            "........",
            "........",
            "........",
            "..F.....",
            "........",
            "........",
        )
        val from = cell(5, 2)
        assertEquals(setOf(cell(4, 1), cell(4, 3)), b.stepsFrom(from).map { it.to }.toSet())
        val next = b.applyStep(from, cell(4, 1))!!
        assertEquals(FM, next.cells[cell(4, 1)])
        assertEquals(E, next.cells[from])
        assertEquals(CheckerSide.SECOND, next.turn)
    }

    @Test fun manCannotStepBackwards() {
        val b = board(
            ".S......",
            "........",
            "........",
            "........",
            "........",
            "..F.....",
            "........",
            "........",
        )
        assertNull(b.applyStep(cell(5, 2), cell(6, 1)))
        assertNull(b.applyStep(cell(5, 2), cell(6, 3)))
    }

    @Test fun captureIsMandatory() {
        // SECOND man steps up beside a FIRST man that could jump it.
        val b = board(
            "........",
            "........",
            "........",
            "........",
            "...S....",
            "..F.....",
            "........",
            "........",
            turn = CheckerSide.FIRST,
        )
        // FIRST at (5,2) jumps over (4,3) to (3,4); slides are refused while it exists.
        assertEquals(listOf(Step(cell(5, 2), cell(3, 4))), b.stepsFrom(cell(5, 2)))
        assertNull("slide refused while a jump is on", b.applyStep(cell(5, 2), cell(4, 1)))
        val next = b.applyStep(cell(5, 2), cell(3, 4))!!
        assertEquals(E, next.cells[cell(4, 3)])
        assertEquals(FM, next.cells[cell(3, 4)])
        assertEquals(CheckerSide.SECOND, next.turn)
    }

    @Test fun chainKeepsTheTurnWithTheSamePiece() {
        val b = board(
            "........",
            "........",
            ".....S..",
            "........",
            "...S....",
            "..F.....",
            "........",
            "........",
            turn = CheckerSide.FIRST,
        )
        val chained = b.applyStep(cell(5, 2), cell(3, 4))!!
        assertEquals("turn stays for the chain", CheckerSide.FIRST, chained.turn)
        assertEquals(cell(3, 4), chained.pending)
        // Nothing else may move mid-chain, and the chain piece has exactly one continuation.
        assertTrue(b.stepsFrom(cell(5, 2)).isNotEmpty()) // sanity: the jump existed
        assertTrue(chained.stepsFrom(cell(0, 0)).isEmpty())
        assertEquals(listOf(Step(cell(3, 4), cell(1, 6))), chained.stepsFrom(cell(3, 4)))
        val done = chained.applyStep(cell(3, 4), cell(1, 6))!!
        assertEquals(CheckerSide.SECOND, done.turn)
        assertEquals(-1, done.pending)
        assertEquals(E, done.cells[cell(2, 5)])
    }

    @Test fun crowningStopsTheChain() {
        // FIRST man one jump from the far rank. A fresh king on (0,3) could jump the second
        // victim on (1,4) — but English rules crown and stop instead.
        val b = board(
            "........",
            "..S.S...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            turn = CheckerSide.FIRST,
        )
        // Place the jumper: FIRST man at (2,1) jumps (1,2) and crowns on (0,3).
        val cells = b.cells.toMutableList()
        cells[cell(2, 1)] = FM
        val withJumper = b.copy(cells = cells)
        val crowned = withJumper.applyStep(cell(2, 1), cell(0, 3))!!
        assertEquals(FK, crowned.cells[cell(0, 3)])
        assertEquals("crowning passes the turn", CheckerSide.SECOND, crowned.turn)
        assertEquals(-1, crowned.pending)
    }

    @Test fun manCannotCaptureBackwards() {
        // A SECOND man behind a FIRST man: FIRST moves up, so jumping down is backwards.
        val b = board(
            ".S......",
            "........",
            "........",
            "........",
            "........",
            "....F...",
            ".....S..",
            "........",
            turn = CheckerSide.FIRST,
        )
        assertNull(b.applyStep(cell(5, 4), cell(7, 6)))
        assertTrue(b.stepsFrom(cell(5, 4)).none { it.to == cell(7, 6) })
    }

    @Test fun kingFliesDiagonals() {
        val b = board(
            ".S......",
            "........",
            "........",
            "..f.....",
            "........",
            "........",
            "........",
            "........",
        )
        val from = cell(3, 2)
        // Every empty square along each diagonal, edge to edge.
        assertEquals(
            setOf(
                cell(2, 1), cell(1, 0),
                cell(2, 3), cell(1, 4), cell(0, 5),
                cell(4, 1), cell(5, 0),
                cell(4, 3), cell(5, 4), cell(6, 5), cell(7, 6),
            ),
            b.stepsFrom(from).map { it.to }.toSet(),
        )
    }

    @Test fun kingCapturesFromDistanceLandingDirectlyBehind() {
        val b = board(
            ".S......",
            "........",
            ".....S..",
            "........",
            "........",
            "..f.....",
            "........",
            "........",
            turn = CheckerSide.FIRST,
        )
        // The king on (5,2) sights the man on (2,5) down a clear diagonal and lands on (1,6).
        val next = b.applyStep(cell(5, 2), cell(1, 6))
        assertNotNull(next)
        assertEquals(FK, next!!.cells[cell(1, 6)])
        assertEquals(E, next.cells[cell(2, 5)])
        assertEquals(E, next.cells[cell(5, 2)])
        assertEquals(CheckerSide.SECOND, next.turn)
    }

    @Test fun kingCannotLandBeyondTheSquareBehind() {
        val b = board(
            ".S......",
            "........",
            ".....S..",
            "........",
            "........",
            "..f.....",
            "........",
            "........",
            turn = CheckerSide.FIRST,
        )
        // (0,7) is past the mandatory landing square (1,6): refused.
        assertNull(b.applyStep(cell(5, 2), cell(0, 7)))
    }

    @Test fun kingCannotJumpItsOwnSide() {
        val b = board(
            ".S......",
            "........",
            "........",
            "..f.....",
            "...F....",
            "........",
            "........",
            "........",
            turn = CheckerSide.FIRST,
        )
        assertNull("blocked by its own man", b.applyStep(cell(3, 2), cell(6, 5)))
    }

    @Test fun kingChainKeepsTheTurn() {
        val b = board(
            ".S......",
            "........",
            ".....S..",
            "........",
            ".....S..",
            "........",
            "...f....",
            "........",
            turn = CheckerSide.FIRST,
        )
        // (6,3) takes (4,5) landing on (3,6), then (2,5) landing on (1,4).
        val chained = b.applyStep(cell(6, 3), cell(3, 6))!!
        assertEquals(CheckerSide.FIRST, chained.turn)
        assertEquals(cell(3, 6), chained.pending)
        assertEquals(listOf(Step(cell(3, 6), cell(1, 4))), chained.stepsFrom(cell(3, 6)))
        val done = chained.applyStep(cell(3, 6), cell(1, 4))!!
        assertEquals(CheckerSide.SECOND, done.turn)
        assertEquals(-1, done.pending)
        assertEquals(E, done.cells[cell(2, 5)])
    }

    @Test fun noPiecesLeftLoses() {
        val b = board(
            "........",
            "........",
            "........",
            "...F....",
            "........",
            "........",
            "........",
            "........",
        )
        assertEquals(CheckerSide.FIRST, b.winner())
        assertTrue(b.isOver)
        assertTrue(b.nextBoards().isEmpty())
    }

    @Test fun noLegalMoveLoses() {
        // SECOND to move with a lone man on its own back rank: forward is off the board,
        // so it has no step and loses.
        val b = board(
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "S.....F.",
            turn = CheckerSide.SECOND,
        )
        assertTrue(b.stepsFrom(cell(7, 0)).isEmpty())
        assertEquals(CheckerSide.FIRST, b.winner())
    }

    @Test fun saneRejectsLightSquarePieces() {
        val cells = CheckersBoard.initialCells().toMutableList()
        cells[0] = FM // light square
        assertFalse(CheckersBoard.isSane(cells, CheckerSide.FIRST, -1))
    }

    @Test fun saneRejectsNinthPiece() {
        val cells = MutableList(64) { E }
        var placed = 0
        for (i in 0..63) {
            if (placed >= 9) break
            if (CheckersBoard.isDark(i)) {
                cells[i] = FM
                placed++
            }
        }
        assertFalse(CheckersBoard.isSane(cells, CheckerSide.FIRST, -1))
    }

    @Test fun saneAcceptsPendingOwnPiece() {
        val b = board(
            "........",
            "........",
            ".....S..",
            "........",
            "...S....",
            "..F.....",
            "........",
            "........",
            turn = CheckerSide.FIRST,
        )
        val chained = b.applyStep(cell(5, 2), cell(3, 4))!!
        assertNotNull(chained)
        assertTrue(CheckersBoard.isSane(chained.cells, chained.turn, chained.pending))
        assertFalse(CheckersBoard.isSane(chained.cells, CheckerSide.SECOND, chained.pending))
    }
}
