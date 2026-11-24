package us.crafties.btgames.games.blocktower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockTowerCodecTest {

    @Test fun seedRoundTrips() {
        val m = BlockTowerCodec.decode(BlockTowerCodec.encodeSeed(2, 0xDEADBEEFL))
        assertEquals(BlockTowerCodec.Msg.Seed(2, 0xDEADBEEFL), m)
    }

    @Test fun ackRoundTrips() {
        assertEquals(BlockTowerCodec.Msg.Ack(3), BlockTowerCodec.decode(BlockTowerCodec.encodeAck(3)))
        assertNull(BlockTowerCodec.decode(byteArrayOf(2)))
    }

    @Test fun moveRoundTrips() {
        val m = BlockTowerCodec.decode(BlockTowerCodec.encodeMove(1, 12, 4, 2))
        assertEquals(BlockTowerCodec.Msg.Move(1, 12, 4, 2), m)
        assertNull(BlockTowerCodec.decode(byteArrayOf(3, 1, 2)))
    }

    @Test fun rematchRoundTrips() {
        assertEquals(BlockTowerCodec.Msg.Rematch(5), BlockTowerCodec.decode(BlockTowerCodec.encodeRematch(5)))
    }

    @Test fun garbageDecodesToNull() {
        assertNull(BlockTowerCodec.decode(byteArrayOf()))
        assertNull(BlockTowerCodec.decode(byteArrayOf(9, 1, 2, 3)))
        assertNull(BlockTowerCodec.decode(byteArrayOf(1, 2)))
    }
}
