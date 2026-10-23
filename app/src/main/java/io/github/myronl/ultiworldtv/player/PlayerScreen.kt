package io.github.myronl.ultiworldtv.player

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import coil.compose.AsyncImage
import io.github.myronl.ultiworldtv.bridge.ChallengeRequiredException
import io.github.myronl.ultiworldtv.data.Playback
import io.github.myronl.ultiworldtv.data.PlaybackResult
import io.github.myronl.ultiworldtv.data.UltiworldRepository
import io.github.myronl.ultiworldtv.ui.CenteredMessage
import io.github.myronl.ultiworldtv.ui.Palette
import io.github.myronl.ultiworldtv.ui.Spinner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The site's own check-in timing: every 80 seconds while streaming, every 5 minutes otherwise. */
private const val HEARTBEAT_STREAMING_MS = 80_000L
private const val HEARTBEAT_IDLE_MS = 300_000L
private const val TITLE_INTRO_MS = 2_000L

private sealed interface PlayerState {
    data object Loading : PlayerState
    data class Playing(val playback: Playback) : PlayerState
    data class Failed(val title: String, val body: String, val canRetry: Boolean) : PlayerState
}

@Composable
fun PlayerScreen(
    repo: UltiworldRepository,
    videoUrl: String,
    title: String?,
    image: String?,
    userAgent: String,
    onExit: () -> Unit,
    onSessionExpired: () -> Unit,
    onChallenge: () -> Unit
) {
    var state by remember(videoUrl) { mutableStateOf<PlayerState>(PlayerState.Loading) }
    var attempt by remember(videoUrl) { mutableIntStateOf(0) }
    // Where a replay was when it failed, so trying again picks up there instead of at the start.
    var resumeAt by remember(videoUrl) { mutableLongStateOf(C.TIME_UNSET) }

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

                is PlaybackResult.Upcoming -> PlayerState.Failed(
                    "This video hasn't started yet",
                    r.message.replaceFirstChar { it.uppercase() }.ifBlank { "Try again closer to the start time." },
                    canRetry = true
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
        PlayerState.Loading -> Opening(title, image)

        is PlayerState.Playing -> VideoPlayer(
            playback = s.playback,
            repo = repo,
            image = image,
            userAgent = userAgent,
            startAt = resumeAt,
            onFailed = { failed, position ->
                if (!s.playback.isLive && position > 0) resumeAt = position
                state = failed
            },
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
    image: String?,
    userAgent: String,
    startAt: Long,
    onFailed: (failed: PlayerState.Failed, positionMs: Long) -> Unit,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // "Streaming" the way the site counts it: from play until pause, end or error, buffering included.
    val streaming = remember(playback) { MutableStateFlow(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    // The title shows for a moment when a video starts, then only along with the player controls.
    var introTitle by remember(playback) { mutableStateOf(true) }
    LaunchedEffect(playback) {
        delay(TITLE_INTRO_MS)
        introTitle = false
    }

    val player = remember(playback) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to "https://ultiworld.com/", "Origin" to "https://ultiworld.com"))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            // Pauses for another app's sound (a voice search, say) the way other TV apps do.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
            .apply {
                val item = MediaItem.Builder()
                    .setUri(playback.streamUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(playback.title).build())
                    .build()
                if (startAt > 0) setMediaItem(item, startAt) else setMediaItem(item)
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
                onFailed(describe(error, playback.isLive), player.currentPosition)
            }
        }
        player.addListener(listener)
        // Home on the remote (or another app taking over) stops the activity without leaving this screen: pause,
        // and give the account's stream slot back right away rather than at the next check-in.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                repo.releaseStreamingSlot()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
            repo.releaseStreamingSlot()
        }
    }

    // Check-ins only while the app is on screen; in the background the player is paused and the slot given back.
    LaunchedEffect(player) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { checkIns(repo, player, streaming, onFailed, onSessionExpired) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Shows through until the first frame arrives, so opening a video doesn't flash to black.
        Backdrop(image)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
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
        AnimatedVisibility(
            visible = controlsVisible || introTitle,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 48.dp, vertical = 28.dp)
            ) {
                Text(playback.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            }
        }
    }
}

/** The site's device-limit check-ins, at its own timing, until Ultiworld says to stop. */
private suspend fun checkIns(
    repo: UltiworldRepository,
    player: ExoPlayer,
    streaming: MutableStateFlow<Boolean>,
    onFailed: (PlayerState.Failed, Long) -> Unit,
    onSessionExpired: () -> Unit
) {
    while (true) {
        val now = streaming.value
        val result = try {
            repo.heartbeat(streaming = now)
        } catch (e: Exception) {
            // Leaving the player cancels this loop. Any other failure is a network blip: like the
            // website's player, keep playing and check in again at the next interval.
            currentCoroutineContext().ensureActive()
            null
        }
        if (result != null && !result.sessionValid) {
            player.stop()
            onSessionExpired()
            return
        }
        if (result != null && result.stopStreaming) {
            player.stop()
            onFailed(
                PlayerState.Failed("Streaming on too many devices", "Stop watching on another device, then try again.", canRetry = true),
                player.currentPosition
            )
            return
        }
        // Like the site, check in straight away when playback starts; otherwise wait for the next interval.
        withTimeoutOrNull(if (now) HEARTBEAT_STREAMING_MS else HEARTBEAT_IDLE_MS) {
            streaming.first { it && !now }
        }
    }
}

/** The video's thumbnail, dimmed and softened, filling the screen behind what's on it. */
@Composable
private fun Backdrop(image: String?) {
    if (image == null) return
    AsyncImage(
        model = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Blur needs Android 12; older TVs get the dimmed thumbnail without it.
        modifier = Modifier.fillMaxSize().blur(12.dp).alpha(0.35f)
    )
}

/** While the app finds the stream: what it already knows about the video, and a spinner. */
@Composable
private fun Opening(title: String?, image: String?) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Backdrop(image)
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spinner()
            Spacer(Modifier.height(28.dp))
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Palette.Text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
            }
            Text("Opening the stream…", style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
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
