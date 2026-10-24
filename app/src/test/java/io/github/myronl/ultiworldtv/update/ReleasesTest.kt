package io.github.myronl.ultiworldtv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private val apk = "https://github.com/MyronL/ultiworld-tv/releases/download/v0.2/ultiworld-tv.apk"

    private fun latest(tag: String, vararg assets: Pair<String, String>): String {
        val list = assets.joinToString(",") { (name, url) -> """{"name":"$name","browser_download_url":"$url"}""" }
        return """{"tag_name":"$tag","draft":false,"assets":[$list]}"""
    }

    @Test
    fun `latest release is its version and the APK among its files`() {
        val json = latest("v0.2", "ultiworld-tv.apk.sha256" to "$apk.sha256", "ultiworld-tv.apk" to apk)
        assertEquals(Release("0.2", apk), Releases.parse(json))
    }

    @Test
    fun `a release without the APK or a numeric version is ignored`() {
        assertNull(Releases.parse(latest("v0.2", "ultiworld-tv.apk.sha256" to "$apk.sha256")))
        assertNull(Releases.parse(latest("v0.2", "ultiworld-tv.apk" to "http://example.com/ultiworld-tv.apk")))
        assertNull(Releases.parse(latest("nightly", "ultiworld-tv.apk" to apk)))
        assertNull(Releases.parse(latest("v0.2-beta", "ultiworld-tv.apk" to apk)))
        assertNull(Releases.parse("""{"message":"Not Found"}"""))
    }

    @Test
    fun `versions compare as numbers`() {
        assertTrue(Releases.isNewer("0.2", "0.1"))
        assertTrue(Releases.isNewer("0.10", "0.9"))
        assertTrue(Releases.isNewer("1.0", "0.9.9"))
        assertTrue(Releases.isNewer("0.2.1", "0.2"))
        assertFalse(Releases.isNewer("0.2", "0.2"))
        assertFalse(Releases.isNewer("0.2.0", "0.2"))
        assertFalse(Releases.isNewer("0.1", "0.2"))
        assertFalse(Releases.isNewer("", "0.1"))
        assertFalse(Releases.isNewer("0.2", "0.1-dev"))
    }
}
