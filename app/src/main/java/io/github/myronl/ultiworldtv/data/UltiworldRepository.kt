package io.github.myronl.ultiworldtv.data

import io.github.myronl.ultiworldtv.bridge.BridgeException
import io.github.myronl.ultiworldtv.bridge.ChallengeRequiredException
import io.github.myronl.ultiworldtv.bridge.Scripts
import io.github.myronl.ultiworldtv.bridge.WebBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            // Straight after Cloudflare's check the login page may still be loading, so wait for the form, then
            // give it one fresh load, before deciding the page has changed.
            val form = loginForm() ?: run {
                load(LOGIN_URL)
                loginForm()
            }
            when {
                form?.optBoolean("hasForm") != true ->
                    return@session LoginResult.Failed("Ultiworld's login page has changed, so the app can't log in right now.")

                form.optBoolean("humanCheck") ->
                    return@session LoginResult.Failed(
                        "Ultiworld's login page wants a human check, which the app can't do. Try again later."
                    )
            }
            // A wrong password stays on the login page with a message (see Scripts.submitLogin).
            val inPage = submitAndAwait(Scripts.submitLogin(username, password), inPageAnswer = Scripts.LOGIN_ERROR) as? String
            if (inPage != null) return@session LoginResult.Failed(inPage)
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
            LoginResult.Challenge
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
        // Leave the video page, so its scripts stop running while the app plays the stream (its check-ins are
        // blocked anyway, see WebBridge). Playback can start while this loads.
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

    /** The login form's state once the page shows it, asked for a few seconds; null if it never does. */
    private suspend fun WebBridge.loginForm(): JSONObject? {
        repeat(LOGIN_FORM_TRIES) {
            val form = try {
                evaluate(Scripts.LOGIN_FORM_STATE) as? JSONObject
            } catch (e: BridgeException) {
                // A page mid-navigation may not answer.
                null
            }
            if (form?.optBoolean("hasForm") == true) return form
            delay(500)
        }
        return null
    }

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
        private const val LOGIN_FORM_TRIES = 10
        const val VIDEO_LIBRARY_URL = "https://ultiworld.com/video/"
        const val LOGIN_URL = "https://ultiworld.com/wordpress/wp-login.php?redirect_to=https%3A%2F%2Fultiworld.com%2Fvideo%2F"
    }
}
