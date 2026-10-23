package io.github.myronl.ultiworldtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * A text field that behaves with a remote. Moving onto it only highlights it, so the on-screen keyboard
 * doesn't pop up (and cover the QR code) on the way past; OK starts typing. The keyboard's Next/Done key,
 * BACK, or up/down end typing.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    // Where ▼ goes from this field; Compose's own guess can skip the button right below.
    next: FocusRequester? = null,
    // Start typing as soon as the field appears, keyboard up.
    editOnStart: Boolean = false,
    // The keyboard's Done key also moves on to [next], rather than staying on this field.
    doneFocusesNext: Boolean = false,
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
    fun done() {
        stopEditing(if (doneFocusesNext && next != null) FocusDirection.Down else null)
        onDone()
    }

    LaunchedEffect(Unit) { if (editOnStart) editing = true }
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

                        // Enter on a hardware keyboard. Act on its release: acting on the press would move focus
                        // first, and the release would then click whatever got focus.
                        editing && isOk -> {
                            if (e.type == KeyEventType.KeyUp) done()
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
                    onDone = { done() },
                    onSearch = { done() }
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
