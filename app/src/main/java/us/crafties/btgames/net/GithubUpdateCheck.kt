package us.crafties.btgames.net

const val GITHUB_API_URL = "https://api.github.com/repos/nyxgoober/BTGames/releases/latest"

const val GITHUB_RELEASES_PAGE = "https://github.com/nyxgoober/BTGames/releases/latest"

object GithubUpdateCheck {
    private val TAG_NAME = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
    private val APK_URL = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"")

    fun parse(body: String, myVersionName: String): UpdateStatus {
        val tag = TAG_NAME.find(body)?.groupValues?.get(1) ?: return UpdateStatus.Failed
        if (tag == myVersionName) return UpdateStatus.UpToDate
        val url = APK_URL.find(body)?.groupValues?.get(1) ?: GITHUB_RELEASES_PAGE
        return UpdateStatus.Available(url)
    }

    fun fetchResponse(): String? = httpGet(GITHUB_API_URL, "BTGames")

    fun check(myVersionName: String, fetch: () -> String? = ::fetchResponse): UpdateStatus {
        val body = try {
            fetch()
        } catch (_: Exception) {
            null
        } ?: return UpdateStatus.Failed
        return parse(body, myVersionName)
    }
}
