package local.ultiworldtv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ultiworldtv.bridge.BridgeException
import local.ultiworldtv.bridge.ChallengeRequiredException
import local.ultiworldtv.bridge.Scripts
import local.ultiworldtv.bridge.WebBridge
import org.json.JSONObject

/** Everything the app asks of Ultiworld, run through the one [WebBridge] page. */
class UltiworldRepository(private val bridge: WebBridge, private val scope: CoroutineScope) {

    /** Opens the video library and reports whether this TV is logged in. */
    suspend fun start(): Boolean = bridge.session {
        load(VIDEO_LIBRARY_URL)
        evaluate(Scripts.IS_LOGGED_IN) == true || hasLoginCookie()
    }

    /**
     * Logs in through Ultiworld's own login form: the app fills it in and submits it in the WebView, so it is
     * the site's normal login. Only the resulting cookie is kept; the password is never stored.
     */
    suspend fun logIn(username: String, password: String): LoginResult = bridge.session {
        try {
            load(LOGIN_URL)
            val form = evaluate(Scripts.LOGIN_FORM_STATE) as? JSONObject
            when {
                form?.optBoolean("hasForm") != true ->
                    return@session LoginResult.NeedsWebPage("Ultiworld's login page looks different than the app expects.")
                form.optBoolean("humanCheck") ->
                    return@session LoginResult.NeedsWebPage("Ultiworld wants to confirm you're human on its own login page.")
            }
            evaluateAndAwaitNavigation(Scripts.submitLogin(username, password))
            if (currentUrl.value.orEmpty().contains("wp-login.php")) {
                val message = evaluate(Scripts.LOGIN_ERROR) as? String
                return@session LoginResult.Failed(message?.takeIf { it.isNotBlank() } ?: "Ultiworld didn't accept that email and password.")
            }
            if (hasLoginCookie() || evaluate(Scripts.IS_LOGGED_IN) == true) {
                flushCookies()
                LoginResult.Success
            } else {
                LoginResult.Failed("The login didn't go through. Try again.")
            }
        } catch (e: ChallengeRequiredException) {
            LoginResult.NeedsWebPage("Ultiworld is checking this TV on its own page.")
        } catch (e: BridgeException) {
            LoginResult.Failed(e.message ?: "Couldn't reach Ultiworld.")
        }
    }

    suspend fun library(): Library = bridge.session { Parsing.library(evaluateFetched(Scripts.LIBRARY)) }

    suspend fun scores(): Scores = bridge.session {
        ensureOnSite()
        Parsing.scores(evaluate(Scripts.SCORES))
    }

    suspend fun collection(url: String): List<VideoCard> = bridge.session {
        // Links come from scraped pages and search hits: only follow ones that stay on Ultiworld.
        if (!WebBridge.isOnSite(url)) return@session emptyList()
        Parsing.cards(evaluateFetched(Scripts.collection(url), timeoutMs = 30_000))
    }

    /** Library videos matching [query], best match first, from the site's own search. */
    suspend fun search(query: String): List<VideoCard> = bridge.session {
        ensureOnSite()
        Parsing.cards(evaluate(Scripts.search(query)))
    }

    suspend fun playback(videoUrl: String): PlaybackResult {
        if (!WebBridge.isOnSite(videoUrl)) return PlaybackResult.Missing
        val result = bridge.session {
            var r = readPlayback(videoUrl)
            // A logged-in TV that sees the paywall is often just early: the site's check hadn't finished.
            // One fresh load settles it before telling you the plan doesn't include the video.
            if (r is PlaybackResult.Locked && hasLoginCookie()) r = readPlayback(videoUrl)
            r
        }
        // Leave the video page: its own script would otherwise keep sending "not streaming" check-ins
        // alongside the app's "streaming" ones. Playback can start while this loads.
        scope.launch { bridge.session { runCatching { load(VIDEO_LIBRARY_URL) } } }
        return result
    }

    private suspend fun WebBridge.readPlayback(videoUrl: String): PlaybackResult {
        load(videoUrl)
        return Parsing.playback(evaluate(Scripts.playback(loggedIn = hasLoginCookie()), timeoutMs = 30_000))
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

    /**
     * Forgets the login on this TV without asking the site, for when Ultiworld already ended the session
     * (too many devices). Otherwise the leftover cookie would look logged in on the next launch.
     */
    suspend fun forgetLogin() = bridge.session { clearSession() }

    private suspend fun WebBridge.ensureOnSite() {
        val url = currentUrl.value
        if (!WebBridge.isOnSite(url) || isChallengePage()) load(VIDEO_LIBRARY_URL)
    }

    /**
     * Runs a script that fetches a page instead of navigating to it. When Cloudflare wants to check the TV first,
     * a real page load runs the check (or puts it on screen, via [ChallengeRequiredException]), then it tries again.
     */
    private suspend fun WebBridge.evaluateFetched(script: String, timeoutMs: Long = 20_000): Any? {
        ensureOnSite()
        val v = evaluate(script, timeoutMs)
        if (!Parsing.isChallenge(v)) return v
        load(VIDEO_LIBRARY_URL)
        return evaluate(script, timeoutMs).also { if (Parsing.isChallenge(it)) throw ChallengeRequiredException() }
    }

    companion object {
        const val VIDEO_LIBRARY_URL = "https://ultiworld.com/video/"
        const val LOGIN_URL = "https://ultiworld.com/wordpress/wp-login.php?redirect_to=https%3A%2F%2Fultiworld.com%2Fvideo%2F"
    }
}
