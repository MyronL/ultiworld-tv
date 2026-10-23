package local.ultiworldtv.data

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

/** Turns what [local.ultiworldtv.bridge.Scripts] returns into the app's models. Kept free of Android so it is unit-tested. */
object Parsing {
    private val SIZE_SUFFIX = Regex("-\\d{2,4}x\\d{2,4}(?=\\.(?:jpe?g|png|webp)$)", RegexOption.IGNORE_CASE)

    /** WordPress serves "photo-300x169.jpg" thumbnails; a TV card looks better with the full-size "photo.jpg". */
    fun fullSizeImage(url: String?): String? = url?.takeUnless { it.startsWith("data:") || it.isBlank() }?.replace(SIZE_SUFFIX, "")

    fun library(v: Any?): Library {
        val o = v as? JSONObject ?: return Library(loggedIn = false, shelves = emptyList())
        val shelves = o.optJSONArray("shelves").objects().map { s ->
            Shelf(title = s.optString("title"), items = s.optJSONArray("items").objects().map(::card))
        }
        return Library(loggedIn = o.optBoolean("loggedIn"), shelves = shelves.filter { it.items.isNotEmpty() })
    }

    fun cards(v: Any?): List<VideoCard> = (v as? JSONArray).objects().map(::card)

    fun card(o: JSONObject) = VideoCard(
        title = o.optString("title").trim(),
        url = o.optString("url"),
        image = fullSizeImage(o.stringOrNull("image")),
        badge = o.stringOrNull("badge"),
        isLive = o.optBoolean("live"),
        event = o.stringOrNull("event"),
        division = o.stringOrNull("division"),
        isCollection = o.optBoolean("collection")
    )

    fun scores(v: Any?): Scores {
        val o = v as? JSONObject ?: return Scores(emptyList(), pollSeconds = 60)
        val events = o.optJSONArray("events").objects().map { e ->
            EventScores(
                id = e.optLong("id"),
                title = e.optString("title"),
                url = e.stringOrNull("url"),
                day = e.stringOrNull("day"),
                games = e.optJSONArray("games").objects().map(::game)
            )
        }
        return Scores(events, pollSeconds = o.optInt("poll", 60).coerceAtLeast(20))
    }

    fun game(o: JSONObject) = Game(
        id = o.optString("id"),
        status = status(o.optString("status")),
        division = o.stringOrNull("division"),
        name = o.stringOrNull("name") ?: o.stringOrNull("pool"),
        field = o.stringOrNull("field"),
        start = o.stringOrNull("start")?.let(::parseTime),
        teams = o.optJSONArray("teams").objects().map { t ->
            Team(
                name = t.optString("name"),
                fullName = t.stringOrNull("full_name"),
                seed = t.intOrNull("seed"),
                score = t.intOrNull("score"),
                logo = t.stringOrNull("logo"),
                isWinner = t.optBoolean("winner")
            )
        },
        videos = o.optJSONArray("videos").objects().mapNotNull { vid ->
            val url = vid.stringOrNull("url") ?: return@mapNotNull null
            GameVideo(
                label = vid.stringOrNull("label") ?: vid.stringOrNull("production_level") ?: "Watch",
                url = url,
                state = vid.stringOrNull("state")
            )
        }
    )

    fun status(s: String): GameStatus = when (s.lowercase()) {
        "in_progress", "live", "halftime", "in-progress" -> GameStatus.LIVE
        "scheduled", "upcoming", "pending" -> GameStatus.SCHEDULED
        "final", "completed", "complete", "forfeit" -> GameStatus.FINAL
        else -> GameStatus.OTHER
    }

    fun playback(v: Any?): PlaybackResult {
        val o = v as? JSONObject ?: return PlaybackResult.Missing
        return when (o.optString("status")) {
            "ok" -> {
                val id = o.stringOrNull("playbackId") ?: return PlaybackResult.Missing
                PlaybackResult.Ready(
                    Playback(
                        playbackId = id,
                        token = o.stringOrNull("token"),
                        title = o.optString("title"),
                        isLive = o.optString("streamType").startsWith("live"),
                        poster = o.stringOrNull("poster")
                    )
                )
            }
            "locked" -> PlaybackResult.Locked(o.optString("message"))
            "stopped" -> PlaybackResult.Stopped(o.optString("message"))
            else -> PlaybackResult.Missing
        }
    }

    fun heartbeat(v: Any?): HeartbeatResult {
        val o = v as? JSONObject ?: return HeartbeatResult(sessionValid = true, stopStreaming = false)
        return HeartbeatResult(sessionValid = o.optBoolean("sessionValid", true), stopStreaming = o.optBoolean("stopStreaming"))
    }

    private fun parseTime(s: String): OffsetDateTime? = try {
        OffsetDateTime.parse(s)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this ==
        null
    ) {
        emptyList()
    } else {
        (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.intOrNull(key: String): Int? = when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}
