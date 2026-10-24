package io.github.myronl.ultiworldtv.login

import io.github.myronl.ultiworldtv.data.LoginResult
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val key = Base64.getUrlDecoder().decode(server.link("127.0.0.1").substringAfter('#'))

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun get(path: String): Pair<Int, String> = request("GET", path, null)

    private fun post(path: String, form: Map<String, String>): Pair<Int, String> =
        request("POST", path, form.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") })

    /** What the page posts: the login sealed with AES-GCM, as the page's script does it. */
    private fun sealed(
        username: String,
        password: String,
        key: ByteArray = this.key,
        code: String = server.code,
        nonce: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }
    ): Map<String, String> {
        val login = "username=${URLEncoder.encode(username, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(code.toByteArray())
        return mapOf("n" to base64(nonce), "c" to base64(cipher.doFinal(login.toByteArray())))
    }

    private fun logIn(username: String, password: String) = post("/?k=${server.code}", sealed(username, password))

    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

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
        assertEquals(403, post("/?k=nope", sealed("a", "b")).first)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `the link carries a new key after the #`() {
        val link = server.link("10.0.0.2")
        assertTrue(link.startsWith("http://10.0.0.2:$port/?k=${server.code}#"))
        assertEquals(32, key.size)
        val other = PhoneLoginServer(scope, preferredPort = 0) { _, _ -> LoginResult.Success }
        assertFalse(other.link("10.0.0.2").substringAfter('#') == link.substringAfter('#'))
    }

    @Test
    fun `the form is served with the code, and can't send the login as it is`() {
        val (status, html) = get("/?k=${server.code}")
        assertEquals(200, status)
        assertTrue(html.contains("action=\"/?k=${server.code}\""))
        assertTrue(html.contains("type=\"password\""))
        // Hidden until the script finds the key; the fields have no names, so a plain submit can't send them.
        assertTrue(html.contains("Scan the QR code on the TV"))
        assertTrue(html.contains("<form id=\"login\" method=\"post\" action=\"/?k=${server.code}\" autocomplete=\"on\" hidden>"))
        assertFalse(html.contains("name=\"username\"") || html.contains("name=\"password\""))
    }

    @Test
    fun `the page's script is the one the policy allows`() {
        val c = URL("http://127.0.0.1:$port/?k=${server.code}").openConnection() as HttpURLConnection
        val policy = c.getHeaderField("Content-Security-Policy")
        val html = c.inputStream.bufferedReader().readText()
        val script = html.substringAfter("<script>").substringBefore("</script>")
        assertTrue(script.contains("modules[\"./aes.js\"]") && script.contains("location.hash"))
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
        assertTrue(policy, policy.contains("script-src 'sha256-$hash';"))
    }

    @Test
    fun `a good login is handed over once`() {
        val (status, html) = logIn("me@example.com", "p&ss w=rd ü")
        assertEquals(200, status)
        assertTrue(html.contains("Your TV is opening Ultiworld"))
        assertEquals(listOf("me@example.com" to "p&ss w=rd ü"), received)
        // After that the page only says so, and nothing more is handed over.
        assertTrue(get("/?k=${server.code}").second.contains("already logged in"))
        logIn("x", "y")
        assertEquals(1, received.size)
    }

    @Test
    fun `a wrong key, code or ciphertext is refused`() {
        val wrongKey = sealed("me", "pw", key = ByteArray(32))
        val wrongCode = sealed("me", "pw", code = "abcdefghjk")
        val tampered = sealed("me", "pw").let { form ->
            val c = Base64.getUrlDecoder().decode(form["c"])
            c[3] = (c[3].toInt() xor 1).toByte()
            form + ("c" to base64(c))
        }
        val cut = sealed("me", "pw").let { form -> form + ("c" to form.getValue("c").take(8)) }
        for (form in listOf(wrongKey, wrongCode, tampered, cut, mapOf("n" to "short", "c" to "AAAA"), emptyMap())) {
            val (status, html) = post("/?k=${server.code}", form)
            assertEquals(400, status)
            assertTrue(html.contains("Your login didn&#39;t come through"))
        }
        assertTrue(received.isEmpty())
    }

    @Test
    fun `the login isn't taken as plain fields`() {
        assertEquals(400, post("/?k=${server.code}", mapOf("username" to "me", "password" to "pw")).first)
        // Not even next to a good sealed one.
        assertEquals(400, post("/?k=${server.code}", sealed("me", "pw") + ("password" to "pw")).first)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a nonce is only taken once`() {
        answer = LoginResult.Failed("Wrong password")
        val form = sealed("me", "pw")
        assertEquals(200, post("/?k=${server.code}", form).first)
        assertEquals(400, post("/?k=${server.code}", form).first)
        // Even with a different login under the same nonce.
        assertEquals(400, post("/?k=${server.code}", sealed("me", "pw2", nonce = Base64.getUrlDecoder().decode(form["n"]))).first)
        assertEquals(listOf("me" to "pw"), received)
    }

    @Test
    fun `the page's AES-GCM and the server's agree`() {
        // Made by the served page's script (the vendored @noble/ciphers and phone-login.js) under node, with this
        // key, nonce and code, for username "me@example.com" and password "p&ss w=rd ü".
        val key = ByteArray(32) { it.toByte() }
        val nonce = Base64.getUrlDecoder().decode(VECTOR_NONCE)
        val login = PhoneLoginServer.unseal(key, "abcdefghjk", nonce, Base64.getUrlDecoder().decode(VECTOR_SEALED))
        assertEquals(mapOf("username" to "me@example.com", "password" to "p&ss w=rd ü"), PhoneLoginServer.parseForm(login!!))
        assertNull(PhoneLoginServer.unseal(key, "abcdefghjm", nonce, Base64.getUrlDecoder().decode(VECTOR_SEALED)))
    }

    @Test
    fun `a failed login shows Ultiworld's message escaped, with the form again`() {
        answer = LoginResult.Failed("Wrong <b>password</b> for \"me\"")
        val html = logIn("me", "bad").second
        assertTrue(html.contains("Wrong &lt;b&gt;password&lt;/b&gt; for &quot;me&quot;"))
        assertFalse(html.contains("<b>password</b>"))
        assertTrue(html.contains("type=\"password\""))
    }

    @Test
    fun `empty fields don't reach Ultiworld`() {
        val html = logIn(" ", "").second
        assertTrue(html.contains("Enter your email and password"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `tries are limited`() {
        answer = LoginResult.Failed("nope")
        // Ones that can't be read count too.
        repeat(2) { post("/?k=${server.code}", sealed("u", "p", key = ByteArray(32))) }
        repeat(PhoneLoginServer.MAX_ATTEMPTS - 2) { logIn("u", "p") }
        assertEquals(429, logIn("u", "p").first)
        assertEquals(PhoneLoginServer.MAX_ATTEMPTS - 2, received.size)
        // The TV hears about it once, so it can stop showing a code that no longer works.
        assertEquals(1, lockouts.get())
        assertTrue(runCatching { get("/?k=${server.code}") }.isFailure)
        assertEquals(1, lockouts.get())
    }

    @Test
    fun `form parsing decodes and keeps empty values`() {
        assertEquals(mapOf("a" to "1 2", "b" to "", "c" to "x=y&z"), PhoneLoginServer.parseForm("a=1+2&b=&c=x%3Dy%26z"))
    }

    private companion object {
        const val VECTOR_NONCE = "oKGio6Slpqeoqaqr"
        const val VECTOR_SEALED = "k2sZXyuqb9pfCOL2M0qlphHBKXz3mSED8ShW5wzYAm6gEnqPihBlTiy3c-06PvGdbD4Fe0eSWX4YzFjfwRG7pcthT7rk6dU"
    }
}
