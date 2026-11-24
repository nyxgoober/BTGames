package us.crafties.btgames.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {

    private fun hello(nick: String, token: Int = 0x1234ABCD, game: Int = GameIds.PADDLE) =
        Control.Hello(Proto.VERSION, game, token, nick)

    private fun roundTrip(c: Control): Control? = Codec.decodeControl(Codec.encode(c))

    // ---- HELLO ------------------------------------------------------------------------

    @Test
    fun helloRoundTripKeepsEveryField() {
        assertEquals(hello("Nyx"), roundTrip(hello("Nyx")))
    }

    @Test
    fun helloTokenSurvivesNegativeValues() {
        // Tokens are random signed ints; the sign bit must not be lost or sign-extended wrongly.
        for (t in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE, 0x80000000.toInt())) {
            val dec = roundTrip(hello("a", token = t)) as Control.Hello
            assertEquals(t, dec.token)
        }
    }

    @Test
    fun helloCarriesTheGameId() {
        for (g in listOf(GameIds.PADDLE, GameIds.XO)) {
            assertEquals(g, (roundTrip(hello("a", game = g)) as Control.Hello).gameId)
        }
    }

    @Test
    fun commonShortThaiNamesSurviveIntact() {
        for (nick in listOf("สมชาย", "นิชา", "ก้อง", "เบิร์ด", "ต้นไม้", "พี่หมี", "ไอซ์")) {
            assertEquals(nick, (roundTrip(hello(nick)) as Control.Hello).nick)
        }
    }

    @Test
    fun overlongThaiNicknameIsCutOnAWholeLetterNeverMidCluster() {
        val nick = "นักเล่นเกมสุดหล่อมาก"
        val dec = roundTrip(hello(nick)) as Control.Hello
        assertTrue(dec.nick.isNotEmpty())
        assertTrue(dec.nick.toByteArray(Charsets.UTF_8).size <= Proto.MAX_NICK_BYTES)
        assertTrue("prefix of the original", nick.startsWith(dec.nick))
        // A Thai vowel/tone mark must never START the remainder, otherwise the cut separated a
        // mark from its consonant.
        val rest = nick.removePrefix(dec.nick)
        if (rest.isNotEmpty()) {
            val c = rest[0].code
            val combining = c == 0x0E31 || c in 0x0E34..0x0E3A || c in 0x0E47..0x0E4E
            assertTrue("cut before a combining mark: U+${c.toString(16)}", !combining)
        }
    }

    @Test
    fun anyNicknameEncodesWithinTheBudget() {
        // type(1) + version(1) + gameId(1) + token(4) + nick(<= 24) = 31 bytes at most.
        val maxHello = 1 + 1 + 1 + Proto.TOKEN_BYTES + Proto.MAX_NICK_BYTES
        for (nick in listOf("a".repeat(64), "นักเล่นเกม".repeat(5), "😀".repeat(10), "")) {
            val enc = Codec.encode(hello(nick))
            assertTrue("nick='$nick' -> ${enc.size}B (max $maxHello)", enc.size <= maxHello)
        }
    }

    @Test
    fun emojiIsNeverSplitInHalf() {
        val dec = roundTrip(hello("😀".repeat(10))) as Control.Hello
        assertTrue(dec.nick.isNotEmpty())
        assertTrue("only whole emoji", dec.nick.all { it == '\uD83D' || it == '\uDE00' })
        assertEquals("even number of UTF-16 units, so no lone surrogate", 0, dec.nick.length % 2)
    }

    @Test
    fun controlCharactersAreStrippedFromNicknames() {
        assertEquals("ab", Codec.sanitizeNick("a\u0000\u0007b"))
        assertEquals("hi", Codec.sanitizeNick("  hi  "))
    }

    // ---- other control messages -------------------------------------------------------

    @Test
    fun acceptDeclineByeRoundTrip() {
        assertEquals(Control.Accept("Bee"), roundTrip(Control.Accept("Bee")))
        assertEquals(Control.Decline(Proto.DECLINE_USER), roundTrip(Control.Decline(Proto.DECLINE_USER)))
        assertEquals(Control.Decline(Proto.DECLINE_BUSY), roundTrip(Control.Decline(Proto.DECLINE_BUSY)))
        assertEquals(Control.Bye, roundTrip(Control.Bye))
    }

    @Test
    fun mutualHandshakeMessagesRoundTrip() {
        for (t in listOf(Int.MIN_VALUE, -7, 0, 42, Int.MAX_VALUE)) {
            assertEquals(Control.Mutual(t), roundTrip(Control.Mutual(t)))
            assertEquals(Control.First(t), roundTrip(Control.First(t)))
        }
        assertEquals(Control.Fallback, roundTrip(Control.Fallback))
    }

    @Test
    fun gamePayloadIsOpaqueAndPreserved() {
        val payload = byteArrayOf(1, 2, 3, 0, -1, 127, -128)
        val dec = roundTrip(Control.Game(payload)) as Control.Game
        assertArrayEquals(payload, dec.payload)
    }

    @Test
    fun oversizedGamePayloadIsClampedNotRejected() {
        val big = ByteArray(Proto.MAX_GAME_PAYLOAD + 25) { it.toByte() }
        val dec = roundTrip(Control.Game(big)) as Control.Game
        assertEquals(Proto.MAX_GAME_PAYLOAD, dec.payload.size)
    }

    // ---- malformed input --------------------------------------------------------------

    @Test
    fun garbageIsIgnoredNotThrown() {
        assertNull(Codec.decodeControl(byteArrayOf()))
        assertNull(Codec.decodeControl(byteArrayOf(99, 1, 2, 3)))
        assertNull(Codec.decodeControl(byteArrayOf(Proto.T_HELLO, 1)))        // truncated HELLO
        assertNull(Codec.decodeControl(byteArrayOf(Proto.T_MUTUAL, 1, 2)))    // truncated token
        assertNull(Codec.decodeControl(byteArrayOf(Proto.T_FIRST)))
        assertNull(Codec.decodeControl(byteArrayOf(Proto.T_DECLINE)))         // missing reason
    }

    // ---- advertisement ----------------------------------------------------------------

    @Test
    fun advertRoundTrip() {
        val ad = Codec.encodeAdvert(GameIds.XO, 0x1234, "Somchai")
        assertEquals(Codec.Advert(GameIds.XO, 0x1234, "Somchai"), Codec.decodeAdvert(ad))
    }

    @Test
    fun advertIdKeepsAllSixteenBits() {
        // The id is unsigned: 0xFFFF must not come back negative, and the high byte must not be lost.
        for (id in listOf(0, 1, 0x00FF, 0x0100, 0x7FFF, 0x8000, 0xFFFF)) {
            val dec = Codec.decodeAdvert(Codec.encodeAdvert(GameIds.PADDLE, id, "a"))!!
            assertEquals(id, dec.id)
        }
    }

    @Test
    fun advertFitsTheScanResponseBudget() {
        // Scan response: 2 (AD header) + 2 (company id) + game(1) + id(2) + nick must be <= 31.
        val ad = Codec.encodeAdvert(GameIds.PADDLE, 0xFFFF, "นักเล่นเกมสุดหล่อมาก")
        assertTrue("advert ${ad.size}B", 2 + 2 + ad.size <= 31)
        val longest = Codec.encodeAdvert(GameIds.PADDLE, 0xFFFF, "a".repeat(Proto.MAX_NICK_BYTES))
        assertEquals(31, 2 + 2 + longest.size)
    }

    @Test
    fun advertRejectsEmptyMissingOrTruncatedData() {
        assertNull(Codec.decodeAdvert(null))
        assertNull(Codec.decodeAdvert(byteArrayOf()))
        assertNull(Codec.decodeAdvert(byteArrayOf(GameIds.PADDLE.toByte())))                 // no id
        assertNull(Codec.decodeAdvert(byteArrayOf(GameIds.PADDLE.toByte(), 1)))              // half an id
    }

    @Test
    fun advertWithNoNicknameStillIdentifiesTheGameAndPlayer() {
        assertEquals(
            Codec.Advert(GameIds.PADDLE, 7, ""),
            Codec.decodeAdvert(Codec.encodeAdvert(GameIds.PADDLE, 7, "")),
        )
    }
}
