package local.ultiworldtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import local.ultiworldtv.bridge.ChallengeRequiredException
import local.ultiworldtv.data.UltiworldRepository
import local.ultiworldtv.data.VideoCard

/** Every video in an event, team pack or feature: the library page filtered the way the site's own links filter it. */
@Composable
fun CollectionScreen(repo: UltiworldRepository, title: String, url: String, onOpen: (VideoCard) -> Unit, onChallenge: () -> Unit) {
    var cards by remember(url) { mutableStateOf<List<VideoCard>?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    var focusedIndex by rememberSaveable(url) { mutableIntStateOf(0) }

    LaunchedEffect(url, attempt) {
        try {
            cards = repo.collection(url)
        } catch (e: ChallengeRequiredException) {
            onChallenge()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load these videos"
        }
    }

    val list = cards
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when {
            error != null -> CenteredMessage("Couldn't load $title", error.orEmpty(), action = "Try again", onAction = {
                error = null
                attempt++
            })
            list == null -> CenteredMessage("Loading $title…", "")
            list.isEmpty() -> CenteredMessage(title, "No videos here yet.")
            else -> {
                val focus = remember { FocusRequester() }
                LaunchedEffect(list) {
                    delay(100)
                    runCatching { focus.requestFocus() }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Palette.Text,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    itemsIndexed(list, key = { _, c -> c.url }) { i, card ->
                        VideoCardView(
                            card,
                            onClick = {
                                focusedIndex = i
                                onOpen(card)
                            },
                            modifier = if (i == focusedIndex.coerceAtMost(list.lastIndex)) Modifier.focusRequester(focus) else Modifier
                        )
                    }
                }
            }
        }
    }
}
