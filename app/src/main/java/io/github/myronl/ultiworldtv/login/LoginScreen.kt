package io.github.myronl.ultiworldtv.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.myronl.ultiworldtv.data.LoginResult
import io.github.myronl.ultiworldtv.ui.BrandMark
import io.github.myronl.ultiworldtv.ui.Palette
import io.github.myronl.ultiworldtv.ui.TvTextField
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's own login: email and password typed with the remote, or sent from a phone through the QR code.
 * Both go through Ultiworld's real login form ([io.github.myronl.ultiworldtv.data.UltiworldRepository.logIn]).
 */
@Composable
fun LoginScreen(model: LoginModel, onResult: (LoginResult) -> Unit) {
    val context = LocalContext.current
    // Outlives this screen: after a phone login the TV moves on, but the phone still needs its answer.
    val appScope = LocalLifecycleOwner.current.lifecycleScope
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    val loginButtonFocus = remember { FocusRequester() }

    val address = remember { lanAddress(context) }
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    // Bumped when the phone server locks itself out after too many tries, to start a new one with a new code.
    var phoneServerRun by remember { mutableIntStateOf(0) }
    // Only listen while the screen is in front: Home on the remote stops it, and coming back starts a new code.
    val visible = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(address, phoneServerRun, visible) {
        val server = address?.takeIf { visible }?.let {
            PhoneLoginServer(
                appScope,
                bindAddress = InetAddress.getByName(it),
                onLockedOut = { appScope.launch { phoneServerRun++ } }
            ) { u, p ->
                model.logIn(u, p, viaPhone = true).also { result -> withContext(Dispatchers.Main) { onResult(result) } }
            }
        }
        phoneUrl = server?.let { "http://$address:${it.start()}/?k=${it.code}" }
        onDispose { server?.stop() }
    }

    fun submit() {
        if (model.busy) return
        appScope.launch {
            val result = model.logIn(username.trim(), password, viaPhone = false)
            if (result != LoginResult.Success) password = ""
            onResult(result)
        }
    }

    LaunchedEffect(Unit) { runCatching { userFocus.requestFocus() } }

    Row(
        Modifier.fillMaxSize().background(Palette.Background).padding(horizontal = 72.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(72.dp)
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            BrandMark()
            Spacer(Modifier.height(20.dp))
            Text("Log in with your Ultiworld account", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            Text(
                "This TV stays logged in. Your password goes only to ultiworld.com and isn't stored.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted
            )
            Text(
                "Ultiworld TV is an unofficial app. It isn't made by or affiliated with Ultiworld.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted
            )
            Spacer(Modifier.height(24.dp))
            TvTextField(
                value = username,
                onValueChange = { username = it },
                label = "Email or username",
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                modifier = Modifier.focusRequester(userFocus)
            )
            Spacer(Modifier.height(16.dp))
            TvTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                isPassword = true,
                next = loginButtonFocus,
                onDone = ::submit
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = ::submit, modifier = Modifier.focusRequester(loginButtonFocus)) {
                Text(if (model.busy) "Logging in…" else "Log in")
            }
            Spacer(Modifier.height(16.dp))
            when {
                model.busy && model.fromPhone -> Text("Logging in with the details from your phone…", color = Palette.Teal)
                model.error != null -> Text(model.error.orEmpty(), color = Color(0xFFFF8A8F))
            }
        }

        phoneUrl?.let { url ->
            PhonePanel(url, Modifier.width(340.dp).align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun PhonePanel(url: String, modifier: Modifier = Modifier) {
    val qr = remember(url) { qrCode(url, 480) }
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Or use your phone", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)) {
            Image(bitmap = qr, contentDescription = "QR code for logging in from your phone", modifier = Modifier.size(190.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Scan with your phone's camera and log in there. Your phone needs to be on the same Wi-Fi as the TV.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted
        )
        Spacer(Modifier.height(8.dp))
        Text(url.removePrefix("http://"), style = MaterialTheme.typography.bodySmall, color = Palette.Teal)
    }
}
