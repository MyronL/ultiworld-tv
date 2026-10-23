package local.ultiworldtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import local.ultiworldtv.bridge.ChallengeRequiredException
import local.ultiworldtv.data.VideoCard

/** How long typing has to pause before the results update. */
private const val SEARCH_DEBOUNCE_MS = 400L

/** Searches the video library as you type: teams, players, events, divisions. */
@Composable
fun SearchScreen(model: SearchModel, onOpen: (VideoCard) -> Unit, onChallenge: () -> Unit) {
    val firstResult = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val results = model.results.orEmpty()

    LaunchedEffect(model.query) {
        if (model.query.trim() != model.resultsFor) delay(SEARCH_DEBOUNCE_MS)
        try {
            model.search(model.query)
        } catch (e: ChallengeRequiredException) {
            onChallenge()
        }
    }
    // Back from a video: focus the result that was playing.
    val restoreIndex = remember { model.focusedIndex?.takeIf { it in results.indices } }
    LaunchedEffect(Unit) {
        if (restoreIndex == null) return@LaunchedEffect
        // The grid has to be laid out before it can take focus.
        delay(100)
        runCatching { restore.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(Palette.Background).padding(horizontal = 48.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp)) {
            Text("Search", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
            TvTextField(
                value = model.query,
                onValueChange = { model.query = it },
                label = "Teams, players, events",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
                next = if (results.isNotEmpty()) firstResult else null,
                editOnStart = model.query.isEmpty(),
                doneFocusesNext = true,
                modifier = Modifier.padding(top = 8.dp)
            )
            val status = when {
                model.error != null -> model.error.orEmpty()
                model.searching -> "Searching…"
                model.resultsFor != null && results.isEmpty() -> "No videos match “${model.resultsFor}”."
                model.resultsFor == null -> "Results show up as you type."
                else -> ""
            }
            if (status.isNotEmpty()) {
                Text(status, color = Palette.TextMuted, modifier = Modifier.padding(top = 12.dp))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(results, key = { _, c -> c.url }) { i, card ->
                VideoCardView(
                    card,
                    onClick = {
                        model.focusedIndex = i
                        onOpen(card)
                    },
                    modifier = Modifier
                        .then(if (i == 0) Modifier.focusRequester(firstResult) else Modifier)
                        .then(if (i == restoreIndex) Modifier.focusRequester(restore) else Modifier)
                )
            }
        }
    }
}
