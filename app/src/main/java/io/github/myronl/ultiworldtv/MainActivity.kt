package io.github.myronl.ultiworldtv

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.foundation.background as composeBackground
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.myronl.ultiworldtv.bridge.ChallengeRequiredException
import io.github.myronl.ultiworldtv.bridge.Scripts
import io.github.myronl.ultiworldtv.bridge.WebBridge
import io.github.myronl.ultiworldtv.data.Game
import io.github.myronl.ultiworldtv.data.LoginResult
import io.github.myronl.ultiworldtv.data.UltiworldRepository
import io.github.myronl.ultiworldtv.data.VideoCard
import io.github.myronl.ultiworldtv.login.LoginModel
import io.github.myronl.ultiworldtv.login.LoginScreen
import io.github.myronl.ultiworldtv.player.PlayerScreen
import io.github.myronl.ultiworldtv.ui.BrandMark
import io.github.myronl.ultiworldtv.ui.CenteredMessage
import io.github.myronl.ultiworldtv.ui.CollectionModel
import io.github.myronl.ultiworldtv.ui.CollectionScreen
import io.github.myronl.ultiworldtv.ui.FeedPicker
import io.github.myronl.ultiworldtv.ui.HomeModel
import io.github.myronl.ultiworldtv.ui.HomeScreen
import io.github.myronl.ultiworldtv.ui.Palette
import io.github.myronl.ultiworldtv.ui.SearchModel
import io.github.myronl.ultiworldtv.ui.SearchScreen
import io.github.myronl.ultiworldtv.ui.Spinner
import io.github.myronl.ultiworldtv.ui.UltiworldTheme
import io.github.myronl.ultiworldtv.update.Updater
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Starting : Screen

    /** The app's own login form, with the phone QR code. */
    data object Login : Screen

    /** Cloudflare wants a human check; the WebView is on screen until it passes. */
    data object Challenge : Screen
    data object Home : Screen
    data class Collection(val title: String, val url: String) : Screen
    data object Search : Screen

    /** [title] and [image] are what the app knows about the video already, to show while the stream opens. */
    data class Player(val url: String, val title: String? = null, val image: String? = null) : Screen
    data class Message(val title: String, val body: String, val action: String, val next: Screen) : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var bridge: WebBridge
    private lateinit var repo: UltiworldRepository
    private lateinit var home: HomeModel
    private lateinit var login: LoginModel
    private lateinit var search: SearchModel
    private lateinit var updater: Updater
    private val collections = CollectionModel()
    private lateinit var composeView: ComposeView
    private lateinit var hint: TextView

    private val stack = mutableStateListOf<Screen>(Screen.Starting)
    private val top: Screen get() = stack.last()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = WebBridge(this)
        repo = UltiworldRepository(bridge, lifecycleScope)
        home = HomeModel(repo, getSharedPreferences("settings", MODE_PRIVATE))
        login = LoginModel(repo)
        search = SearchModel(repo)
        updater = Updater(this, lifecycleScope)

        composeView = ComposeView(this).apply { setContent { UltiworldTheme { App() } } }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad * 4, pad, pad * 4, pad)
            isFocusable = false
            visibility = View.GONE
        }
        setContentView(
            FrameLayout(this).apply {
                // The WebView sits behind everything; see WebBridge for why it stays visible.
                addView(bridge.webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(composeView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                // At the top: the login form's button sits low on the page, and the logo up top isn't needed.
                addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            }
        )
        bridge.onPageFinished = { onWebPageFinished() }
        // A WebView whose renderer died can't load anything again: start the app over with a fresh one.
        bridge.onRendererGone = ::recreate
        onBackPressedDispatcher.addCallback(this) { goBack() }
    }

    override fun onPause() {
        super.onPause()
        bridge.flushCookies()
    }

    override fun onDestroy() {
        updater.close()
        (bridge.webView.parent as? ViewGroup)?.removeView(bridge.webView)
        bridge.webView.destroy()
        super.onDestroy()
    }

    @Composable
    private fun App() {
        val screen = top
        var picker by remember { mutableStateOf<Game?>(null) }
        var confirmSignOut by remember { mutableStateOf(false) }

        LaunchedEffect(screen) {
            showWeb(screen)
            // Leaving the screen cancels this, which hands the page back to the app.
            when (screen) {
                // A fresh load of the page puts the check on screen, or shows there's none any more, which
                // closes this screen (see onWebPageFinished) instead of leaving a plain page up.
                Screen.Challenge -> bridge.holdForUser(
                    bridge.currentUrl.value?.takeIf(WebBridge::isOnSite) ?: UltiworldRepository.VIDEO_LIBRARY_URL
                )

                else -> Unit
            }
        }

        when (screen) {
            Screen.Starting -> {
                LaunchedEffect(Unit) { start() }
                Column(
                    Modifier.fillMaxSize().composeBackground(Palette.Background),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BrandMark()
                    Spinner(Modifier.padding(top = 40.dp))
                    Text(
                        "Connecting to Ultiworld…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.TextMuted,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
            }

            Screen.Login -> LoginScreen(login, onResult = ::onLoginResult)

            Screen.Challenge -> Unit

            Screen.Home -> HomeScreen(
                model = home,
                updater = updater,
                onOpen = ::open,
                onGame = { game ->
                    when (game.videos.size) {
                        0 -> Unit
                        1 -> push(Screen.Player(game.videos.single().url, title = game.matchup()))
                        else -> picker = game
                    }
                },
                onSearch = { push(Screen.Search) },
                onSignOut = { confirmSignOut = true },
                onChallenge = { push(Screen.Challenge) }
            )

            Screen.Search -> SearchScreen(search, onOpen = ::open, onChallenge = { push(Screen.Challenge) })

            is Screen.Collection -> CollectionScreen(repo, collections, screen.title, screen.url, onOpen = ::open, onChallenge = {
                push(Screen.Challenge)
            })

            is Screen.Player -> PlayerScreen(
                repo = repo,
                videoUrl = screen.url,
                title = screen.title,
                image = screen.image,
                userAgent = bridge.userAgent,
                onExit = ::goBack,
                onSessionExpired = {
                    lifecycleScope.launch { runCatching { repo.forgetLogin() } }
                    home.clear()
                    search.clear()
                    replaceAll(
                        Screen.Message(
                            "Logged out on this TV",
                            "Ultiworld logged this TV out because your account is logged in on too many devices. Log in again to keep watching.",
                            "Log in",
                            Screen.Login
                        )
                    )
                },
                onChallenge = { push(Screen.Challenge) }
            )

            is Screen.Message -> CenteredMessage(screen.title, screen.body, action = screen.action, onAction = { replaceAll(screen.next) })
        }

        picker?.let { game ->
            FeedPicker(game, onPick = {
                picker = null
                push(Screen.Player(it.url, title = game.matchup()))
            }, onDismiss = { picker = null })
        }
        if (confirmSignOut) {
            SignOutDialog(
                onConfirm = {
                    confirmSignOut = false
                    lifecycleScope.launch {
                        runCatching { repo.signOut() }
                        home.clear()
                        search.clear()
                        replaceAll(Screen.Login)
                    }
                },
                onDismiss = { confirmSignOut = false }
            )
        }
    }

    private suspend fun start() {
        try {
            val loggedIn = repo.start() ||
                // Debug builds only: browse the public parts (library, scores) without an account.
                (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_SKIP_LOGIN, false))
            replaceAll(if (loggedIn) Screen.Home else Screen.Login)
        } catch (e: ChallengeRequiredException) {
            push(Screen.Challenge)
        } catch (e: Exception) {
            replaceAll(
                Screen.Message("Couldn't reach Ultiworld", e.message ?: "Check the TV's internet connection.", "Try again", Screen.Starting)
            )
        }
    }

    private fun onLoginResult(result: LoginResult) {
        when (result) {
            LoginResult.Success -> onLoggedIn()

            // Once the TV is past Cloudflare's check, it's back on the login screen to try again.
            LoginResult.Challenge -> push(Screen.Challenge)

            // The login screen shows the message itself.
            is LoginResult.Failed -> Unit
        }
    }

    private fun onLoggedIn() {
        bridge.flushCookies()
        home.clear()
        search.clear()
        login.reset()
        replaceAll(Screen.Home)
    }

    /** A remote or keyboard with a search key. */
    override fun onSearchRequested(): Boolean {
        if (top is Screen.Home || top is Screen.Collection) push(Screen.Search)
        return true
    }

    private fun open(card: VideoCard) {
        push(if (card.isCollection) Screen.Collection(card.title, card.url) else Screen.Player(card.url, card.title, card.image))
    }

    private fun push(screen: Screen) {
        if (top != screen) stack.add(screen)
    }

    private fun replaceAll(screen: Screen) {
        stack.clear()
        stack.add(screen)
        collections.keepOnly(emptyList())
    }

    private fun goBack() {
        if (stack.size > 1 && top != Screen.Login) stack.removeAt(stack.lastIndex) else finish()
        collections.keepOnly(stack.filterIsInstance<Screen.Collection>().map { it.url })
    }

    /** Puts the WebView in front for Cloudflare's check, and behind the app otherwise. */
    private fun showWeb(screen: Screen) {
        val web = screen == Screen.Challenge
        composeView.visibility = if (web) View.GONE else View.VISIBLE
        bridge.webView.isFocusable = web
        bridge.webView.isFocusableInTouchMode = web
        hint.visibility = if (web) View.VISIBLE else View.GONE
        hint.text = if (web) "Ultiworld is checking this TV.  If a box appears, move to it and press OK." else ""
        if (web) bridge.webView.requestFocus() else composeView.requestFocus()
    }

    private fun onWebPageFinished() {
        if (top != Screen.Challenge) return
        lifecycleScope.launch {
            runCatching { bridge.evaluate(Scripts.REMOTE_FRIENDLY_PAGE, timeoutMs = 5_000) }
            if (!bridge.isChallengePage() && top == Screen.Challenge) stack.removeAt(stack.lastIndex)
        }
    }
}

/** "Brute Squad vs. 6ixers", or null when the feed doesn't name the teams yet. */
private fun Game.matchup(): String? = teams.map { it.name }.filter { it.isNotBlank() }.takeIf { it.size == 2 }?.joinToString(" vs. ")

/** Debug builds: `adb shell am start -n io.github.myronl.ultiworldtv/.MainActivity --ez skip_login true`. */
private const val EXTRA_SKIP_LOGIN = "skip_login"

@Composable
private fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).composeBackground(Palette.SurfaceRaised).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sign out of Ultiworld on this TV?", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text("Cancel") }
                Button(onClick = onConfirm) { Text("Sign out") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
