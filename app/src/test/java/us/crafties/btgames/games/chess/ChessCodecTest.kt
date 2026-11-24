package us.crafties.btgames.games.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessCodecTest {

    @Test fun initialStateRoundTrips() {
        val board = ChessBoard()
        val msg = ChessCodec.decode(ChessCodec.encodeState(0, board)) as ChessCodec.Msg.State
        assertEquals(0, msg.round)
        assertEquals(board, msg.board)
    }

    @Test fun midGameStateRoundTrips() {
        // After 1. e4 c5.
        val afterE4 = ChessBoard().apply(ChessMove(52, 36))!!
        val afterC5 = afterE4.apply(ChessMove(10, 26))!!
        val msg = ChessCodec.decode(ChessCodec.encodeState(7, afterC5)) as ChessCodec.Msg.State
        assertEquals(7, msg.round)
        assertEquals(afterC5, msg.board)
        assertEquals(ChessSide.WHITE, msg.board.turn)
        assertEquals(15, msg.board.castling) // no rights used yet
    }

    @Test fun rematchRoundTrips() {
        val msg = ChessCodec.decode(ChessCodec.encodeRematch(200)) as ChessCodec.Msg.Rematch
        assertEquals(200, msg.round)
    }

    @Test fun rejectsTruncatedAndForeignPackets() {
        assertNull(ChessCodec.decode(byteArrayOf()))
        assertNull(ChessCodec.decode(byteArrayOf(9, 0)))
        assertNull(ChessCodec.decode(ChessCodec.encodeState(0, ChessBoard()).copyOf(10)))
        assertNull(ChessCodec.decode(ChessCodec.encodeRematch(1).copyOf(1)))
    }

    @Test fun rejectsMissingKing() {
        val cells = ChessBoard.initial().toMutableList()
        cells[60] = ChessPiece.EMPTY // remove white king
        val board = ChessBoard(cells = cells)
        assertNull(ChessCodec.decode(ChessCodec.encodeState(0, board)))
    }

    @Test fun fullPayloadFitsTheRadio() {
        val bytes = ChessCodec.encodeState(255, ChessBoard())
        assertTrue("STATE must fit MAX_GAME_PAYLOAD", bytes.size <= 40)
        assertEquals(ChessCodec.STATE_SIZE, bytes.size)
    }
}
