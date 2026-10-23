package local.ultiworldtv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ultiworldtv.bridge.Scripts
import local.ultiworldtv.bridge.WebBridge

/** Everything the app asks of Ultiworld, run through the one [WebBridge] page. */
class UltiworldRepository(private val bridge: WebBridge, private val scope: CoroutineScope) {

    /** Opens the video library and reports whether this TV is logged in. */
    suspend fun start(): Boolean = bridge.session {
        load(VIDEO_LIBRARY_URL)
        evaluate(Scripts.IS_LOGGED_IN) == true || hasLoginCookie()
    }

    suspend fun library(): Library = bridge.session {
        load(VIDEO_LIBRARY_URL)
        Parsing.library(evaluate(Scripts.LIBRARY))
    }

    suspend fun scores(): Scores = bridge.session {
        ensureOnSite()
        Parsing.scores(evaluate(Scripts.SCORES))
    }

    suspend fun collection(url: String): List<VideoCard> = bridge.session {
        ensureOnSite()
        Parsing.cards(evaluate(Scripts.collection(url), timeoutMs = 30_000))
    }

    suspend fun playback(videoUrl: String): PlaybackResult {
        val result = bridge.session {
            load(videoUrl)
            Parsing.playback(evaluate(Scripts.PLAYBACK, timeoutMs = 30_000))
        }
        // Leave the video page: its own script would otherwise keep sending "not streaming" check-ins
        // alongside the app's "streaming" ones. Playback can start while this loads.
        scope.launch { bridge.session { runCatching { load(VIDEO_LIBRARY_URL) } } }
        return result
    }

    suspend fun heartbeat(streaming: Boolean): HeartbeatResult = bridge.session {
        ensureOnSite()
        Parsing.heartbeat(evaluate(Scripts.heartbeat(streaming)))
    }

    /** Sends a final "not streaming" check-in even when the caller is being cancelled (leaving the player). */
    fun releaseStreamingSlot() {
        scope.launch {
            withContext(NonCancellable) { runCatching { heartbeat(streaming = false) } }
        }
    }

    suspend fun signOut() {
        bridge.session {
            runCatching {
                ensureOnSite()
                (evaluate(Scripts.LOGOUT_URL) as? String)?.let { load(it) }
            }
            clearSession()
        }
    }

    private suspend fun WebBridge.ensureOnSite() {
        val url = currentUrl.value
        if (url == null || !url.startsWith(WebBridge.ORIGIN) || isChallengePage()) load(VIDEO_LIBRARY_URL)
    }

    companion object {
        const val VIDEO_LIBRARY_URL = "https://ultiworld.com/video/"
        const val LOGIN_URL = "https://ultiworld.com/wordpress/wp-login.php?redirect_to=https%3A%2F%2Fultiworld.com%2Fvideo%2F"
    }
}
