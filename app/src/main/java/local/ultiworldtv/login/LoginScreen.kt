package local.ultiworldtv.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ultiworldtv.data.LoginResult
import local.ultiworldtv.ui.Palette

/**
 * The app's own login: email and password typed with the remote, or sent from a phone through the QR code.
 * Both go through Ultiworld's real login form ([local.ultiworldtv.data.UltiworldRepository.logIn]).
 */
@Composable
fun LoginScreen(model: LoginModel, onResult: (LoginResult) -> Unit, onUseWebPage: () -> Unit) {
    val context = LocalContext.current
    // Outlives this screen: after a phone login the TV moves on, but the phone still needs its answer.
    val appScope = LocalLifecycleOwner.current.lifecycleScope
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    val loginButtonFocus = remember { FocusRequester() }

    val address = remember { lanAddress(context) }
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    DisposableEffect(address) {
        val server = address?.let {
            PhoneLoginServer(appScope) { u, p ->
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ULTIWORLD", color = Palette.Red, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(8.dp))
                Text("TV", color = Palette.Teal, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
            Text("Log in with your Ultiworld account", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            Text(
                "This TV stays logged in. Your password isn't stored.",
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = ::submit, modifier = Modifier.focusRequester(loginButtonFocus)) {
                    Text(if (model.busy) "Logging in…" else "Log in")
                }
                OutlinedButton(onClick = onUseWebPage) { Text("Use Ultiworld's login page") }
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

/**
 * A text field that behaves with a remote. Moving onto it only highlights it, so the on-screen keyboard
 * doesn't pop up (and cover the QR code) on the way past; OK starts typing. The keyboard's Next/Done key,
 * BACK, or up/down end typing.
 */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    // Where ▼ goes from this field; Compose's own guess can skip the button right below.
    next: FocusRequester? = null,
    onDone: () -> Unit = {}
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf(false) }
    // Where focus goes once typing ends: back onto this field, or on to the next control.
    var after by remember { mutableStateOf<FocusDirection?>(null) }
    // False when focus already left by itself (a hardware Tab, say): then nothing needs moving.
    var refocus by remember { mutableStateOf(true) }

    fun stopEditing(then: FocusDirection? = null) {
        keyboard?.hide()
        after = then
        refocus = true
        editing = false
    }

    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        } else if (wasEditing && refocus) {
            // Only after typing: on first composition every field would otherwise grab focus.
            runCatching { boxFocus.requestFocus() }
            // Let focus land on the box before moving on from it.
            withFrameNanos { }
            when {
                after == FocusDirection.Down && next != null -> runCatching { next.requestFocus() }
                after != null -> focusManager.moveFocus(after!!)
            }
            after = null
        }
        wasEditing = editing
    }
    BackHandler(enabled = editing) { stopEditing() }

    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = if (highlighted || editing) Palette.Text else Palette.TextMuted, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier
                .fillMaxWidth()
                .focusRequester(boxFocus)
                .focusProperties { if (next != null) down = next }
                .onFocusChanged { highlighted = it.isFocused }
                .onPreviewKeyEvent { e ->
                    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    when {
                        !editing && isOk -> {
                            if (e.type == KeyEventType.KeyUp) editing = true
                            true
                        }
                        editing && (e.key == Key.DirectionDown || e.key == Key.DirectionUp) -> {
                            if (e.type == KeyEventType.KeyDown) {
                                stopEditing(if (e.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .clip(shape)
                .background(Palette.Surface)
                .border(if (highlighted || editing) BorderStroke(3.dp, Palette.Focus) else BorderStroke(1.dp, Color(0xFF39414D)), shape)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 20.sp),
                cursorBrush = SolidColor(if (editing) Palette.Teal else Color.Transparent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(
                    onNext = { stopEditing(FocusDirection.Down) },
                    onDone = {
                        stopEditing()
                        onDone()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = editing }
                    .focusRequester(fieldFocus)
                    .onFocusChanged {
                        if (!it.isFocused && editing) {
                            keyboard?.hide()
                            refocus = false
                            editing = false
                        }
                    }
            )
            if (value.isEmpty() && !editing) {
                Text("Press OK to type", color = Color(0xFF5F6874), fontSize = 20.sp, maxLines = 1)
            }
        }
    }
}
