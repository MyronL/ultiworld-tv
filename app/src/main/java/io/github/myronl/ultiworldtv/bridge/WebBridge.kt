package io.github.myronl.ultiworldtv.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Ultiworld is behind Cloudflare and still asks a human to confirm after [WebBridge.load] waited. */
class ChallengeRequiredException : Exception("Cloudflare wants a human check")

/** A script run in the page threw, or never answered. */
class BridgeException(message: String) : Exception(message)

/**
 * One WebView kept on an ultiworld.com page for the life of the app.
 *
 * Ultiworld sits behind Cloudflare, which turns away plain HTTP clients, and its login, paywall and
 * device-limit checks all live in the site's own JavaScript. Running every request from inside a real
 * browser page on the site's own origin means the app talks to Ultiworld exactly the way the website
 * does: same cookies, same Cloudflare clearance, same endpoints.
 *
 * The WebView stays VISIBLE behind the Compose UI (a hidden page can be throttled, and Cloudflare's
 * check runs slower for it). It is only brought to the front for a Cloudflare prompt.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebBridge(context: Context) {
    val webView: WebView = WebView(context)

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pageLoads = MutableStateFlow(0)
    private val mutex = Mutex()

    private val _currentUrl = MutableStateFlow<String?>(null)

    /** The main frame's URL after each finished page load. */
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    /** Called on the main thread after every main-frame page load. */
    var onPageFinished: ((url: String) -> Unit)? = null

    /**
     * Called on the main thread when the page's renderer process died (low memory, usually). The WebView can't
     * be used again after that, so the app has to start over with a new one.
     */
    var onRendererGone: (() -> Unit)? = null

    /** Why the main frame's latest load failed (no network, say), or null when it loaded. */
    @Volatile
    private var loadError: String? = null

    val userAgent: String get() = webView.settings.userAgentString

    init {
        WebView.setWebContentsDebuggingEnabled(io.github.myronl.ultiworldtv.BuildConfig.DEBUG)
        webView.setBackgroundColor(Color.BLACK)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // A TV-sized page; the login form is easier to read without the phone layout.
            textZoom = 125
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Only frames on ultiworld.com get the bridge object, so ad or embed iframes cannot answer for the page.
            WebViewCompat.addWebMessageListener(webView, JS_OBJECT, setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) message.data?.let(::deliver)
            }
        } else {
            // Older WebViews: this object is in every frame on every site, so only answers while the page is Ultiworld count.
            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun postMessage(data: String) {
                        if (isOnSite(startedUrl)) deliver(data)
                    }
                },
                JS_OBJECT
            )
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                startedUrl = url
                loadError = null
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadError = error.description?.toString() ?: "error ${error.errorCode}"
            }

            override fun onPageFinished(view: WebView, url: String) {
                startedUrl = url
                _currentUrl.value = url
                pageLoads.value += 1
                onPageFinished?.invoke(url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (isBlocked(request.url)) EMPTY_RESPONSE() else null

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()})")
                pending.values.forEach { it.completeExceptionally(BridgeException("The page stopped responding")) }
                onRendererGone?.invoke()
                return true
            }
        }
    }

    /** Runs [block] with exclusive use of the page, so a navigation never lands in the middle of another call. */
    suspend fun <T> session(block: suspend WebBridge.() -> T): T = mutex.withLock { block() }

    /**
     * Hands the page to the person at the remote until cancelled: while it's on screen for Cloudflare's
     * check, every [session] waits, so background work (a check-in as the player closes, a
     * feed loading) can't navigate away mid-form. Opens [url] first, once any call already running finishes.
     */
    suspend fun holdForUser(url: String? = null): Nothing = mutex.withLock {
        if (url != null) withContext(Dispatchers.Main) { webView.loadUrl(url) }
        awaitCancellation()
    }

    /**
     * Navigates and waits until a real Ultiworld page (not Cloudflare's "Just a moment...") has loaded.
     * Throws [ChallengeRequiredException] when Cloudflare is still asking after [challengeWaitMs].
     */
    suspend fun load(url: String, timeoutMs: Long = 30_000, challengeWaitMs: Long = 20_000) {
        val before = pageLoads.value
        withContext(Dispatchers.Main) { webView.loadUrl(url) }
        awaitPageLoad(before, timeoutMs, challengeWaitMs)
    }

    /**
     * Runs [script], which submits a form, and waits for the form's answer. A form that leads to a new page
     * is waited for the same way [load] waits, and this returns null. A form that answers on the page itself
     * (a script that checks it in the background, say) returns the first non-null thing [inPageAnswer] reads there.
     */
    suspend fun submitAndAwait(script: String, inPageAnswer: String, timeoutMs: Long = 30_000, challengeWaitMs: Long = 20_000): Any? {
        val before = pageLoads.value
        evaluate(script)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (withTimeoutOrNull(500) { pageLoads.first { it > before } } == null) {
            // Asked again every half second. A page mid-navigation may not answer; the next page load ends the wait.
            val answer = try {
                evaluate(inPageAnswer, timeoutMs = 2_000)
            } catch (e: BridgeException) {
                null
            }
            if (answer != null) return answer
            if (System.currentTimeMillis() > deadline) throw BridgeException("Ultiworld took too long to load")
        }
        settle(challengeWaitMs)
        return null
    }

    private suspend fun awaitPageLoad(after: Int, timeoutMs: Long, challengeWaitMs: Long) {
        withTimeoutOrNull(timeoutMs) { pageLoads.first { it > after } }
            ?: throw BridgeException("Ultiworld took too long to load")
        settle(challengeWaitMs)
    }

    /** After a page load: fails for the WebView's own error page, and waits out a Cloudflare check. */
    private suspend fun settle(challengeWaitMs: Long) {
        // No network, DNS failure and the like: the WebView shows its own error page, which is no Cloudflare check.
        loadError?.let { throw BridgeException("Couldn't reach Ultiworld ($it). Check the TV's internet connection.") }
        val deadline = System.currentTimeMillis() + challengeWaitMs
        while (isChallengePage()) {
            if (System.currentTimeMillis() > deadline) throw ChallengeRequiredException()
            delay(750)
        }
    }

    /**
     * Whether the page on screen is Cloudflare's check rather than Ultiworld. Asked through the WebView itself
     * rather than [evaluate], so it gets an answer from any page, including the WebView's own error page.
     */
    suspend fun isChallengePage(): Boolean {
        val answer = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("(() => { ${Scripts.IS_CHALLENGE} })()") { answer.complete(it) }
        }
        // No answer is usually a page mid-redirect after the check, so treat it as still checking.
        return when (withTimeoutOrNull(5_000) { answer.await() }) {
            "false" -> false
            else -> true
        }
    }

    /**
     * Runs [body] as the body of an async function in the page and returns what it returns, decoded from
     * JSON: a JSONObject, JSONArray, String, Number, Boolean, or null. [body] uses `return` and may `await`.
     */
    suspend fun evaluate(body: String, timeoutMs: Long = 20_000): Any? {
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<String>()
        pending[id] = answer
        val script = """
            (async () => {
              const __id = ${JSONObject.quote(id)};
              const __send = (m) => $JS_OBJECT.postMessage(JSON.stringify(m));
              try {
                const v = await (async () => { $body })();
                __send({ id: __id, ok: true, v: v === undefined ? null : v });
              } catch (e) {
                __send({ id: __id, ok: false, e: String((e && e.message) || e) });
              }
            })();
        """.trimIndent()
        try {
            withContext(Dispatchers.Main) { webView.evaluateJavascript(script, null) }
            val raw = withTimeout(timeoutMs) { answer.await() }
            val envelope = JSONObject(raw)
            if (!envelope.optBoolean("ok")) throw BridgeException(envelope.optString("e", "Script failed"))
            return envelope.opt("v").takeUnless { it == JSONObject.NULL }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw BridgeException("Ultiworld didn't answer in time")
        } finally {
            pending.remove(id)
        }
    }

    /** Ultiworld's WordPress login cookie. Present once logged in, until it expires or the user logs out. */
    fun hasLoginCookie(): Boolean = CookieManager.getInstance().getCookie(ORIGIN + "/").orEmpty().contains("wordpress_logged_in_")

    suspend fun clearSession() = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        CookieManager.getInstance().removeAllCookies { done.complete(Unit) }
        done.await()
        CookieManager.getInstance().flush()
        webView.clearCache(false)
    }

    fun flushCookies() = CookieManager.getInstance().flush()

    /** The main frame's URL as soon as a load starts; [currentUrl] only changes once it finishes. */
    @Volatile
    private var startedUrl: String? = null

    private fun deliver(raw: String) {
        val id = runCatching { JSONObject(raw).optString("id") }.getOrNull() ?: return
        pending[id]?.complete(raw)
    }

    private fun isBlocked(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val path = uri.path.orEmpty()
        if (BLOCKED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
        // The page's own device-limit check-ins. Its script sends "not streaming" on every page load, which
        // would tell Ultiworld the TV gave up its stream while the app is playing one; the app sends its own.
        if (host == HOST && path == HEARTBEAT_PATH && uri.getQueryParameter(APP_CHECK_IN_PARAM) == null) return true
        // The page's own video player: the app plays the stream natively, so the page copy must not load it too.
        if (host == "cdn.jsdelivr.net" && path.contains("@mux")) return true
        // The Chromecast sender library that the page player pulls in. reCAPTCHA, also on gstatic, stays.
        if (host == "www.gstatic.com" && (path.startsWith("/cast/") || path.startsWith("/eureka/") || path.startsWith("/cv/"))) {
            return true
        }
        return false
    }

    companion object {
        const val ORIGIN = "https://ultiworld.com"
        private const val HOST = "ultiworld.com"
        private const val HEARTBEAT_PATH = "/wp-json/uw/v1/heartbeat/"

        /** Marks the app's own check-ins, so the page's can be told apart and blocked. */
        const val APP_CHECK_IN_PARAM = "uwtv"

        /** Whether [url] is a page on Ultiworld itself (not a look-alike such as ultiworld.com.example.net). */
        fun isOnSite(url: String?): Boolean {
            val uri = url?.let(Uri::parse) ?: return false
            return uri.scheme == "https" && uri.host == HOST
        }
        private const val TAG = "WebBridge"
        private const val JS_OBJECT = "UWNative"

        // Ads, analytics and video hosts. None are needed to read pages or log in, and they cost a TV's CPU.
        private val BLOCKED_HOST_SUFFIXES = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googletagservices.com",
            "googletagmanager.com",
            "google-analytics.com",
            "adtrafficquality.google",
            "googleadservices.com",
            "amazon-adsystem.com",
            "ampproject.org",
            "profitwell.com",
            "facebook.net",
            "cloudflareinsights.com",
            "stream.mux.com",
            "image.mux.com",
            "litix.io"
        )

        @Suppress("FunctionName")
        private fun EMPTY_RESPONSE() =
            WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
