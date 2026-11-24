package us.crafties.btgames.games.fourchips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FourChipsCodecTest {

    private fun board(vararg rows: String, first: Chip = Chip.FIRST): FourChipsBoard {
        val cells = rows.flatMap { row ->
            row.map { when (it) { 'F' -> Chip.FIRST; 'S' -> Chip.SECOND; else -> Chip.EMPTY } }
        }
        return FourChipsBoard(cells, first)
    }

    @Test fun emptyBoardRoundTrips() {
        val b = FourChipsBoard()
        val m = FourChipsCodec.decode(FourChipsCodec.encodeState(0, b))
        assertEquals(FourChipsCodec.Msg.State(0, b), m)
    }

    @Test fun playedBoardRoundTripsExactly() {
        var b = FourChipsBoard()
        for (c in listOf(3, 3, 2, 4, 3, 5, 1)) b = b.drop(c, b.turn)!!.board
        val m = FourChipsCodec.decode(FourChipsCodec.encodeState(7, b))
        assertEquals(FourChipsCodec.Msg.State(7, b), m)
    }

    @Test fun fullDrawBoardRoundTrips() {
        val b = board(
            "FSFSFSS",
            "FSFSFSF",
            "SFSFSFS",
            "FSFFFSF",
            "FSSSFSF",
            "SFSFSFS",
        )
        assertEquals(FourOutcome.DRAW, b.outcome)
        val m = FourChipsCodec.decode(FourChipsCodec.encodeState(1, b))
        assertEquals(FourChipsCodec.Msg.State(1, b), m)
    }

    @Test fun floatingDiscsAreRefused() {
        val b = board(
            ".......",
            ".......",
            ".......",
            ".......",
            "F......",
            ".S.....",
        )
        assertNull(FourChipsCodec.decode(FourChipsCodec.encodeState(0, b)))
    }

    @Test fun badFirstMoverIsRefused() {
        val raw = FourChipsCodec.encodeState(0, FourChipsBoard()).copyOf()
        raw[2] = 0 // EMPTY cannot move first
        assertNull(FourChipsCodec.decode(raw))
        raw[2] = 3 // not a chip at all
        assertNull(FourChipsCodec.decode(raw))
    }

    @Test fun truncatedPacketsAreRefused() {
        assertNull(FourChipsCodec.decode(byteArrayOf(1, 0)))
        assertNull(FourChipsCodec.decode(FourChipsCodec.encodeState(0, FourChipsBoard()).copyOf(10)))
    }

    @Test fun rematchRoundTrips() {
        assertEquals(FourChipsCodec.Msg.Rematch(3), FourChipsCodec.decode(FourChipsCodec.encodeRematch(3)))
        assertNull(FourChipsCodec.decode(byteArrayOf(2)))
    }

    @Test fun garbageDecodesToNull() {
        assertNull(FourChipsCodec.decode(byteArrayOf()))
        assertNull(FourChipsCodec.decode(byteArrayOf(9, 1, 2, 3)))
    }
}
