package io.github.myronl.ultiworldtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.myronl.ultiworldtv.bridge.ChallengeRequiredException
import io.github.myronl.ultiworldtv.data.UltiworldRepository
import io.github.myronl.ultiworldtv.data.VideoCard
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/** Every video in an event, team pack or feature: the library page filtered the way the site's own links filter it. */
@Composable
fun CollectionScreen(
    repo: UltiworldRepository,
    model: CollectionModel,
    title: String,
    url: String,
    onOpen: (VideoCard) -> Unit,
    onChallenge: () -> Unit
) {
    val entry = remember(url) { model.entry(url) }
    var attempt by remember(url) { mutableIntStateOf(0) }

    LaunchedEffect(url, attempt) {
        try {
            entry.cards = repo.collection(url)
            entry.error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            onChallenge()
        } catch (e: Exception) {
            // Keep showing what we had; only an empty screen needs the error.
            if (entry.cards == null) entry.error = e.message ?: "Couldn't load these videos"
        }
    }

    val list = entry.cards
    val error = entry.error
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when {
            error != null -> CenteredMessage("Couldn't load $title", error, action = "Try again", onAction = {
                entry.error = null
                attempt++
            })

            list == null -> CollectionLoading(title)

            list.isEmpty() -> CenteredMessage(title, "No videos here yet.")

            else -> {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    delay(100)
                    runCatching { focus.requestFocus() }
                }
                LazyVerticalGrid(
                    state = entry.gridState,
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
                    val focusIndex = entry.focusedIndex.coerceAtMost(list.lastIndex)
                    itemsIndexed(list, key = { _, c -> c.url }) { i, card ->
                        VideoCardView(
                            // Every card names this page's event; the division is the useful part here.
                            if (card.event == title) card.copy(event = null) else card,
                            onClick = {
                                entry.focusedIndex = i
                                onOpen(card)
                            },
                            modifier = if (i == focusIndex) Modifier.focusRequester(focus) else Modifier
                        )
                    }
                }
            }
        }
    }
}

/** The page's title above two rows of placeholder cards, in the grid the videos will fill. */
@Composable
private fun CollectionLoading(title: String) {
    val brush = shimmer()
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Palette.Text, modifier = Modifier.padding(bottom = 40.dp))
        repeat(2) {
            Row(Modifier.padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                repeat(4) { PlaceholderCard(brush, Modifier.weight(1f)) }
            }
        }
    }
}
