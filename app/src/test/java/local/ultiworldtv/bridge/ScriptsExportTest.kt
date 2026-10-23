package local.ultiworldtv.bridge

import java.io.File
import org.junit.Test

/**
 * Writes each page script to app/build/scripts/ wrapped the way WebBridge.evaluate wraps it, so it can be
 * syntax-checked (`node --check`) and pasted into a browser console on ultiworld.com to try against the live site.
 */
class ScriptsExportTest {
    @Test
    fun exportScripts() {
        val dir = File("build/scripts").apply { mkdirs() }
        val scripts = mapOf(
            "is_challenge" to Scripts.IS_CHALLENGE,
            "is_logged_in" to Scripts.IS_LOGGED_IN,
            "logout_url" to Scripts.LOGOUT_URL,
            "library" to Scripts.LIBRARY,
            "scores" to Scripts.SCORES,
            "collection" to Scripts.collection("https://ultiworld.com/video/?event=199279#filtered"),
            "playback" to Scripts.PLAYBACK,
            "heartbeat_true" to Scripts.heartbeat(true),
            "remote_friendly_form" to Scripts.REMOTE_FRIENDLY_FORM
        )
        scripts.forEach { (name, body) -> File(dir, "$name.js").writeText("(async () => {\n$body\n})();\n") }
    }
}
