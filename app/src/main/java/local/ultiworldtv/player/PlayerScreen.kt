package local.ultiworldtv.player

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import local.ultiworldtv.bridge.ChallengeRequiredException
import local.ultiworldtv.data.Playback
import local.ultiworldtv.data.PlaybackResult
import local.ultiworldtv.data.UltiworldRepository
import local.ultiworldtv.ui.CenteredMessage
import local.ultiworldtv.ui.Palette

/** The site's own check-in timing: every 80 seconds while streaming, every 5 minutes otherwise. */
private const val HEARTBEAT_STREAMING_MS = 80_000L
private const val HEARTBEAT_IDLE_MS = 300_000L

private sealed interface PlayerState {
    data object Loading : PlayerState
    data class Playing(val playback: Playback) : PlayerState
    data class Failed(val title: String, val body: String, val canRetry: Boolean) : PlayerState
}

@Composable
fun PlayerScreen(
    repo: UltiworldRepository,
    videoUrl: String,
    userAgent: String,
    onExit: () -> Unit,
    onSessionExpired: () -> Unit,
    onChallenge: () -> Unit
) {
    var state by remember(videoUrl) { mutableStateOf<PlayerState>(PlayerState.Loading) }
    var attempt by remember(videoUrl) { mutableIntStateOf(0) }

    LaunchedEffect(videoUrl, attempt) {
        state = PlayerState.Loading
        state = try {
            when (val r = repo.playback(videoUrl)) {
                is PlaybackResult.Ready -> PlayerState.Playing(r.playback)
                is PlaybackResult.Locked -> PlayerState.Failed(
                    "Not included in your subscription",
                    r.message.ifBlank { "This video needs a different Ultiworld plan or an event pack." },
                    canRetry = false
                )
                is PlaybackResult.Stopped -> PlayerState.Failed(
                    "Streaming on too many devices",
                    "Stop watching on another device, then try again.",
                    canRetry = true
                )
                PlaybackResult.Missing -> PlayerState.Failed(
                    "This video isn't available yet",
                    "Live streams appear here once the broadcast starts.",
                    canRetry = true
                )
            }
        } catch (e: ChallengeRequiredException) {
            onChallenge()
            return@LaunchedEffect
        } catch (e: Exception) {
            PlayerState.Failed("Couldn't open the video", e.message ?: "", canRetry = true)
        }
    }

    when (val s = state) {
        PlayerState.Loading -> CenteredMessage("Opening the stream…", "")
        is PlayerState.Playing -> VideoPlayer(
            playback = s.playback,
            repo = repo,
            userAgent = userAgent,
            onFailed = { state = it },
            onSessionExpired = onSessionExpired
        )
        is PlayerState.Failed -> CenteredMessage(
            title = s.title,
            body = s.body,
            action = if (s.canRetry) "Try again" else "Back",
            onAction = { if (s.canRetry) attempt++ else onExit() },
            secondary = if (s.canRetry) "Back" else null,
            onSecondary = onExit
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    playback: Playback,
    repo: UltiworldRepository,
    userAgent: String,
    onFailed: (PlayerState.Failed) -> Unit,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    // "Streaming" the way the site counts it: from play until pause, end or error, buffering included.
    val streaming = remember(playback) { MutableStateFlow(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    val player = remember(playback) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to "https://ultiworld.com/", "Origin" to "https://ultiworld.com"))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(playback.streamUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(playback.title).build())
                        .build()
                )
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                streaming.value = player.playWhenReady &&
                    player.playerError == null &&
                    (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                onFailed(describe(error, playback.isLive))
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            repo.releaseStreamingSlot()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            val now = streaming.value
            val result = runCatching { repo.heartbeat(streaming = now) }.getOrNull()
            if (result != null && !result.sessionValid) {
                player.stop()
                onSessionExpired()
                return@LaunchedEffect
            }
            if (result != null && result.stopStreaming) {
                player.stop()
                onFailed(
                    PlayerState.Failed("Streaming on too many devices", "Stop watching on another device, then try again.", canRetry = true)
                )
                return@LaunchedEffect
            }
            // Like the site, check in straight away when playback starts; otherwise wait for the next interval.
            withTimeoutOrNull(if (now) HEARTBEAT_STREAMING_MS else HEARTBEAT_IDLE_MS) {
                streaming.first { it && !now }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    controllerShowTimeoutMs = 3500
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE })
                    isFocusable = true
                    isFocusableInTouchMode = true
                    post { requestFocus() }
                }
            }
        )
        if (controlsVisible) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 48.dp, vertical = 28.dp)
                    .align(Alignment.TopStart)
            ) {
                Text(playback.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun describe(error: PlaybackException, isLive: Boolean): PlayerState.Failed {
    val http = generateSequence(error.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return when {
        // Mux answers 412 for a live stream that exists but isn't broadcasting yet.
        http != null && http.responseCode in setOf(404, 412) && isLive -> PlayerState.Failed(
            "The broadcast hasn't started",
            "Try again closer to the start time.",
            canRetry = true
        )
        http != null && http.responseCode == 403 -> PlayerState.Failed(
            "The stream link expired",
            "Try again to get a fresh one.",
            canRetry = true
        )
        else -> PlayerState.Failed("Playback stopped", error.errorCodeName, canRetry = true)
    }
}
