package us.crafties.btgames.games.xo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XoCodecTest {

    private fun boardAfter(vararg cells: Int, first: Mark = Mark.X): XoBoard {
        var b = XoBoard(firstMover = first)
        for (c in cells) b = b.play(c, b.turn)!!
        return b
    }

    @Test fun stateRoundTrip() {
        val b = boardAfter(4, 0, 8)
        val dec = XoCodec.decode(XoCodec.encodeState(3, b)) as XoCodec.Msg.State
        assertEquals(3, dec.round)
        assertEquals(b, dec.board)
    }

    @Test fun stateRoundTripKeepsWhoMovedFirst() {
        val b = boardAfter(4, 0, first = Mark.O)
        val dec = XoCodec.decode(XoCodec.encodeState(1, b)) as XoCodec.Msg.State
        assertEquals(Mark.O, dec.board.firstMover)
        assertEquals(b.turn, dec.board.turn)
    }

    @Test fun finishedGamesRoundTrip() {
        val won = boardAfter(0, 3, 1, 4, 2)
        assertEquals(won, (XoCodec.decode(XoCodec.encodeState(0, won)) as XoCodec.Msg.State).board)
        val draw = boardAfter(0, 1, 2, 4, 3, 5, 7, 6, 8) // ends in a draw
        assertEquals(Outcome.DRAW, draw.outcome)
        assertEquals(draw, (XoCodec.decode(XoCodec.encodeState(0, draw)) as XoCodec.Msg.State).board)
    }

    @Test fun rematchRoundTrip() {
        assertEquals(XoCodec.Msg.Rematch(5), XoCodec.decode(XoCodec.encodeRematch(5)))
    }

    @Test fun roundNumbersSurviveTheFullByteRange() {
        for (r in listOf(0, 1, 127, 128, 200, 255)) {
            assertEquals(r, (XoCodec.decode(XoCodec.encodeState(r, XoBoard())) as XoCodec.Msg.State).round)
            assertEquals(r, (XoCodec.decode(XoCodec.encodeRematch(r)) as XoCodec.Msg.Rematch).round)
        }
    }

    // ---- exact bytes (a change here is a wire-format change and must bump Proto.VERSION) ----

    @Test fun stateWireLayoutIsExactly12Bytes() {
        val b = boardAfter(4) // X in the centre, X moved first
        val bytes = XoCodec.encodeState(7, b)
        assertEquals(12, bytes.size)
        assertArrayEquals(
            byteArrayOf(
                1,          // T_STATE
                7,          // round
                1,          // firstMover = X (code 1)
                0, 0, 0,    // cells 0..2
                0, 1, 0,    // cells 3..5   (cell 4 = X)
                0, 0, 0,    // cells 6..8
            ),
            bytes,
        )
    }

    @Test fun rematchWireLayoutIsExactly2Bytes() {
        assertArrayEquals(byteArrayOf(2, 9), XoCodec.encodeRematch(9))
    }

    // ---- rejection of bad input --------------------------------------------------------

    @Test fun emptyAndUnknownTypesAreIgnored() {
        assertNull(XoCodec.decode(byteArrayOf()))
        assertNull(XoCodec.decode(byteArrayOf(99, 1, 2)))
    }

    @Test fun truncatedMessagesAreIgnored() {
        assertNull(XoCodec.decode(byteArrayOf(1, 0, 1, 0, 0)))   // STATE missing cells
        assertNull(XoCodec.decode(byteArrayOf(2)))               // REMATCH missing round
    }

    @Test fun cellValuesOutsideTheMarkRangeAreRejected() {
        val bytes = XoCodec.encodeState(0, XoBoard())
        bytes[5] = 3   // not EMPTY/X/O
        assertNull(XoCodec.decode(bytes))
        bytes[5] = -1
        assertNull(XoCodec.decode(bytes))
    }

    @Test fun anEmptyFirstMoverIsRejected() {
        val bytes = XoCodec.encodeState(0, XoBoard())
        bytes[2] = 0
        assertNull(XoCodec.decode(bytes))
    }

    @Test fun impossibleBoardsAreRejected() {
        // Full 12-byte packet (so it is NOT rejected as truncated): five X and nothing else.
        val bytes = byteArrayOf(1, 0, 1, /* cells */ 1, 1, 1, 1, 1, 0, 0, 0, 0)
        assertEquals(12, bytes.size)
        assertNull(XoCodec.decode(bytes))
    }

    @Test fun aBoardWithTwoWinnersIsRejected() {
        val bytes = byteArrayOf(1, 0, 1, /* cells */ 1, 1, 1, 2, 2, 2, 0, 0, 0) // XXX / OOO / ...
        assertEquals("must be full size so it is not rejected as truncated", 12, bytes.size)
        assertNull(XoCodec.decode(bytes))
    }

    @Test fun aLongerPacketIsAcceptedAndExtraBytesIgnored() {
        // Forward compatibility: a newer phone may append fields.
        val base = XoCodec.encodeState(2, boardAfter(0, 1))
        val longer = base + byteArrayOf(9, 9, 9)
        val dec = XoCodec.decode(longer) as XoCodec.Msg.State
        assertEquals(2, dec.round)
    }

    @Test fun everyEncodedStateFitsInsideTheGamePayloadLimit() {
        assertTrue(XoCodec.encodeState(255, XoBoard()).size <= us.crafties.btgames.net.Proto.MAX_GAME_PAYLOAD)
    }
}
