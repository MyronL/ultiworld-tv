package local.ultiworldtv.login

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.ultiworldtv.data.LoginResult

/**
 * A tiny web server on the TV, so you can type your Ultiworld login on your phone instead of with the remote.
 *
 * The TV shows a QR code for `http://<tv>:<port>/?k=<code>`. The phone gets a login form from this server,
 * and what it submits goes to [onCredentials], the same login the TV's own form uses. It's for the home
 * network only and lives as long as the login screen: every request must carry the one-time [code], it
 * accepts [MAX_ATTEMPTS] tries (then stops and calls [onLockedOut]), and it shuts down after a successful login. Plain HTTP on the LAN, so the
 * login crosses your Wi-Fi unencrypted (as with any device on the network you set up from a phone).
 */
class PhoneLoginServer(
    private val scope: CoroutineScope,
    private val preferredPort: Int = DEFAULT_PORT,
    /** The TV's home-network address, so the server isn't reachable on other interfaces (a VPN, say). Null: all of them. */
    private val bindAddress: InetAddress? = null,
    /**
     * Called once, off the main thread, when the server stops itself after too many tries. The code on the
     * TV no longer works, so the screen should stop showing it (or start a new server with a new code).
     */
    private val onLockedOut: () -> Unit = {},
    private val onCredentials: suspend (username: String, password: String) -> LoginResult
) {
    val code: String = newCode()

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var server: ServerSocket? = null
    private val attempts = AtomicInteger(0)
    private val clients = AtomicInteger(0)

    @Volatile
    private var finished = false

    /** Starts listening on [preferredPort], or any free port if that one's taken. Returns the port. */
    fun start(): Int {
        val socket = try {
            ServerSocket(preferredPort, BACKLOG, bindAddress)
        } catch (e: IOException) {
            ServerSocket(0, BACKLOG, bindAddress)
        }
        server = socket
        port = socket.localPort
        scope.launch(Dispatchers.IO) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                // A phone needs one or two connections; more at once is someone tying the server up.
                if (clients.incrementAndGet() > MAX_CLIENTS) {
                    clients.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                launch {
                    // The socket timeout is per read, so a client trickling bytes could hold on for hours.
                    // Closing the socket ends a blocked read too.
                    val deadline = launch {
                        delay(REQUEST_DEADLINE_MS)
                        runCatching { client.close() }
                    }
                    try {
                        client.use { handle(it) }
                    } finally {
                        deadline.cancel()
                        clients.decrementAndGet()
                    }
                }
            }
        }
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = 15_000
        val request = try {
            readRequest(client.getInputStream())
        } catch (e: IOException) {
            null
        } ?: return respond(client, 400, page("Bad request", "<p>Scan the code on the TV again.</p>"))

        val out = when {
            request.path == "/favicon.ico" -> Response(404, "")
            request.path != "/" -> Response(404, page("Not found", "<p>Scan the code on the TV again.</p>"))
            !sameCode(request.query["k"]) -> Response(403, page("This link has expired", "<p>Scan the code on the TV again.</p>"))
            finished -> Response(200, page("You're logged in", "<p>The TV is already logged in to Ultiworld.</p>"))
            request.method == "GET" -> Response(200, formPage(error = null))
            request.method == "POST" -> submit(request)
            else -> Response(405, page("Not allowed", ""))
        }
        respond(client, out.status, out.body)
    }

    private suspend fun submit(request: Request): Response {
        val attempt = attempts.incrementAndGet()
        if (attempt > MAX_ATTEMPTS) {
            stop()
            if (attempt == MAX_ATTEMPTS + 1) onLockedOut()
            return Response(429, page("Too many tries", "<p>The TV is showing a new code. Scan it to try again.</p>"))
        }
        val form = parseForm(request.body)
        val username = form["username"].orEmpty().trim()
        val password = form["password"].orEmpty()
        if (username.isEmpty() || password.isEmpty()) return Response(200, formPage(error = "Enter your email and password."))

        val result = withTimeoutOrNull(LOGIN_TIMEOUT_MS) { onCredentials(username, password) }
            ?: LoginResult.Failed("Ultiworld took too long to answer. Try again.")
        return when (result) {
            LoginResult.Success -> {
                finished = true
                // Answer the phone first, then stop listening.
                scope.launch {
                    delay(1_000)
                    stop()
                }
                Response(200, page("You're logged in", "<p>Your TV is opening Ultiworld. You can close this page.</p>"))
            }
            is LoginResult.Failed -> Response(200, formPage(error = result.message))
            is LoginResult.NeedsWebPage -> Response(
                200,
                page("Finish on the TV", "<p>${escape(result.reason)}</p><p>The TV is showing Ultiworld's login page.</p>")
            )
        }
    }

    private fun sameCode(given: String?): Boolean = given != null && MessageDigest.isEqual(given.toByteArray(), code.toByteArray())

    private fun formPage(error: String?): String = page(
        "Log in to Ultiworld TV",
        """
        <p class="muted">Use your Ultiworld account. The TV logs in for you; your password isn't stored.</p>
        ${error?.let { "<p class=\"error\">${escape(it)}</p>" } ?: ""}
        <form method="post" action="/?k=$code" autocomplete="on">
          <label>Email or username<input name="username" type="text" inputmode="email" autocomplete="username"
            autocapitalize="none" autocorrect="off" spellcheck="false" required autofocus></label>
          <label>Password<input name="password" type="password" autocomplete="current-password" required></label>
          <button type="submit">Log in on the TV</button>
        </form>
        """
    )

    private class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

    private class Response(val status: Int, val body: String)

    companion object {
        const val DEFAULT_PORT = 8765
        const val MAX_ATTEMPTS = 8
        private const val MAX_CLIENTS = 4
        private const val BACKLOG = 8

        // Reading the request and the login itself, which can take a while.
        private const val REQUEST_DEADLINE_MS = 90_000L
        private const val LOGIN_TIMEOUT_MS = 75_000L
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024
        private const val CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        private fun newCode(): String {
            val random = SecureRandom()
            return (1..10).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        }

        /** Reads one HTTP/1.x request: request line, headers, and a body of at most [MAX_BODY_BYTES]. */
        private fun readRequest(input: InputStream): Request? {
            val head = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < end.size) {
                val b = input.read()
                if (b == -1 || head.size() >= MAX_HEADER_BYTES) return null
                head.write(b)
                matched = if (b.toByte() == end[matched]) {
                    matched + 1
                } else if (b.toByte() == end[0]) {
                    1
                } else {
                    0
                }
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 3) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY_BYTES) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n == -1) return null
                read += n
            }
            val target = parts[1]
            val q = target.indexOf('?')
            return Request(
                method = parts[0].uppercase(),
                path = if (q == -1) target else target.substring(0, q),
                query = if (q == -1) emptyMap() else parseForm(target.substring(q + 1)),
                body = String(body, Charsets.UTF_8)
            )
        }

        /** `a=1&b=two%20words` to a map. Also used for query strings. */
        internal fun parseForm(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val i = pair.indexOf('=')
            val k = if (i == -1) pair else pair.substring(0, i)
            val v = if (i == -1) "" else pair.substring(i + 1)
            decode(k) to decode(v)
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            s
        }

        internal fun escape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        private fun respond(client: Socket, status: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                429 -> "Too Many Requests"
                else -> "Error"
            }
            val head = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Referrer-Policy: no-referrer\r\n")
                append("X-Frame-Options: DENY\r\n")
                append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'\r\n")
                append("Connection: close\r\n\r\n")
            }
            runCatching {
                client.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    write(bytes)
                    flush()
                }
            }
        }

        private fun page(title: String, content: String): String = """
            <!doctype html>
            <html lang="en"><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escape(title)}</title>
            <style>
              :root { color-scheme: dark; }
              body { margin: 0; background: #0E1116; color: #E8EAED; font: 17px/1.45 system-ui, -apple-system, sans-serif; }
              main { max-width: 420px; margin: 0 auto; padding: 32px 20px; }
              .brand { font-weight: 900; letter-spacing: .5px; font-size: 22px; margin: 0 0 24px; }
              .brand b { color: #D2232A; } .brand span { color: #00A3B4; }
              h1 { font-size: 24px; margin: 0 0 8px; }
              .muted { color: #9AA3AE; margin-top: 0; }
              .error { background: #3a1416; border: 1px solid #D2232A; color: #ffd7d9; padding: 10px 12px; border-radius: 8px; }
              label { display: block; margin: 16px 0 0; color: #9AA3AE; font-size: 15px; }
              input { display: block; box-sizing: border-box; width: 100%; margin-top: 6px; padding: 14px; font-size: 17px;
                color: #E8EAED; background: #171B22; border: 1px solid #39414D; border-radius: 10px; }
              input:focus { outline: 2px solid #00A3B4; border-color: #00A3B4; }
              button { width: 100%; margin-top: 24px; padding: 15px; font-size: 17px; font-weight: 700; color: #000;
                background: #00A3B4; border: 0; border-radius: 10px; }
            </style></head>
            <body><main>
            <p class="brand"><b>ULTIWORLD</b> <span>TV</span></p>
            <h1>${escape(title)}</h1>
            $content
            </main></body></html>
        """.trimIndent()
    }
}
