package us.crafties.btgames.games.keymash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeymashCodecTest {

    @Test fun choiceRoundTrips() {
        val m = KeymashCodec.decode(KeymashCodec.encodeChoice(2, KeymashMode.WORDS, 123456789L))
        assertEquals(KeymashCodec.Msg.Choice(2, KeymashMode.WORDS, 123456789L), m)
    }

    @Test fun choiceRejectsUnknownModes() {
        val raw = KeymashCodec.encodeChoice(0, KeymashMode.SENTENCE, 1L).copyOf()
        raw[2] = 9
        assertNull(KeymashCodec.decode(raw))
    }

    @Test fun updateRoundTrips() {
        val m = KeymashCodec.decode(KeymashCodec.encodeUpdate(1, false, 7, 25, 42, 3800))
        assertEquals(KeymashCodec.Msg.Update(1, false, 7, 25, 42, 3800), m)
        val done = KeymashCodec.decode(KeymashCodec.encodeUpdate(1, true, 25, 25, 51, 4900))
        assertEquals(KeymashCodec.Msg.Update(1, true, 25, 25, 51, 4900), done)
    }

    @Test fun updateRejectsImpossibleProgress() {
        assertNull(KeymashCodec.decode(KeymashCodec.encodeUpdate(0, false, 26, 25, 10, 100)))
        assertNull(KeymashCodec.decode(KeymashCodec.encodeUpdate(0, false, 0, 0, 10, 100)))
    }

    @Test fun rematchRoundTrips() {
        assertEquals(KeymashCodec.Msg.Rematch(3), KeymashCodec.decode(KeymashCodec.encodeRematch(3)))
        assertNull(KeymashCodec.decode(byteArrayOf(3)))
    }

    @Test fun garbageDecodesToNull() {
        assertNull(KeymashCodec.decode(byteArrayOf()))
        assertNull(KeymashCodec.decode(byteArrayOf(9, 1, 2, 3)))
        assertNull(KeymashCodec.decode(byteArrayOf(1, 2)))
    }
}
