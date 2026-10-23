package local.ultiworldtv.data

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** Turns what [local.ultiworldtv.bridge.Scripts] returns into the app's models. Kept free of Android so it is unit-tested. */
object Parsing {
    private val SIZE_SUFFIX = Regex("-(\\d{2,4})x(\\d{2,4})(?=\\.(?:jpe?g|png|webp)$)", RegexOption.IGNORE_CASE)

    /**
     * The image for a card, and what to fall back to. WordPress serves "photo-300x169.jpg" thumbnails of a
     * "photo.jpg" upload. The thumbnail looks soft on a TV and the original is often 250 KB or more, so a 16:9
     * thumbnail becomes WordPress's 768-pixel copy, "photo-768x432.jpg", about a fifth of the original's size.
     * Uploads narrower than 768 pixels have no such copy, hence the fallback.
     */
    fun cardImage(url: String?): Pair<String?, String?> {
        val thumb = url?.takeUnless { it.startsWith("data:") || it.isBlank() } ?: return null to null
        val size = SIZE_SUFFIX.find(thumb) ?: return thumb to null
        val (w, h) = size.destructured.let { (w, h) -> w.toInt() to h.toInt() }
        if (w >= 768) return thumb to null
        val full = thumb.replaceRange(size.range, "")
        // WordPress rounds the height, so a 16:9 thumbnail is within half a pixel of 9/16 of its width.
        if (abs(w * 9 - h * 16) > 8) return full to null
        return thumb.replaceRange(size.range, "-768x432") to full
    }

    /** What [local.ultiworldtv.bridge.Scripts.LIBRARY] and friends return when Cloudflare wants to check first. */
    fun isChallenge(v: Any?): Boolean = (v as? JSONObject)?.optBoolean("challenge") == true

    fun library(v: Any?): Library {
        val o = v as? JSONObject ?: return Library(shelves = emptyList())
        val shelves = o.optJSONArray("shelves").objects().map { s ->
            Shelf(title = s.optString("title"), items = s.optJSONArray("items").objects().map(::card))
        }
        return Library(shelves = shelves.filter { it.items.isNotEmpty() })
    }

    /** A collection page's cards (`{ items: [...] }`) or search results (a plain array). */
    fun cards(v: Any?): List<VideoCard> = ((v as? JSONObject)?.optJSONArray("items") ?: v as? JSONArray).objects().map(::card)

    fun card(o: JSONObject): VideoCard {
        val (image, fallback) = cardImage(o.stringOrNull("image"))
        return VideoCard(
            title = o.optString("title").trim(),
            url = o.optString("url"),
            image = image,
            imageFallback = fallback,
            badge = o.stringOrNull("badge"),
            isLive = o.optBoolean("live"),
            event = o.stringOrNull("event"),
            division = o.stringOrNull("division"),
            isCollection = o.optBoolean("collection")
        )
    }

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
