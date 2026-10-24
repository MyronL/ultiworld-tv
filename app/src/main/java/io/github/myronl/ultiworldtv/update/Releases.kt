package io.github.myronl.ultiworldtv.update

import org.json.JSONObject

/** A published release: its version, without the tag's "v", and where its APK is. */
data class Release(val version: String, val apkUrl: String)

/** Reads GitHub's answer about the latest release. Kept free of Android so it is unit-tested. */
object Releases {
    const val LATEST_URL = "https://api.github.com/repos/MyronL/ultiworld-tv/releases/latest"

    /** What the release workflow names the APK it publishes. */
    const val APK_NAME = "ultiworld-tv.apk"

    /** The release in GitHub's `releases/latest` JSON, or null when it has no APK or no usable version. */
    fun parse(json: String): Release? {
        val o = JSONObject(json)
        val version = o.optString("tag_name").removePrefix("v").takeIf { parts(it) != null } ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == APK_NAME }
        val url = apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return null
        return Release(version, url)
    }

    /** Whether [version] comes after [current], comparing numbers: "0.10" is newer than "0.9". */
    fun isNewer(version: String, current: String): Boolean {
        val a = parts(version) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int>? = version.split('.').map { it.toIntOrNull() ?: return null }
}
