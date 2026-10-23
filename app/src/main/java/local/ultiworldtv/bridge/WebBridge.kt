package local.ultiworldtv.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
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
 * check runs slower for it). It is only brought to the front for logging in or a Cloudflare prompt.
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

    val userAgent: String get() = webView.settings.userAgentString

    init {
        WebView.setWebContentsDebuggingEnabled(local.ultiworldtv.BuildConfig.DEBUG)
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
            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun postMessage(data: String) = deliver(data)
                },
                JS_OBJECT
            )
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                _currentUrl.value = url
                pageLoads.value += 1
                onPageFinished?.invoke(url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (isBlocked(request.url)) EMPTY_RESPONSE() else null

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()})")
                pending.values.forEach { it.completeExceptionally(BridgeException("The page stopped responding")) }
                return true
            }
        }
    }

    /** Runs [block] with exclusive use of the page, so a navigation never lands in the middle of another call. */
    suspend fun <T> session(block: suspend WebBridge.() -> T): T = mutex.withLock { block() }

    /**
     * Hands the page to the person at the remote until cancelled: while it's on screen for logging in or
     * Cloudflare's check, every [session] waits, so background work (a check-in as the player closes, a
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
     * Runs [script], which starts a navigation of its own (submitting a form, say), and waits for the page
     * it leads to, the same way [load] does.
     */
    suspend fun evaluateAndAwaitNavigation(script: String, timeoutMs: Long = 30_000, challengeWaitMs: Long = 20_000) {
        val before = pageLoads.value
        evaluate(script)
        awaitPageLoad(before, timeoutMs, challengeWaitMs)
    }

    private suspend fun awaitPageLoad(after: Int, timeoutMs: Long, challengeWaitMs: Long) {
        withTimeoutOrNull(timeoutMs) { pageLoads.first { it > after } }
            ?: throw BridgeException("Ultiworld took too long to load")
        val deadline = System.currentTimeMillis() + challengeWaitMs
        while (isChallengePage()) {
            if (System.currentTimeMillis() > deadline) throw ChallengeRequiredException()
            delay(750)
        }
    }

    /** Whether the page on screen is Cloudflare's check rather than Ultiworld. */
    suspend fun isChallengePage(): Boolean {
        val v = runCatching { evaluate(Scripts.IS_CHALLENGE, timeoutMs = 5_000) }.getOrNull()
        // A page that never answers is usually mid-redirect after the check, so treat it as still checking.
        return v == null || v == true
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

    private fun deliver(raw: String) {
        val id = runCatching { JSONObject(raw).optString("id") }.getOrNull() ?: return
        pending[id]?.complete(raw)
    }

    private fun isBlocked(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val path = uri.path.orEmpty()
        if (BLOCKED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
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
