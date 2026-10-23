package io.github.myronl.ultiworldtv.data

import java.time.OffsetDateTime

/** A card in one of the video library's rows: a video, or a collection (event, team pack, feature) of them. */
data class VideoCard(
    val title: String,
    val url: String,
    /** A TV-sized copy of the thumbnail when WordPress has one, else the original upload. */
    val image: String?,
    /** The original upload, for when [image] turns out to be missing. Null when [image] is already the original. */
    val imageFallback: String?,
    /** "LIVE", a start time like "11:15AM PDT", a duration like "1:21:12", or a date on search results. */
    val badge: String?,
    val isLive: Boolean,
    val event: String?,
    val division: String?,
    val isCollection: Boolean
)

data class Shelf(val title: String, val items: List<VideoCard>)

data class Library(val shelves: List<Shelf>)

data class Team(val name: String, val seed: Int?, val score: Int?, val logo: String?, val isWinner: Boolean)

enum class GameStatus { LIVE, SCHEDULED, FINAL, OTHER }

/** One way to watch a game, e.g. "Game Feed" or "Field Pass". */
data class GameVideo(val label: String, val url: String)

data class Game(
    val id: String,
    val status: GameStatus,
    val division: String?,
    /** "Pool A", "Quarterfinal", ... */
    val name: String?,
    val field: String?,
    val start: OffsetDateTime?,
    val teams: List<Team>,
    val videos: List<GameVideo>
)

data class EventScores(val id: Long, val title: String, val url: String?, val day: String?, val games: List<Game>)

data class Scores(val events: List<EventScores>, val pollSeconds: Int) {
    val games: List<Game> get() = events.flatMap { it.games }.distinctBy { it.id }
}

/** What the app needs to play a stream from Mux. */
data class Playback(val playbackId: String, val token: String?, val title: String, val isLive: Boolean) {
    val streamUrl: String
        get() = "https://stream.mux.com/$playbackId.m3u8" + (token?.let { "?token=$it" } ?: "")
}

sealed interface PlaybackResult {
    data class Ready(val playback: Playback) : PlaybackResult

    /** The page still shows the paywall: this account's plan doesn't include the video. */
    data class Locked(val message: String) : PlaybackResult

    /** A video that hasn't started yet. [message] is the site's countdown, e.g. "will begin streaming in 22 hours". */
    data class Upcoming(val message: String) : PlaybackResult

    /** Ultiworld stopped this device because the account is streaming on too many others. */
    data class Stopped(val message: String) : PlaybackResult

    /** No player on the page: an upcoming video whose stream isn't set up yet, or a page the app doesn't understand. */
    data object Missing : PlaybackResult
}

sealed interface LoginResult {
    data object Success : LoginResult

    /** Ultiworld said no, e.g. a wrong password. [message] is Ultiworld's own wording when there is one. */
    data class Failed(val message: String) : LoginResult

    /** Cloudflare wants to check the TV before the login page loads: put the check on screen, then try again. */
    data object Challenge : LoginResult
}

data class HeartbeatResult(val sessionValid: Boolean, val stopStreaming: Boolean)
