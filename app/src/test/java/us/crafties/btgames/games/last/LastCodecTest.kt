package us.crafties.btgames.games.last

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastCodecTest {
    private fun round(m: LastCodec.Msg) = LastCodec.decode(LastCodec.encode(m))

    private val allMessages = listOf(
        LastCodec.Msg.Seed(3, 123456789, 4, LastRules()),
        LastCodec.Msg.Seed(255, -987654321, 2, LastRules(handSize = 15, slapSix = true, draw = DrawRule.UNTIL_MATCH)),
        LastCodec.Msg.Seed(1, 0, 6, LastRules(handSize = 3, slapSix = false, draw = DrawRule.TWO)),
        LastCodec.Msg.Seed(1, 5, 3, LastRules(handSize = 7, slapSix = false, draw = DrawRule.ONE, forcePlay = true)),
        LastCodec.Msg.Seed(1, 5, 3, LastRules(handSize = 7, slapSix = true, draw = DrawRule.TWO, forcePlay = true)),
        LastCodec.Msg.Seed(4, 9, 3, LastRules(handSize = 7, doubling = true)),
        LastCodec.Msg.Seed(4, 9, 6, LastRules(handSize = 5, slapSix = true, draw = DrawRule.TWO, forcePlay = true, doubling = true), screenHost = true),
        LastCodec.Msg.Seed(4, 9, 3, LastRules(handSize = 7, zeroSeven = true)),
        LastCodec.Msg.Seed(4, 9, 6, LastRules(handSize = 5, slapSix = true, doubling = true, zeroSeven = true), screenHost = true),
        LastCodec.Msg.Seed(2, 77, 6, LastRules(handSize = 7), screenHost = true),
        LastCodec.Msg.Seed(2, 77, 2, LastRules(handSize = 5, slapSix = true, draw = DrawRule.TWO, forcePlay = true), screenHost = true),
        LastCodec.Msg.Ready(7),
        LastCodec.Msg.Again(2),
        LastCodec.Msg.Play(1, 5, LastColor.GREEN),
        LastCodec.Msg.Play(1, 0, null),
        LastCodec.Msg.Draw(9),
        LastCodec.Msg.Pass(9),
        LastCodec.Msg.Call(4, 123456),
        LastCodec.Msg.Call(4, LastCodec.NO_TS),
        LastCodec.Msg.Catch(4, 2, 98765),
        LastCodec.Msg.Slap(4, 3, 55),
        LastCodec.Msg.Swap(4, 2, 5),
        LastCodec.Msg.Defend(4, 3),
        LastCodec.Msg.Move(1, 200, LastMove.Play(2, 5, LastColor.YELLOW)),
        LastCodec.Msg.Move(1, 0, LastMove.Play(0, 0, null)),
        LastCodec.Msg.Move(2, 17, LastMove.EndDouble(3)),
        LastCodec.Msg.Move(1, 1, LastMove.Draw(3)),
        LastCodec.Msg.Move(1, 2, LastMove.Pass(1)),
        LastCodec.Msg.Move(1, 3, LastMove.Call(5)),
        LastCodec.Msg.Move(1, 4, LastMove.Catch(2, 4)),
        LastCodec.Msg.Move(1, 5, LastMove.Catch(2, -1)),
        LastCodec.Msg.Move(1, 6, LastMove.Slap(9, 0b101)),
        LastCodec.Msg.Move(1, 7, LastMove.Exchange(2, 4, 5)),
        LastCodec.Msg.Move(1, 8, LastMove.Defend(1, 2)),
        LastCodec.Msg.Move(1, 9, LastMove.DefenseTimeout(7)),
        LastCodec.Msg.Ping(1000),
        LastCodec.Msg.Pong(3, 1000, 2_000_000),
    )

    @Test fun everyMessageSurvivesARoundTrip() {
        for (m in allMessages) assertEquals(m, round(m))
    }

    @Test fun negativeTimestampsSurvive() {
        assertEquals(LastCodec.Msg.Call(1, -5), round(LastCodec.Msg.Call(1, -5)))
    }

    @Test fun messagesFitInAGamePayload() {
        // MAX_GAME_PAYLOAD is 40
        for (m in allMessages) assertTrue(LastCodec.encode(m).size <= 40)
        assertEquals(10, LastCodec.encode(allMessages[0]).size)
    }

    @Test fun shortOrUnknownPayloadsAreRejected() {
        assertNull(LastCodec.decode(byteArrayOf()))
        assertNull(LastCodec.decode(byteArrayOf(99, 1, 2)))
        assertNull(LastCodec.decode(byteArrayOf(1, 1, 0, 0, 0, 0, 2, 7, 0)))    // truncated seed
        assertNull(LastCodec.decode(byteArrayOf(3, 1, 1)))                      // truncated play
        assertNull(LastCodec.decode(byteArrayOf(4)))
        assertNull(LastCodec.decode(byteArrayOf(9, 1, 0, 0)))                   // truncated call
        assertNull(LastCodec.decode(byteArrayOf(12, 1, 1, 0, 0, 0)))            // truncated move
        assertNull(LastCodec.decode(byteArrayOf(8, 1, 0, 0, 0, 0)))             // truncated pong
    }

    @Test fun badPlayerCountsRulesAndColoursAreRejected() {
        assertNull(LastCodec.decode(byteArrayOf(1, 1, 0, 0, 0, 0, 1, 7, 0, 0)))   // 1 player
        assertNull(LastCodec.decode(byteArrayOf(1, 1, 0, 0, 0, 0, 7, 7, 0, 0)))   // 7 players
        assertNull(LastCodec.decode(byteArrayOf(1, 1, 0, 0, 0, 0, 2, 2, 0, 0)))   // 2 starting cards
        assertNull(LastCodec.decode(byteArrayOf(1, 1, 0, 0, 0, 0, 2, 16, 0, 0)))  // 16 starting cards
        assertNull(LastCodec.decode(byteArrayOf(1, 1, 0, 0, 0, 0, 2, 7, 0, 9)))   // draw rule 9
        assertNull(LastCodec.decode(byteArrayOf(3, 1, 1, 9)))                     // colour 9
        assertNull(LastCodec.decode(byteArrayOf(12, 1, 1, 99, 0, 0, 0)))          // unknown move kind
        assertNull(LastCodec.decode(byteArrayOf(12, 1, 1, 0, 0, 0, 9)))           // move colour 9
    }
}
