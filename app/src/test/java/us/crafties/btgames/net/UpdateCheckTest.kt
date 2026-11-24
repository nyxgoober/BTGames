package us.crafties.btgames.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    private fun json(code: Int) = """{"packageName":"us.crafties.btgames","suggestedVersionCode":$code,"packages":[]}"""

    @Test fun olderOrSameVersionMeansUpToDate() {
        assertEquals(UpdateStatus.UpToDate, UpdateCheck.parse(json(2), 2))
        assertEquals(UpdateStatus.UpToDate, UpdateCheck.parse(json(1), 2))
    }

    @Test fun newerVersionOpensTheFDRoidListing() {
        assertEquals(UpdateStatus.Available(FDROID_PAGE), UpdateCheck.parse(json(3), 2))
    }

    @Test fun noListingOrMalformedReplyMeansFailed() {
        assertEquals(UpdateStatus.Failed, UpdateCheck.parse("", 2))
        assertEquals(UpdateStatus.Failed, UpdateCheck.parse("404: Not Found", 2))
        assertEquals(UpdateStatus.Failed, UpdateCheck.parse("""{"suggestedVersionCode":"new"}""", 2))
    }

    @Test fun checkUsesTheInjectedFetcher() {
        assertEquals(UpdateStatus.UpToDate, UpdateCheck.check(2) { json(2) })
        assertEquals(UpdateStatus.Available(FDROID_PAGE), UpdateCheck.check(2) { json(3) })
        assertEquals(UpdateStatus.Failed, UpdateCheck.check(2) { null })
        assertEquals(UpdateStatus.Failed, UpdateCheck.check(2) { "garbage" })
        assertEquals(UpdateStatus.Failed, UpdateCheck.check(2) { throw RuntimeException("boom") })
    }

    @Test fun contractConstants() {
        assertEquals("https://f-droid.org/api/v1/packages/us.crafties.btgames", UPDATE_URL)
        assertEquals("https://f-droid.org/packages/us.crafties.btgames/", FDROID_PAGE)
        assertTrue(UPDATE_URL.startsWith("https://"))
        assertTrue(FDROID_PAGE.startsWith("https://"))
    }
}
