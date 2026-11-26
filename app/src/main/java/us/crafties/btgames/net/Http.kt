package us.crafties.btgames.net

import java.net.HttpURLConnection
import java.net.URI

internal fun httpGet(url: String, userAgent: String? = null): String? {
    val conn = try {
        URI(url).toURL().openConnection() as? HttpURLConnection
    } catch (_: Exception) {
        null
    } ?: return null
    try {
        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
        return conn.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        return null
    } finally {
        conn.disconnect()
    }
}
