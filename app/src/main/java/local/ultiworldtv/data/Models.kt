package local.ultiworldtv.data

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

data class Team(val name: String, val fullName: String?, val seed: Int?, val score: Int?, val logo: String?, val isWinner: Boolean)

enum class GameStatus { LIVE, SCHEDULED, FINAL, OTHER }

/** One way to watch a game, e.g. "Game Feed" or "Field Pass". */
data class GameVideo(val label: String, val url: String, val state: String?)

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
    val games: List<Game> get() = events.flatMap { it.games }
}

/** What the app needs to play a stream from Mux. */
data class Playback(val playbackId: String, val token: String?, val title: String, val isLive: Boolean, val poster: String?) {
    val streamUrl: String
        get() = "https://stream.mux.com/$playbackId.m3u8" + (token?.let { "?token=$it" } ?: "")
}

sealed interface PlaybackResult {
    data class Ready(val playback: Playback) : PlaybackResult

    /** The page still shows the paywall: this account's plan doesn't include the video. */
    data class Locked(val message: String) : PlaybackResult

    /** Ultiworld stopped this device because the account is streaming on too many others. */
    data class Stopped(val message: String) : PlaybackResult

    /** No player on the page: an upcoming video whose stream isn't set up yet, or a page the app doesn't understand. */
    data object Missing : PlaybackResult
}

sealed interface LoginResult {
    data object Success : LoginResult

    /** Ultiworld said no, e.g. a wrong password. [message] is Ultiworld's own wording when there is one. */
    data class Failed(val message: String) : LoginResult

    /** Ultiworld wants something the app can't do for you (a captcha, a changed page): use its own page. */
    data class NeedsWebPage(val reason: String) : LoginResult
}

/**
 * The site's answer to a device-limit check-in. [checkedIn] is false when the check-in didn't go through
 * (an error page, say), so it can't be counted as one.
 */
data class HeartbeatResult(val sessionValid: Boolean, val stopStreaming: Boolean, val checkedIn: Boolean)
