package us.crafties.btgames.net

/** F-Droid's public package API for this app. It reports the suggested version code. */
const val UPDATE_URL = "https://f-droid.org/api/v1/packages/us.crafties.btgames"

/** The app's F-Droid listing, opened when an update is available. */
const val FDROID_PAGE = "https://f-droid.org/packages/us.crafties.btgames/"

/**
 * The latest update-check outcome, shown in Settings. F-Droid's client handles the actual install;
 * this check only tells you when a newer build is in the repo.
 */
sealed interface UpdateStatus {
    /** No check has run yet this launch. */
    data object Unknown : UpdateStatus

    /** A check is in flight. */
    data object Checking : UpdateStatus

    /** The repo's suggested version is not newer than this build. */
    data object UpToDate : UpdateStatus

    /** A release was found; [downloadUrl] is the F-Droid listing or the GitHub APK asset. */
    data class Available(val downloadUrl: String) : UpdateStatus

    /** Offline, not listed yet, server error, or an answer we did not understand. */
    data object Failed : UpdateStatus
}

/**
 * Release update checks against F-Droid's public package API. No device data is sent: the URL is
 * fixed, so this is a plain GET of free, public metadata. Pure except for [fetchResponse], which
 * is injectable so tests never touch the network.
 */
object UpdateCheck {
    /** Matches the only field we care about in the JSON reply. */
    private val VERSION_CODE = Regex("\"suggestedVersionCode\"\\s*:\\s*(\\d+)")

    /**
     * Compare F-Droid's suggested version with this build's [myVersionCode]. An absent or malformed
     * reply (for example, before the app is listed) is a [Failed] check.
     */
    fun parse(body: String, myVersionCode: Int): UpdateStatus {
        val suggested = VERSION_CODE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return UpdateStatus.Failed
        return if (suggested > myVersionCode) UpdateStatus.Available(FDROID_PAGE) else UpdateStatus.UpToDate
    }

    /** Blocking GET of the package API. Callers must stay off the main thread. */
    fun fetchResponse(): String? = httpGet(UPDATE_URL)

    /** One full check: fetch the response, then [parse] it. Any failure is [UpdateStatus.Failed]. */
    fun check(myVersionCode: Int, fetch: () -> String? = ::fetchResponse): UpdateStatus {
        val body = try {
            fetch()
        } catch (_: Exception) {
            null
        } ?: return UpdateStatus.Failed
        return parse(body, myVersionCode)
    }
}
