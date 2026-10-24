package io.github.myronl.ultiworldtv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.myronl.ultiworldtv.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the published app up to date from its GitHub releases. Now and then it asks GitHub for the latest release
 * and, when that's newer and the viewer asks for it, downloads the APK and hands it to Android's installer, which
 * asks on the TV before installing. Android only installs it over this app when it's signed with the same key.
 *
 * Only in builds signed with the release key ([BuildConfig.UPDATES]): a build made with another key couldn't
 * install the published APK over itself anyway.
 */
class Updater(context: Context, private val scope: CoroutineScope) {
    sealed interface State {
        data object None : State
        data class Available(val release: Release) : State

        /** [percent] is null until the download's size is known. */
        data class Downloading(val release: Release, val percent: Int?) : State

        /** Android's installer has the APK and is asking on the TV. */
        data class Installing(val release: Release) : State
        data class Failed(val release: Release) : State
    }

    private val app = context.applicationContext
    var state by mutableStateOf<State>(State.None)
        private set

    private var nextCheck = 0L
    private var receiver: BroadcastReceiver? = null

    /** Asks GitHub for the latest release, at most every few hours after an answer. */
    suspend fun check() {
        if (!BuildConfig.UPDATES || SystemClock.elapsedRealtime() < nextCheck) return
        if (state !is State.None && state !is State.Available) return
        val release = try {
            withContext(Dispatchers.IO) { Releases.parse(fetchText(Releases.LATEST_URL)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Updates are extra: try again on a later check.
            return
        }
        nextCheck = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS
        if (release != null && Releases.isNewer(release.version, BuildConfig.VERSION_NAME)) state = State.Available(release)
    }

    /** Downloads the newer release and opens Android's installer for it. Runs on in the background off Home. */
    fun install() {
        val release = when (val s = state) {
            is State.Available -> s.release
            is State.Failed -> s.release
            else -> return
        }
        state = State.Downloading(release, percent = null)
        scope.launch {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
            }
            val id = try {
                installer.createSession(params)
            } catch (e: IOException) {
                state = State.Failed(release)
                return@launch
            }
            try {
                installer.openSession(id).use { session ->
                    withContext(Dispatchers.IO) { download(release, session) }
                    state = State.Installing(release)
                    listen(release)
                    session.commit(statusSender(id))
                }
            } catch (e: CancellationException) {
                installer.abandonSession(id)
                state = State.Available(release)
                throw e
            } catch (e: Exception) {
                runCatching { installer.abandonSession(id) }
                stopListening()
                state = State.Failed(release)
            }
        }
    }

    fun close() = stopListening()

    private suspend fun download(release: Release, session: PackageInstaller.Session) {
        val conn = open(release.apkUrl)
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                session.openWrite(Releases.APK_NAME, 0, total).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        coroutineContext.ensureActive()
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else null
                        if (percent != null && percent != shown) {
                            shown = percent
                            state = State.Downloading(release, percent)
                        }
                    }
                    session.fsync(out)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Where Android's installer reports back: first to ask on the TV, then how it went. */
    private fun statusSender(sessionId: Int): IntentSender {
        val intent = Intent(ACTION_STATUS).setPackage(app.packageName)
        // Mutable: the installer adds its status to it.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(app, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender
    }

    private fun listen(release: Release) {
        stopListening()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    // The confirmation screen, or first the TV's "allow this app to install apps" setting.
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        try {
                            app.startActivity(checkNotNull(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: IllegalStateException) {
                            finish(State.Failed(release))
                        } catch (e: ActivityNotFoundException) {
                            finish(State.Failed(release))
                        }
                    }

                    // Android stops the app to replace it, so this is rarely seen.
                    PackageInstaller.STATUS_SUCCESS -> finish(State.None)

                    // Cancelled on the TV.
                    PackageInstaller.STATUS_FAILURE_ABORTED -> finish(State.Available(release))

                    else -> finish(State.Failed(release))
                }
            }
        }
        ContextCompat.registerReceiver(app, r, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun finish(next: State) {
        stopListening()
        state = next
    }

    private fun stopListening() {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
    }

    private fun fetchText(url: String): String {
        val conn = open(url, accept = "application/vnd.github+json")
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Follows GitHub's redirect from the release page to where the file is stored. */
    private fun open(url: String, accept: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "UltiworldTV/${BuildConfig.VERSION_NAME}")
        accept?.let { conn.setRequestProperty("Accept", it) }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("GitHub answered $code")
        }
        return conn
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L
        const val ACTION_STATUS = "io.github.myronl.ultiworldtv.UPDATE_STATUS"
    }
}
