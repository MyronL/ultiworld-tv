package local.ultiworldtv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    @Test
    fun `card image is the 768 pixel copy of a 16 by 9 thumbnail, falling back to the original`() {
        val base = "https://cdn.ultiworld.com/wordpress/wp-content/uploads/2026/10/D1-Field-Pass"
        assertEquals(("$base-768x432.jpg" to "$base.jpg"), Parsing.cardImage("$base-300x169.jpg"))
        assertEquals(("https://x/a-768x432.png" to "https://x/a.png"), Parsing.cardImage("https://x/a-150x84.png"))
    }

    @Test
    fun `card image keeps other shapes and big thumbnails as they are`() {
        // Not 16:9: the 768-pixel copy's height can't be known, so use the original.
        assertEquals(("https://x/a.jpg" to null), Parsing.cardImage("https://x/a-300x200.jpg"))
        assertEquals(("https://x/a-1024x576.jpg" to null), Parsing.cardImage("https://x/a-1024x576.jpg"))
        assertEquals(("https://x/a-1-001.png" to null), Parsing.cardImage("https://x/a-1-001.png"))
        assertEquals((null to null), Parsing.cardImage("data:image/gif;base64,R0lGOD"))
        assertEquals((null to null), Parsing.cardImage(null))
    }

    @Test
    fun `collection items and search results`() {
        val hit = """{"title": "[Showcase] DiG vs. Revolver", "url": "https://ultiworld.com/video/203402/x/", "image": null,
            "badge": "Oct 23, 2026", "live": false, "event": "Club Nationals 2026", "division": "USAU Club Men's", "collection": false}"""
        assertEquals(listOf("[Showcase] DiG vs. Revolver"), Parsing.cards(JSONObject("""{"items": [$hit]}""")).map { it.title })
        val results = Parsing.cards(JSONArray("[$hit]"))
        assertEquals("Oct 23, 2026", results.single().badge)
        assertEquals("USAU Club Men's", results.single().division)
        assertTrue(Parsing.cards(JSONObject("""{"challenge": true}""")).isEmpty())
    }

    @Test
    fun `cloudflare challenge answer`() {
        assertTrue(Parsing.isChallenge(JSONObject("""{"challenge": true}""")))
        assertFalse(Parsing.isChallenge(JSONObject("""{"shelves": []}""")))
        assertFalse(Parsing.isChallenge(JSONArray("[]")))
        assertFalse(Parsing.isChallenge(null))
    }

    @Test
    fun `library shelves and cards`() {
        val v = JSONObject(
            """
            {"shelves": [
              {"title": "Live Now", "items": [
                {"title": "[Showcase] Tower vs. Drag’n Thrust", "url": "https://ultiworld.com/video/203399/showcase/",
                 "image": "https://cdn.ultiworld.com/u/D1R1F1-300x169.jpg", "badge": "LIVE", "live": true,
                 "event": "Club National Championships 2026", "division": "USAU Club Mixed", "collection": false}
              ]},
              {"title": "Recent Events", "items": [
                {"title": "TSGMUT 2026", "url": "https://ultiworld.com/video/?event=203122#filtered", "image": null,
                 "badge": null, "live": false, "event": null, "division": null, "collection": true}
              ]},
              {"title": "Empty", "items": []}
            ]}
            """
        )
        val lib = Parsing.library(v)
        assertEquals(listOf("Live Now", "Recent Events"), lib.shelves.map { it.title })
        val live = lib.shelves[0].items.single()
        assertTrue(live.isLive)
        assertFalse(live.isCollection)
        assertEquals("https://cdn.ultiworld.com/u/D1R1F1-768x432.jpg", live.image)
        assertEquals("https://cdn.ultiworld.com/u/D1R1F1.jpg", live.imageFallback)
        val event = lib.shelves[1].items.single()
        assertTrue(event.isCollection)
        assertNull(event.badge)
        assertNull(event.image)
    }

    @Test
    fun `score feed games`() {
        val v = JSONObject(
            """
            {"poll": 20, "events": [{"id": 199279, "title": "Club National Championships 2026", "url": "https://ultiworld.com/event/199279/x/",
              "day": "2026-10-23", "games": [
                {"id": "usau-418386", "status": "in_progress", "division": "Women's", "name": "Pool A", "field": "2",
                 "start": "2026-10-23T11:15:00-07:00",
                 "teams": [
                   {"name": "Flipside", "full_name": "San Diego Flipside", "seed": 8, "score": 7, "logo": "https://x/f.png", "winner": false},
                   {"name": "Pop", "full_name": "Raleigh Pop", "seed": "5", "score": null, "logo": null, "winner": false}
                 ],
                 "videos": [
                   {"id": 203416, "label": "Field Pass", "state": "live", "url": "https://ultiworld.com/video/203416/field-pass"},
                   {"id": 1, "label": null, "production_level": "Game Feed", "url": "https://ultiworld.com/video/1/x"},
                   {"id": 2, "label": "No link"}
                 ]}
              ]}]}
            """
        )
        val scores = Parsing.scores(v)
        assertEquals(20, scores.pollSeconds)
        val game = scores.games.single()
        assertEquals(GameStatus.LIVE, game.status)
        assertEquals("Pool A", game.name)
        assertEquals(2026, game.start?.year)
        assertEquals(listOf(8, 5), game.teams.map { it.seed })
        assertEquals(listOf(7, null), game.teams.map { it.score })
        assertEquals(listOf("Field Pass", "Game Feed"), game.videos.map { it.label })
    }

    @Test
    fun `poll interval has a floor`() {
        assertEquals(20, Parsing.scores(JSONObject("""{"poll": 1, "events": []}""")).pollSeconds)
    }

    @Test
    fun `playback results`() {
        val ready = Parsing.playback(
            JSONObject("""{"status": "ok", "playbackId": "abc", "token": "t.k.n", "streamType": "live:dvr", "title": "Final"}""")
        )
        assertTrue(ready is PlaybackResult.Ready)
        val p = (ready as PlaybackResult.Ready).playback
        assertTrue(p.isLive)
        assertEquals("https://stream.mux.com/abc.m3u8?token=t.k.n", p.streamUrl)

        val vod = Parsing.playback(JSONObject("""{"status": "ok", "playbackId": "abc", "token": null, "streamType": "on-demand"}"""))
        assertEquals("https://stream.mux.com/abc.m3u8", (vod as PlaybackResult.Ready).playback.streamUrl)

        assertTrue(Parsing.playback(JSONObject("""{"status": "locked", "message": "Subscribers only"}""")) is PlaybackResult.Locked)
        assertTrue(Parsing.playback(JSONObject("""{"status": "stopped", "message": "Too many"}""")) is PlaybackResult.Stopped)
        assertEquals(PlaybackResult.Missing, Parsing.playback(JSONObject("""{"status": "ok"}""")))
        assertEquals(PlaybackResult.Missing, Parsing.playback(null))
    }

    @Test
    fun `heartbeat defaults to a valid session`() {
        assertEquals(HeartbeatResult(sessionValid = true, stopStreaming = false, checkedIn = false), Parsing.heartbeat(null))
        assertEquals(
            HeartbeatResult(sessionValid = false, stopStreaming = true, checkedIn = true),
            Parsing.heartbeat(JSONObject("""{"status": 200, "sessionValid": false, "stopStreaming": true}"""))
        )
    }

    @Test
    fun `a heartbeat the server turned away is not a check-in`() {
        val r = Parsing.heartbeat(JSONObject("""{"status": 503, "sessionValid": true, "stopStreaming": false}"""))
        assertFalse(r.checkedIn)
        assertTrue(r.sessionValid)
    }
}
