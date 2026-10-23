package local.ultiworldtv.login

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import local.ultiworldtv.data.LoginResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLoginServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = mutableListOf<Pair<String, String>>()
    private var answer: LoginResult = LoginResult.Success
    private val lockouts = AtomicInteger(0)

    private val server = PhoneLoginServer(scope, preferredPort = 0, onLockedOut = { lockouts.incrementAndGet() }) { u, p ->
        synchronized(received) { received += u to p }
        answer
    }
    private val port = server.start()

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun get(path: String): Pair<Int, String> = request("GET", path, null)

    private fun post(path: String, form: Map<String, String>): Pair<Int, String> =
        request("POST", path, form.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") })

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return status to text
    }

    @Test
    fun `requests without the code are refused`() {
        assertEquals(403, get("/").first)
        assertEquals(403, get("/?k=wrongcode1").first)
        assertEquals(404, get("/other?k=${server.code}").first)
        assertEquals(403, post("/?k=nope", mapOf("username" to "a", "password" to "b")).first)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `the form is served with the code`() {
        val (status, html) = get("/?k=${server.code}")
        assertEquals(200, status)
        assertTrue(html.contains("action=\"/?k=${server.code}\""))
        assertTrue(html.contains("type=\"password\""))
    }

    @Test
    fun `a good login is handed over once`() {
        val (status, html) = post("/?k=${server.code}", mapOf("username" to "me@example.com", "password" to "p&ss w=rd"))
        assertEquals(200, status)
        assertTrue(html.contains("Your TV is opening Ultiworld"))
        assertEquals(listOf("me@example.com" to "p&ss w=rd"), received)
        // After that the page only says so, and nothing more is handed over.
        assertTrue(get("/?k=${server.code}").second.contains("already logged in"))
        post("/?k=${server.code}", mapOf("username" to "x", "password" to "y"))
        assertEquals(1, received.size)
    }

    @Test
    fun `a failed login shows Ultiworld's message escaped, with the form again`() {
        answer = LoginResult.Failed("Wrong <b>password</b> for \"me\"")
        val html = post("/?k=${server.code}", mapOf("username" to "me", "password" to "bad")).second
        assertTrue(html.contains("Wrong &lt;b&gt;password&lt;/b&gt; for &quot;me&quot;"))
        assertFalse(html.contains("<b>password</b>"))
        assertTrue(html.contains("type=\"password\""))
    }

    @Test
    fun `empty fields don't reach Ultiworld`() {
        val html = post("/?k=${server.code}", mapOf("username" to " ", "password" to "")).second
        assertTrue(html.contains("Enter your email and password"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `tries are limited`() {
        answer = LoginResult.Failed("nope")
        repeat(PhoneLoginServer.MAX_ATTEMPTS) { post("/?k=${server.code}", mapOf("username" to "u", "password" to "p")) }
        assertEquals(429, post("/?k=${server.code}", mapOf("username" to "u", "password" to "p")).first)
        assertEquals(PhoneLoginServer.MAX_ATTEMPTS, received.size)
        // The TV hears about it once, so it can stop showing a code that no longer works.
        assertEquals(1, lockouts.get())
        assertTrue(runCatching { get("/?k=${server.code}") }.isFailure)
        assertEquals(1, lockouts.get())
    }

    @Test
    fun `form parsing decodes and keeps empty values`() {
        assertEquals(mapOf("a" to "1 2", "b" to "", "c" to "x=y&z"), PhoneLoginServer.parseForm("a=1+2&b=&c=x%3Dy%26z"))
    }
}
