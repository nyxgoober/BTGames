package us.crafties.btgames.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WordingTest {

    @Test
    fun aNameTakesTheSForm() {
        assertEquals("Sam wins", Wording.subject("Sam", "win", "wins"))
        assertEquals("Sam wants", Wording.subject("Sam", "want", "wants"))
    }

    @Test
    fun noNameFallsBackToTheyWithThePluralForm() {
        assertEquals("They win", Wording.subject("", "win", "wins"))
        assertEquals("They want", Wording.subject("   ", "want", "wants"))
    }

    @Test
    fun aNameEndingInSStillTakesTheSForm() {
        // "James wins": the verb agrees with a single person, whatever the name ends in.
        assertEquals("James wins", Wording.subject("James", "win", "wins"))
    }

    @Test
    fun surroundingSpacesInANameAreIgnored() {
        assertEquals("Sam wins", Wording.subject(" Sam ", "win", "wins"))
    }
}
