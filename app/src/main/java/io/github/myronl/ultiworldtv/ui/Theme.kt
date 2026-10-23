package io.github.myronl.ultiworldtv.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

object Palette {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val SurfaceRaised = Color(0xFF222833)
    val Teal = Color(0xFF00A3B4)
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

/** The app's name as it heads the home and login screens, marked unofficial: it isn't Ultiworld's own app. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("ULTIWORLD", color = Palette.Red, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Text("TV", color = Palette.Teal, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Text("UNOFFICIAL", color = Palette.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
    }
}
