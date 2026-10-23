package local.ultiworldtv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import local.ultiworldtv.data.Game
import local.ultiworldtv.data.GameStatus
import local.ultiworldtv.data.Team
import local.ultiworldtv.data.VideoCard

private val CardShape = RoundedCornerShape(10.dp)

@Composable
private fun focusBorder() = CardDefaults.border(
    focusedBorder = Border(border = BorderStroke(3.dp, Palette.Focus), shape = CardShape)
)

@Composable
fun VideoCardView(card: VideoCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(264.dp)) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(CardShape),
            border = focusBorder(),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            colors = CardDefaults.colors(containerColor = Palette.Surface)
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(
                    model = card.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                card.badge?.let { Badge(it, live = card.isLive, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.titleSmall,
            color = Palette.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val sub = listOfNotNull(card.event, card.division).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Badge(text: String, live: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (live) Palette.Red else Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (live) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
            Spacer(Modifier.width(6.dp))
        }
        Text(text.uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

private val DivisionShort = mapOf("Women's" to "W", "Mixed" to "X", "Men's" to "M", "Open" to "O", "Girls" to "G", "Boys" to "B")

fun Game.statusText(): String = when (status) {
    GameStatus.LIVE -> "Live"
    GameStatus.FINAL -> "Final"
    GameStatus.SCHEDULED -> start?.atZoneSameInstant(ZoneId.systemDefault())?.format(TimeFormat) ?: "Upcoming"
    GameStatus.OTHER -> ""
}

fun Game.label(): String = listOfNotNull(division?.let { DivisionShort[it] ?: it }, name).joinToString(" · ")

@Composable
fun GameCardView(game: Game, hideScores: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.width(264.dp),
        shape = CardDefaults.shape(CardShape),
        border = focusBorder(),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        colors = CardDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.SurfaceRaised)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (game.status == GameStatus.LIVE) {
                    Badge("Live", live = true)
                } else {
                    Text(game.statusText().uppercase(), color = Palette.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(game.label(), color = Palette.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val showScore = !hideScores && game.status != GameStatus.SCHEDULED
            game.teams.forEach { TeamRow(it, showScore = showScore, dim = game.status == GameStatus.FINAL && !it.isWinner && !hideScores) }
            val feeds = game.videos.joinToString(" · ") { it.label }
            Text(
                if (feeds.isEmpty()) "No stream" else "▶ $feeds",
                color = if (feeds.isEmpty()) Palette.TextMuted else Palette.Teal,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TeamRow(team: Team, showScore: Boolean, dim: Boolean) {
    val color = if (dim) Palette.TextMuted else Palette.Text
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = team.logo,
            contentDescription = null,
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.width(10.dp))
        team.seed?.let {
            Text("$it", color = Palette.TextMuted, fontSize = 12.sp, modifier = Modifier.width(20.dp))
        }
        Text(
            team.name,
            color = color,
            fontSize = 17.sp,
            fontWeight = if (team.isWinner && showScore) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (showScore) {
            Text(team.score?.toString() ?: "–", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
