package io.github.myronl.ultiworldtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.myronl.ultiworldtv.data.Game
import io.github.myronl.ultiworldtv.data.GameVideo

/** A game with more than one stream (its own camera and the shared Field Pass, say): pick which to watch. */
@Composable
fun FeedPicker(game: Game, onPick: (GameVideo) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceRaised).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(game.teams.joinToString(" vs ") { it.name }, style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            Text(game.label(), color = Palette.TextMuted)
            game.videos.forEachIndexed { i, video ->
                Button(
                    onClick = { onPick(video) },
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(focus) else Modifier)
                ) { Text(video.label) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
