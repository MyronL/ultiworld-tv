package local.ultiworldtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Palette {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val SurfaceRaised = Color(0xFF222833)
    val Teal = Color(0xFF00A3B4)
    val TealDeep = Color(0xFF008392)
    val Red = Color(0xFFD2232A)
    val Text = Color(0xFFE8EAED)
    val TextMuted = Color(0xFF9AA3AE)
    val Focus = Color(0xFFFFFFFF)
}

@Composable
fun UltiworldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Teal,
            onPrimary = Color.Black,
            secondary = Palette.Red,
            onSecondary = Color.White,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.SurfaceRaised,
            onSurfaceVariant = Palette.TextMuted,
            border = Palette.Focus
        ),
        content = content
    )
}
