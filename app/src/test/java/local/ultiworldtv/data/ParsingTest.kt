package local.ultiworldtv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    @Test
    fun `full size image drops the WordPress size suffix`() {
        assertEquals(
            "https://cdn.ultiworld.com/wordpress/wp-content/uploads/2026/10/D1-Field-Pass.jpg",
            Parsing.fullSizeImage("https://cdn.ultiworld.com/wordpress/wp-content/uploads/2026/10/D1-Field-Pass-300x169.jpg")
        )
        assertEquals("https://x/a-1-001.png", Parsing.fullSizeImage("https://x/a-1-001.png"))
        assertNull(Parsing.fullSizeImage("data:image/gif;base64,R0lGOD"))
    }

    @Test
    fun `library shelves and cards`() {
        val v = JSONObject(
            """
            {"loggedIn": true, "shelves": [
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
        assertTrue(lib.loggedIn)
        assertEquals(listOf("Live Now", "Recent Events"), lib.shelves.map { it.title })
        val live = lib.shelves[0].items.single()
        assertTrue(live.isLive)
        assertFalse(live.isCollection)
        assertEquals("https://cdn.ultiworld.com/u/D1R1F1.jpg", live.image)
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
        assertEquals(HeartbeatResult(sessionValid = true, stopStreaming = false), Parsing.heartbeat(null))
        assertEquals(
            HeartbeatResult(sessionValid = false, stopStreaming = true),
            Parsing.heartbeat(JSONObject("""{"status": 200, "sessionValid": false, "stopStreaming": true}"""))
        )
    }
}
