package us.crafties.btgames.games.checkers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckersCodecTest {

    @Test fun stateRoundTripsWithChainAndPly() {
        // A mid-chain position: FIRST man on (3,4) with its next victim on (2,5).
        val cells = MutableList(64) { Piece.EMPTY }
        cells[3 * 8 + 4] = Piece.FIRST_MAN
        cells[2 * 8 + 5] = Piece.SECOND_MAN
        cells[0 * 8 + 1] = Piece.SECOND_MAN
        cells[6 * 8 + 1] = Piece.FIRST_MAN
        val board = CheckersBoard(cells, CheckerSide.FIRST, CheckerSide.FIRST, pending = 3 * 8 + 4, ply = 7)
        val msg = CheckersCodec.decode(CheckersCodec.encodeState(3, board)) as CheckersCodec.Msg.State
        assertEquals(3, msg.round)
        assertEquals(board, msg.board)
    }

    @Test fun rematchRoundTrips() {
        val msg = CheckersCodec.decode(CheckersCodec.encodeRematch(200)) as CheckersCodec.Msg.Rematch
        assertEquals(200, msg.round)
    }

    @Test fun rejectsTruncatedAndForeignPackets() {
        assertNull(CheckersCodec.decode(byteArrayOf()))
        assertNull(CheckersCodec.decode(byteArrayOf(9, 0)))
        assertNull(CheckersCodec.decode(CheckersCodec.encodeState(0, CheckersBoard()).copyOf(10)))
        assertNull(CheckersCodec.decode(CheckersCodec.encodeRematch(1).copyOf(1)))
    }

    @Test fun rejectsLightSquarePiece() {
        val cells = CheckersBoard.initialCells().toMutableList()
        cells[0] = Piece.FIRST_MAN
        val board = CheckersBoard(cells, CheckerSide.FIRST, CheckerSide.FIRST)
        assertNull(CheckersCodec.decode(CheckersCodec.encodeState(0, board)))
    }

    @Test fun rejectsIllegalPieceCode() {
        val raw = CheckersCodec.encodeState(0, CheckersBoard())
        // First cell (a light square, bits 0..2) set to code 7: no such piece.
        raw[6] = (raw[6].toInt() or 0x07).toByte()
        assertNull(CheckersCodec.decode(raw))
    }

    @Test fun rejectsPendingThatIsNotMyPiece() {
        val board = CheckersBoard(pending = 10) // light square: never a piece of the side to move
        assertNull(CheckersCodec.decode(CheckersCodec.encodeState(0, board)))
    }

    @Test fun fullPayloadFitsTheRadio() {
        val bytes = CheckersCodec.encodeState(255, CheckersBoard())
        assertTrue("STATE must fit MAX_GAME_PAYLOAD", bytes.size <= 40)
    }
}
