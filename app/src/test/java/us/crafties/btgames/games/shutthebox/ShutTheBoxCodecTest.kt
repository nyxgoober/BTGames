package us.crafties.btgames.games.shutthebox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ShutTheBoxCodecTest {

    private fun roundTrip(m: ShutTheBoxCodec.Msg) = assertEquals(m, ShutTheBoxCodec.decode(ShutTheBoxCodec.encode(m)))

    @Test fun everyMessageSurvivesTheWire() {
        roundTrip(ShutTheBoxCodec.Msg.Mode(StbMode.ONE_FLIP, StbTiles.TWELVE))
        roundTrip(ShutTheBoxCodec.Msg.Mode(StbMode.UNTIL_DONE, StbTiles.NINE, screen = true))
        roundTrip(ShutTheBoxCodec.Msg.Turn(3, StbMode.UNTIL_DONE, StbTiles.NINE))
        roundTrip(ShutTheBoxCodec.Msg.Turn(5, StbMode.ONE_FLIP, StbTiles.TWELVE, screen = true))
        roundTrip(ShutTheBoxCodec.Msg.State(2, 0x0FFF, 6, 0, 200))
        roundTrip(ShutTheBoxCodec.Msg.Done(1, 17, true))
        roundTrip(ShutTheBoxCodec.Msg.Final(listOf(0, 4)))
    }

    @Test fun oldMessagesWithoutTheScreenByteStillDecode() {
        // Before the table screen existed, Mode was 3 bytes and Turn was 4: no screen flag means "no".
        val mode = ShutTheBoxCodec.decode(byteArrayOf(1, StbMode.ONE_FLIP.toByte(), 9))
        assertEquals(ShutTheBoxCodec.Msg.Mode(StbMode.ONE_FLIP, 9, screen = false), mode)
        val turn = ShutTheBoxCodec.decode(byteArrayOf(4, 2, 0, 12))
        assertEquals(ShutTheBoxCodec.Msg.Turn(2, 0, 12, screen = false), turn)
        assertNotNull(mode); assertNotNull(turn)
    }
}
