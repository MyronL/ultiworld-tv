package local.ultiworldtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import local.ultiworldtv.bridge.ChallengeRequiredException
import local.ultiworldtv.data.Game
import local.ultiworldtv.data.GameStatus
import local.ultiworldtv.data.VideoCard

private const val LIBRARY_REFRESH_MS = 3 * 60_000L

/** One row of the home screen. */
private sealed interface HomeRow {
    val key: String
    val title: String

    data class Videos(override val key: String, override val title: String, val cards: List<VideoCard>) : HomeRow
    data class Games(override val key: String, override val title: String, val games: List<Game>) : HomeRow
}

@Composable
fun HomeScreen(model: HomeModel, onOpen: (VideoCard) -> Unit, onGame: (Game) -> Unit, onSignOut: () -> Unit, onChallenge: () -> Unit) {
    LaunchedEffect(model.reloadToken) {
        while (true) {
            try {
                model.refreshLibrary()
            } catch (e: ChallengeRequiredException) {
                onChallenge()
                return@LaunchedEffect
            }
            delay(LIBRARY_REFRESH_MS)
        }
    }
    LaunchedEffect(model.reloadToken) {
        while (true) {
            try {
                model.refreshScores()
            } catch (e: ChallengeRequiredException) {
                return@LaunchedEffect
            }
            delay((model.scores?.pollSeconds ?: 60).coerceAtLeast(30) * 1000L)
        }
    }

    val library = model.library
    val rows = remember(library, model.scores) { buildRows(model) }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when {
            library == null && model.error != null -> CenteredMessage(
                title = "Couldn't load Ultiworld",
                body = model.error.orEmpty(),
                action = "Try again",
                onAction = model::retry
            )
            library == null -> CenteredMessage(title = "Loading the video library…", body = "")
            else -> HomeRows(model, rows, onOpen, onGame, onSignOut)
        }
    }
}

@Composable
private fun HomeRows(model: HomeModel, rows: List<HomeRow>, onOpen: (VideoCard) -> Unit, onGame: (Game) -> Unit, onSignOut: () -> Unit) {
    val restore = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val firstKey = rows.firstOrNull()?.let { row ->
        when (row) {
            is HomeRow.Videos -> row.key + "|" + row.cards.first().url
            is HomeRow.Games -> row.key + "|" + row.games.first().id
        }
    }
    val target = model.focusedKey?.takeIf { key -> rows.any { row -> row.contains(key) } }

    LaunchedEffect(target ?: firstKey) {
        // The row has to be laid out before it can take focus.
        delay(100)
        runCatching { if (target != null) restore.requestFocus() else first.requestFocus() }
    }

    fun Modifier.focusHooks(key: String): Modifier = this
        .then(if (key == target) Modifier.focusRequester(restore) else Modifier)
        .then(if (target == null && key == firstKey) Modifier.focusRequester(first) else Modifier)
        .onFocusChanged { if (it.isFocused) model.focusedKey = key }

    LazyColumn(
        state = model.listState,
        contentPadding = PaddingValues(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "header") { Header(model, onSignOut) }
        if (rows.isEmpty()) {
            item(key = "empty") {
                Text("Nothing in the video library right now.", color = Palette.TextMuted, modifier = Modifier.padding(horizontal = 48.dp))
            }
        }
        items(rows, key = { it.key }) { row ->
            Column {
                Text(
                    row.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Palette.Text,
                    modifier = Modifier.padding(start = 48.dp, bottom = 4.dp)
                )
                LazyRow(
                    state = model.rowState(row.key),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    when (row) {
                        is HomeRow.Videos -> items(row.cards, key = { it.url }) { card ->
                            VideoCardView(card, onClick = { onOpen(card) }, modifier = Modifier.focusHooks(row.key + "|" + card.url))
                        }
                        is HomeRow.Games -> items(row.games, key = { it.id }) { game ->
                            GameCardView(
                                game,
                                hideScores = model.hideScores,
                                onClick = { onGame(game) },
                                modifier = Modifier.focusHooks(row.key + "|" + game.id)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(model: HomeModel, onSignOut: () -> Unit) {
    Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ULTIWORLD", color = Palette.Red, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Text("TV", color = Palette.Teal, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = model::toggleHideScores) { Text(if (model.hideScores) "Show scores" else "Hide scores") }
        Spacer(Modifier.width(16.dp))
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
fun CenteredMessage(
    title: String,
    body: String,
    action: String? = null,
    onAction: () -> Unit = {},
    secondary: String? = null,
    onSecondary: () -> Unit = {}
) {
    val focus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(Palette.Background).padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        if (action != null || secondary != null) {
            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                action?.let { Button(onClick = onAction, modifier = Modifier.focusRequester(focus)) { Text(it) } }
                secondary?.let { OutlinedButton(onClick = onSecondary) { Text(it) } }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

private fun HomeRow.contains(focusKey: String): Boolean = focusKey.startsWith("$key|") &&
    when (this) {
        is HomeRow.Videos -> cards.any { focusKey == "$key|${it.url}" }
        is HomeRow.Games -> games.any { focusKey == "$key|${it.id}" }
    }

/**
 * The library's own rows in the site's order (Live Now, Upcoming Videos, Recent Videos, Recent Events,
 * Team Packs, Special Features), with today's games from the score feed slotted in: live games after
 * "Live Now", upcoming games and results after "Recent Videos".
 */
private fun buildRows(model: HomeModel): List<HomeRow> {
    val shelves = model.library?.shelves.orEmpty()
    val games = model.scores?.games.orEmpty()
    val live = games.filter { it.status == GameStatus.LIVE }
    val upcoming = games.filter { it.status == GameStatus.SCHEDULED && it.videos.isNotEmpty() }.sortedBy { it.start }
    val results = games.filter { it.status == GameStatus.FINAL }.sortedByDescending { it.start }

    // Each game row follows the library shelf named first in the pair.
    val gameRows = buildList {
        if (live.isNotEmpty()) add("Live Now" to HomeRow.Games("games-live", "Games in progress", live))
        if (upcoming.isNotEmpty()) add("Recent Videos" to HomeRow.Games("games-upcoming", "Coming up today", upcoming))
        if (results.isNotEmpty()) add("Recent Videos" to HomeRow.Games("games-final", "Today's results", results))
    }
    val placed = mutableSetOf<String>()
    val out = mutableListOf<HomeRow>()
    shelves.forEachIndexed { i, shelf ->
        out += HomeRow.Videos("shelf-$i", shelf.title.ifBlank { "Videos" }, shelf.items)
        gameRows.filter { it.first.equals(shelf.title, ignoreCase = true) }.forEach {
            out += it.second
            placed += it.second.key
        }
    }
    // When the anchor shelf isn't on the page today: live games go first, the rest at the end.
    gameRows.map { it.second }.filter { it.key !in placed }.forEach { row ->
        if (row.key == "games-live") out.add(0, row) else out += row
    }
    return out
}
