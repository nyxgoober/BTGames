package us.crafties.btgames.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubUpdateCheckTest {

    private fun release(tag: String, apk: String? = null): String {
        val assets = if (apk == null) "" else "{\"browser_download_url\":\"$apk\"}"
        return "{\"tag_name\":\"$tag\",\"assets\":[$assets]}"
    }

    @Test fun sameCodenameMeansUpToDate() {
        assertEquals(UpdateStatus.UpToDate, GithubUpdateCheck.parse(release("Erbium"), "Erbium"))
    }

    @Test fun differentCodenameLinksTheApkAsset() {
        val apk = "https://github.com/nyxgoober/BTGames/releases/download/Fermium/app-release.apk"
        assertEquals(UpdateStatus.Available(apk), GithubUpdateCheck.parse(release("Fermium", apk), "Erbium"))
    }

    @Test fun differentCodenameWithoutAnAssetLinksTheReleasesPage() {
        assertEquals(UpdateStatus.Available(GITHUB_RELEASES_PAGE), GithubUpdateCheck.parse(release("Fermium"), "Erbium"))
    }

    @Test fun noTagOrMalformedReplyMeansFailed() {
        assertEquals(UpdateStatus.Failed, GithubUpdateCheck.parse("", "Erbium"))
        assertEquals(UpdateStatus.Failed, GithubUpdateCheck.parse("404: Not Found", "Erbium"))
        assertEquals(UpdateStatus.Failed, GithubUpdateCheck.parse("""{"tag_name":123}""", "Erbium"))
    }

    @Test fun checkUsesTheInjectedFetcher() {
        assertEquals(UpdateStatus.UpToDate, GithubUpdateCheck.check("Erbium") { release("Erbium") })
        assertEquals(UpdateStatus.Failed, GithubUpdateCheck.check("Erbium") { null })
        assertEquals(UpdateStatus.Failed, GithubUpdateCheck.check("Erbium") { throw RuntimeException("boom") })
    }

    @Test fun contractConstants() {
        assertEquals("https://api.github.com/repos/nyxgoober/BTGames/releases/latest", GITHUB_API_URL)
        assertEquals("https://github.com/nyxgoober/BTGames/releases/latest", GITHUB_RELEASES_PAGE)
        assertTrue(GITHUB_API_URL.startsWith("https://"))
        assertTrue(GITHUB_RELEASES_PAGE.startsWith("https://"))
    }
}
