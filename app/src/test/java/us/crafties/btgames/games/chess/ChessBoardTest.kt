package us.crafties.btgames.games.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessBoardTest {

    /** Algebraic helper: a1 = 56, h8 = 7. Row 0 is rank 8. */
    private fun sq(file: Int, rank: Int): Int = (8 - rank) * 8 + file

    private fun ChessBoard.play(from: Int, to: Int): ChessBoard {
        val mv = legalMoves().firstOrNull { it.from == from && it.to == to }
        assertNotNull("no move $from->$to", mv)
        return apply(mv!!)!!
    }

    @Test fun initialPositionHasTwentyLegalMoves() {
        val b = ChessBoard()
        assertEquals(20, b.legalMoves().size)
        assertEquals(ChessSide.WHITE, b.turn)
        assertFalse(b.inCheck())
        assertFalse(b.checkmate)
        assertFalse(b.stalemate)
    }

    @Test fun foolsMateIsCheckmate() {
        // 1. f3 e5 2. g4 Qh4#
        var b = ChessBoard()
        b = b.play(53, 45) // f2-f3
        b = b.play(12, 28) // e7-e5
        b = b.play(54, 38) // g2-g4
        b = b.play(3, 39)  // d8-h4#
        assertTrue(b.checkmate)
        assertFalse(b.stalemate)
        assertEquals(ChessSide.WHITE, b.turn) // white is mated
    }

    @Test fun castlingShortMovesKingAndRook() {
        var b = ChessBoard()
        b = b.play(52, 36) // e2-e4 (clears the f1 diagonal)
        b = b.play(12, 28) // e7-e5
        b = b.play(62, 45) // Ng1-f3 (clears g1)
        b = b.play(1, 18)  // Nb8-c6
        b = b.play(61, 34) // Bf1-c4
        b = b.play(6, 21)  // Ng8-f6
        val castle = b.legalMoves().firstOrNull { it.from == 60 && it.to == 62 }
        assertNotNull("white should be able to castle short", castle)
        b = b.apply(castle!!)!!
        assertEquals(ChessPiece.WK, b.cells[62])
        assertEquals(ChessPiece.WR, b.cells[61])
        assertEquals(ChessPiece.EMPTY, b.cells[63])
        assertEquals(12, b.castling) // white kingside bit (1) cleared from 15
    }

    @Test fun promotionOffersFourChoices() {
        val cells = MutableList(64) { ChessPiece.EMPTY }
        cells[sq(0, 7)] = ChessPiece.WP // a7
        cells[sq(4, 1)] = ChessPiece.WK // e2
        cells[sq(4, 8)] = ChessPiece.BK // e8
        val b = ChessBoard(cells = cells, turn = ChessSide.WHITE, castling = 0)
        val promos = b.movesFrom(sq(0, 7)).filter { it.to == sq(0, 8) }
        assertEquals(setOf(2, 3, 4, 5), promos.map { it.promotion }.toSet())
        val after = b.apply(promos.first { it.promotion == 5 })!!
        assertEquals(ChessPiece.WQ, after.cells[sq(0, 8)])
        assertEquals(ChessSide.BLACK, after.turn)
    }

    @Test fun enPassantCaptureRemovesThePassedPawn() {
        // 1. e4 a6 2. e5 d5 3. exd6 e.p.
        var b = ChessBoard()
        b = b.play(52, 36) // e2-e4
        b = b.play(8, 16)  // a7-a6
        b = b.play(36, 28) // e4-e5
        b = b.play(11, 27) // d7-d5
        val ep = b.legalMoves().firstOrNull { it.from == 28 && it.to == 19 }
        assertNotNull("en passant should be legal", ep)
        val after = b.apply(ep!!)!!
        assertEquals(ChessPiece.EMPTY, after.cells[27]) // black d5 pawn removed
        assertEquals(ChessPiece.WP, after.cells[19])
    }

    @Test fun stalemateIsADraw() {
        // Black Ka8, white Kc7, white Qb6: black is not in check and has no move.
        val cells = MutableList(64) { ChessPiece.EMPTY }
        cells[sq(0, 8)] = ChessPiece.BK // a8
        cells[sq(2, 7)] = ChessPiece.WK // c7
        cells[sq(1, 6)] = ChessPiece.WQ // b6
        val b = ChessBoard(cells = cells, turn = ChessSide.BLACK, castling = 0)
        assertFalse(b.inCheck(ChessSide.BLACK))
        assertTrue(b.stalemate)
        assertFalse(b.checkmate)
    }

    @Test fun sanityRejectsTwoKingsOfOneSide() {
        val cells = ChessBoard.initial().toMutableList()
        cells[sq(3, 1)] = ChessPiece.WK // extra white king on d1
        assertFalse(ChessBoard.sane(cells, 15, -1))
        assertTrue(ChessBoard.sane(ChessBoard.initial(), 15, -1))
    }
}
