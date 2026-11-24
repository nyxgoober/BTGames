package us.crafties.btgames.games.keymash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymashContentTest {

    // ---- shared prompts -----------------------------------------------------------------

    @Test fun sameSeedSameWords() {
        val a = KeymashContent.targetsFor(KeymashMode.WORDS, 42L)
        val b = KeymashContent.targetsFor(KeymashMode.WORDS, 42L)
        assertEquals(KeymashContent.WORD_COUNT, a.size)
        assertEquals(a, b)
        assertTrue(a.all { it in KeymashContent.WORDS })
    }

    @Test fun differentSeedsDiffer() {
        val a = KeymashContent.targetsFor(KeymashMode.WORDS, 1L)
        val b = KeymashContent.targetsFor(KeymashMode.WORDS, 2L)
        assertTrue("two shuffles should not be identical", a != b)
    }

    @Test fun sentencesAreUniqueAndKnown() {
        val s = KeymashContent.targetsFor(KeymashMode.SENTENCE, 7L)
        assertEquals(KeymashContent.SENTENCE_COUNT, s.size)
        assertEquals(s.size, s.toSet().size)
        assertTrue(s.all { it in KeymashContent.SENTENCES })
    }

    // ---- scoring --------------------------------------------------------------------------

    @Test fun matchesCountsPositionally() {
        assertEquals(5, KeymashContent.matches("hello", "hello"))
        assertEquals(4, KeymashContent.matches("hell", "hello"))
        assertEquals(0, KeymashContent.matches("xyz", "hello"))
        assertEquals(4, KeymashContent.matches("hxllo!", "hello"))
        assertEquals(5, KeymashContent.matches("helloworld", "hello"))
    }

    @Test fun wrongWordsEarnLessCreditThanRightOnes() {
        assertEquals(6, KeymashContent.wordCredit("hello", "hello"))
        assertEquals(2, KeymashContent.wordCredit("hell", "hello"))
        assertEquals(0, KeymashContent.wordCredit("xyz", "hello"))
    }

    @Test fun prefixMatchesStopsAtFirstError() {
        assertEquals(3, KeymashContent.prefixMatches("helXlo", "hello"))
        assertEquals(0, KeymashContent.prefixMatches("", "hello"))
        assertEquals(5, KeymashContent.prefixMatches("hello!", "hello"))
    }

    @Test fun sentenceAcceptanceIgnoresCaseAndFinalDot() {
        val target = "The quick brown fox jumps over the lazy dog."
        assertTrue(KeymashContent.sentenceMatches(target, target))
        assertTrue(KeymashContent.sentenceMatches("the quick brown fox jumps over the lazy dog.", target))
        assertTrue(KeymashContent.sentenceMatches("The quick brown fox jumps over the lazy dog", target))
        assertTrue(KeymashContent.sentenceMatches("the quick brown fox jumps over the lazy dog", target))
        assertTrue(!KeymashContent.sentenceMatches("The quick brown fox", target))
        assertTrue(!KeymashContent.sentenceMatches("The quick brown fox jumps over the lazy dog!", target))
        assertTrue(!KeymashContent.sentenceMatches("The quick brown fox jumps over the lazy dog..", target))
        assertTrue(!KeymashContent.sentenceMatches("", target))
    }

    @Test fun sentencePrefixIgnoresCase() {
        assertEquals(3, KeymashContent.sentencePrefixMatches("helXlo", "hello"))
        assertEquals(5, KeymashContent.sentencePrefixMatches("HELLO", "hello"))
        assertEquals(0, KeymashContent.sentencePrefixMatches("", "hello"))
    }

    @Test fun wpmIsCorrectCharsPerFivePerMinute() {
        // 250 correct chars in one minute is 50 wpm.
        assertEquals(50.0, KeymashContent.wpmOf(250, 60_000L), 0.001)
        assertEquals(0.0, KeymashContent.wpmOf(0, 60_000L), 0.0)
        assertEquals(0.0, KeymashContent.wpmOf(10, 0L), 0.0)
    }

    @Test fun accuracyIsCorrectOverTyped() {
        assertEquals(0.9, KeymashContent.accuracyOf(90, 100), 0.001)
        assertEquals(0.0, KeymashContent.accuracyOf(0, 0), 0.0)
        assertEquals(1.0, KeymashContent.accuracyOf(120, 100), 0.0) // clamped, never above 1
    }

    @Test fun perfectRunScoresWpmTimesHundred() {
        // 300 correct chars in 60 s = 60 wpm at 100% -> 6000.
        assertEquals(6000, KeymashContent.scoreOf(300, 300, 60_000L))
    }

    @Test fun errorsDragTheScoreDownTwice() {
        // Same speed, half the keystrokes correct: wpm halves AND accuracy halves.
        val perfect = KeymashContent.scoreOf(300, 300, 60_000L)
        val sloppy = KeymashContent.scoreOf(150, 300, 60_000L)
        assertEquals(perfect / 4, sloppy)
    }

    @Test fun emptyRunScoresZero() {
        assertEquals(0, KeymashContent.scoreOf(0, 0, 60_000L))
    }

    // ---- banks are phone-friendly -----------------------------------------------------------

    @Test fun wordsAreLowercaseAndPlain() {
        assertTrue(KeymashContent.WORDS.size >= 100)
        assertTrue(KeymashContent.WORDS.all { w -> w.all { it.isLowerCase() } })
    }

    @Test fun sentencesAreShortEnoughToType() {
        assertTrue(KeymashContent.SENTENCES.size >= 10)
        assertTrue(KeymashContent.SENTENCES.all { it.length <= 60 })
    }
}
