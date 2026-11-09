package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canvas v1 data-layer contract: only an exact recording gets a Canvas, only a
 * playable MP4 URL is returned, and both positive and negative answers are
 * cached so a library walk doesn't re-ask for the same track.
 */
class SpotifyCanvasTest {

    private val requests = mutableListOf<Pair<String, String>>()

    private val tokenManager = mockk<TokenManager> {
        coEvery { getSpotifyAccessToken() } returns "access-token"
        coEvery { getSpotifyClientId() } returns "client-id"
    }

    private val authManager = mockk<SpotifyAuthManager> {
        every { getClientVersion() } returns "1.2.3.4.g0"
        coEvery { getClientToken("client-id") } returns "client-token"
    }

    private fun client(responder: (operation: String, variables: String) -> String): SpotifyApiClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val operation = url.queryParameter("operationName").orEmpty()
            val variables = url.queryParameter("variables").orEmpty()
            requests += operation to variables
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(responder(operation, variables).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return SpotifyApiClient(http, tokenManager, authManager)
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun canvasAnswer(url: String?, type: String = "video/mp4", fileId: String? = null): String {
        val fields = mutableListOf("\"type\":\"$type\"")
        if (url != null) fields += "\"url\":\"$url\""
        if (fileId != null) fields += "\"fileId\":\"$fileId\""
        return """{"data":{"trackUnion":{"canvas":{${fields.joinToString(",")}}}}}"""
    }

    private fun searchAnswer(name: String, artist: String, durationMs: Long): String =
        searchAnswer(name, listOf(artist), durationMs)

    private fun searchAnswer(name: String, artists: List<String>, durationMs: Long): String =
        """{"data":{"search":{"tracks":{"items":[{"track":{
            "uri":"spotify:track:good","name":"$name",
            "artists":{"items":[${artists.joinToString(",") { "{\"profile\":{\"name\":\"$it\"}}" }}]},
            "duration":{"totalMilliseconds":$durationMs}
        }}]}}}}"""

    private fun emptySearchAnswer(): String =
        """{"data":{"search":{"tracks":{"items":[]}}}}"""

    private fun searchTerm(variables: String): String =
        Json.parseToJsonElement(variables).jsonObject["searchTerm"]!!.jsonPrimitive.content

    private fun track(
        title: String,
        artist: String,
        durationMs: Long = 180_000,
        spotifyUri: String? = null,
    ) = Track(title = title, artist = artist, durationMs = durationMs, spotifyUri = spotifyUri)

    private fun candidate(
        id: String,
        name: String,
        artists: List<String>,
        durationMs: Long = 180_000,
    ) = SpotifyTrackCandidate(
        id = id,
        name = name,
        artists = artists,
        albumName = "",
        durationMs = durationMs,
        isrc = null,
        explicit = false,
    )

    // ── URL acceptance ─────────────────────────────────────────────────

    @Test
    fun `trackUnion canvas with an mp4 url is accepted`() {
        val url = "https://canvas.scdn.co/canvas/abc.mp4"
        assertEquals(url, SpotifyCanvas.parseUrl(json(canvasAnswer(url))))
    }

    @Test
    fun `the shallower canvas shapes are tolerated`() {
        val url = "https://canvas.scdn.co/canvas/abc.mp4"
        assertEquals(url, SpotifyCanvas.parseUrl(json("""{"data":{"track":{"canvas":{"url":"$url","type":"video/mp4"}}}}""")))
        assertEquals(url, SpotifyCanvas.parseUrl(json("""{"data":{"canvas":{"url":"$url","type":"video/mp4"}}}""")))
    }

    @Test
    fun `a fileId without a url is no canvas`() {
        assertNull(SpotifyCanvas.parseUrl(json(canvasAnswer(url = null, fileId = "abc"))))
    }

    @Test
    fun `an image or extensionless non-declared url is rejected`() {
        assertNull(SpotifyCanvas.parseUrl(json(canvasAnswer("https://cdn/x.jpg", type = "IMAGE"))))
        assertNull(SpotifyCanvas.parseUrl(json(canvasAnswer("https://cdn/x", type = "IMAGE"))))
    }

    @Test
    fun `an explicit video mp4 type accepts an extensionless url`() {
        val url = "https://cdn/x"
        assertEquals(url, SpotifyCanvas.parseUrl(json(canvasAnswer(url, type = "video/mp4"))))
    }

    @Test
    fun `only http(s) urls are accepted`() {
        assertNull(SpotifyCanvas.parseUrl(json(canvasAnswer("ftp://cdn/x.mp4"))))
        assertNull(SpotifyCanvas.parseUrl(json(canvasAnswer("file:///x.mp4"))))
    }

    @Test
    fun `an mp4 path with query and uppercase extension is accepted`() {
        val a = "https://cdn/x.mp4?token=1"
        val b = "https://cdn/x.MP4"
        assertEquals(a, SpotifyCanvas.parseUrl(json(canvasAnswer(a))))
        assertEquals(b, SpotifyCanvas.parseUrl(json(canvasAnswer(b))))
    }

    // ── Recording match ────────────────────────────────────────────────

    @Test
    fun `an exact title artist and length match is accepted`() {
        val accepted = SpotifyCanvas.exactCandidate(
            track("Song", "Artist", 180_000),
            listOf(candidate("good", "Song", listOf("Artist"), 181_500)),
        )
        assertEquals("good", accepted?.id)
    }

    @Test
    fun `official video audio and remaster tags are presentation only`() {
        assertEquals(
            "video",
            SpotifyCanvas.exactCandidate(
                track("Song (Official Video)", "Artist"),
                listOf(candidate("video", "Song", listOf("Artist"))),
            )?.id,
        )
        assertEquals(
            "audio",
            SpotifyCanvas.exactCandidate(
                track("Song [Audio]", "Artist"),
                listOf(candidate("audio", "Song", listOf("Artist"))),
            )?.id,
        )
        assertEquals(
            "remaster",
            SpotifyCanvas.exactCandidate(
                track("Song - Remastered 2011", "Artist"),
                listOf(candidate("remaster", "Song", listOf("Artist"))),
            )?.id,
        )
        assertEquals(
            "candidateRemaster",
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist"),
                listOf(candidate("candidateRemaster", "Song (Remastered 2011)", listOf("Artist"))),
            )?.id,
        )
    }

    @Test
    fun `a performance version the track does not name is rejected`() {
        val plain = track("Song", "Artist")
        listOf("Live", "Remix", "Acoustic", "Instrumental", "Sped Up", "Karaoke", "Cover", "Demo")
            .forEach { version ->
                assertNull(
                    version,
                    SpotifyCanvas.exactCandidate(
                        plain,
                        listOf(candidate(version, "Song ($version)", listOf("Artist"))),
                    ),
                )
            }
    }

    @Test
    fun `a performance version both sides name is accepted`() {
        assertEquals(
            "live",
            SpotifyCanvas.exactCandidate(
                track("Song - Live", "Artist"),
                listOf(candidate("live", "Song (Live)", listOf("Artist"))),
            )?.id,
        )
        assertEquals(
            "acoustic",
            SpotifyCanvas.exactCandidate(
                track("Song (Acoustic)", "Artist"),
                listOf(candidate("acoustic", "Song - Acoustic", listOf("Artist"))),
            )?.id,
        )
    }

    @Test
    fun `apostrophes and accents are folded`() {
        assertEquals(
            "ascii",
            SpotifyCanvas.exactCandidate(
                track("Don't Stop", "Beyoncé"),
                listOf(candidate("ascii", "Dont Stop", listOf("Beyonce"))),
            )?.id,
        )
        assertEquals(
            "typographic",
            SpotifyCanvas.exactCandidate(
                track("Don’t Stop", "Café"),
                listOf(candidate("typographic", "DON'T STOP", listOf("Cafe"))),
            )?.id,
        )
    }

    @Test
    fun `ampersand and and slash artist credits compare as the same acts`() {
        assertEquals(
            "amp",
            SpotifyCanvas.exactCandidate(
                track("Song", "A & B"),
                listOf(candidate("amp", "Song", listOf("A", "B"))),
            )?.id,
        )
        assertEquals(
            "joined",
            SpotifyCanvas.exactCandidate(
                track("Song", "A, B"),
                listOf(candidate("joined", "Song", listOf("A & B"))),
            )?.id,
        )
        assertEquals(
            "slash",
            SpotifyCanvas.exactCandidate(
                track("Song", "A/B"),
                listOf(candidate("slash", "Song", listOf("A", "B"))),
            )?.id,
        )
        assertEquals(
            "and",
            SpotifyCanvas.exactCandidate(
                track("Song", "A and B"),
                listOf(candidate("and", "Song", listOf("A", "B"))),
            )?.id,
        )
        assertEquals(
            "allowlisted",
            SpotifyCanvas.exactCandidate(
                track("Song", "Earth, Wind & Fire"),
                listOf(candidate("allowlisted", "Song", listOf("Earth, Wind & Fire"))),
            )?.id,
        )
    }

    @Test
    fun `VEVO topic and official channel names are cleaned`() {
        assertEquals(
            "vevo",
            SpotifyCanvas.exactCandidate(
                track("Song", "ArtistVEVO"),
                listOf(candidate("vevo", "Song", listOf("Artist"))),
            )?.id,
        )
        assertEquals(
            "topic",
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist - Topic"),
                listOf(candidate("topic", "Song", listOf("Artist"))),
            )?.id,
        )
        assertEquals(
            "official",
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist Official"),
                listOf(candidate("official", "Song", listOf("Artist"))),
            )?.id,
        )
    }

    @Test
    fun `a wrong artist is rejected`() {
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist"),
                listOf(candidate("cover", "Song", listOf("Somebody Else"))),
            ),
        )
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist, Other"),
                listOf(candidate("partial", "Song", listOf("Artist"))),
            ),
        )
    }

    @Test
    fun `a different title is rejected`() {
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist"),
                listOf(candidate("other", "Another Song", listOf("Artist"))),
            ),
        )
    }

    @Test
    fun `lengths within ten seconds pass and unknown lengths pass`() {
        assertEquals(
            "padded",
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist", 180_000),
                listOf(candidate("padded", "Song", listOf("Artist"), 189_000)),
            )?.id,
        )
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist", 180_000),
                listOf(candidate("long", "Song", listOf("Artist"), 191_000)),
            ),
        )
        assertEquals(
            "unknown",
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist", 0),
                listOf(candidate("unknown", "Song", listOf("Artist"), 182_001)),
            )?.id,
        )
    }

    @Test
    fun `duplicate masters are one recording and the closest length wins`() {
        assertEquals(
            "near",
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist", 180_000),
                listOf(
                    candidate("far", "Song", listOf("Artist"), 181_500),
                    candidate("near", "Song - Remastered", listOf("Artist"), 180_500),
                ),
            )?.id,
        )
    }

    @Test
    fun `distinct recordings are ambiguous and yield no canvas`() {
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist", 180_000),
                listOf(
                    candidate("one", "Song", listOf("Artist"), 175_000),
                    candidate("two", "Song", listOf("Artist"), 185_000),
                ),
            ),
        )
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist"),
                listOf(
                    candidate("plain", "Song", listOf("Artist")),
                    candidate("featured", "Song", listOf("Artist", "Guest")),
                ),
            ),
        )
    }

    // ── Cache behaviour (no network) ───────────────────────────────────

    @Test
    fun `a positive canvas is cached for the track uri`() = runBlocking {
        val url = "https://canvas.scdn.co/canvas/abc.mp4"
        val client = client { _, _ -> canvasAnswer(url) }
        val track = track("Song", "Artist", spotifyUri = "spotify:track:abc")

        assertEquals(url, client.canvasUrl(track))
        assertEquals(url, client.canvasUrl(track))
        assertEquals(listOf("canvas"), requests.map { it.first })
    }

    @Test
    fun `a fileId-only answer is negatively cached`() = runBlocking {
        val client = client { _, _ -> canvasAnswer(url = null, fileId = "abc") }
        val track = track("Song", "Artist", spotifyUri = "spotify:track:abc")

        assertNull(client.canvasUrl(track))
        assertNull(client.canvasUrl(track))
        assertEquals(listOf("canvas"), requests.map { it.first })
    }

    @Test
    fun `an exact search result is resolved through search then canvas`() = runBlocking {
        val url = "https://canvas.scdn.co/canvas/abc.mp4"
        val client = client { operation, _ ->
            when (operation) {
                "searchDesktop" -> searchAnswer("Song", "Artist", 181_000)
                "canvas" -> canvasAnswer(url)
                else -> "{}"
            }
        }

        assertEquals(url, client.canvasUrl(track("Song", "Artist", 180_000)))
        assertEquals(listOf("searchDesktop", "canvas"), requests.map { it.first })
        assertEquals(
            """{"trackUri":"spotify:track:good"}""",
            requests.last().second,
        )
    }

    @Test
    fun `a near-miss search result never asks for a canvas and is cached`() = runBlocking {
        val client = client { operation, _ ->
            when (operation) {
                "searchDesktop" -> searchAnswer("Song (Live)", "Artist", 181_000)
                "canvas" -> canvasAnswer("https://canvas.scdn.co/canvas/abc.mp4")
                else -> "{}"
            }
        }
        val track = track("Song", "Artist", 180_000)

        assertNull(client.canvasUrl(track))
        val afterFirst = requests.size
        assertTrue(requests.all { it.first == "searchDesktop" })
        assertNull(client.canvasUrl(track))
        assertEquals(afterFirst, requests.size)
    }

    @Test
    fun `a cleaned query variant finds the track when the raw query does not`() = runBlocking {
        val url = "https://canvas.scdn.co/canvas/abc.mp4"
        val client = client { operation, variables ->
            when (operation) {
                "searchDesktop" ->
                    if (searchTerm(variables) == "Song Artist") searchAnswer("Song", "Artist", 180_000)
                    else emptySearchAnswer()
                "canvas" -> canvasAnswer(url)
                else -> "{}"
            }
        }
        val track = track("Artist - Song (Official Video)", "ArtistVEVO", 180_000)

        assertEquals(url, client.canvasUrl(track))
        assertEquals(listOf("searchDesktop", "searchDesktop", "canvas"), requests.map { it.first })
        assertEquals(
            listOf("Artist - Song (Official Video) ArtistVEVO", "Song Artist"),
            requests.filter { it.first == "searchDesktop" }.map { searchTerm(it.second) },
        )
    }

    @Test
    fun `a title and primary artist variant finds a collaboration`() = runBlocking {
        val url = "https://canvas.scdn.co/canvas/abc.mp4"
        val client = client { operation, variables ->
            when (operation) {
                "searchDesktop" ->
                    if (searchTerm(variables) == "Song Artist")
                        searchAnswer("Song", listOf("Artist", "Guest"), 180_000)
                    else emptySearchAnswer()
                "canvas" -> canvasAnswer(url)
                else -> "{}"
            }
        }
        val track = track("Song", "Artist, Guest", 180_000)

        assertEquals(url, client.canvasUrl(track))
        assertEquals(
            listOf("Song Artist, Guest", "Song Artist"),
            requests.filter { it.first == "searchDesktop" }.map { searchTerm(it.second) },
        )
    }

    @Test
    fun `title only is the last query variant`() = runBlocking {
        val url = "https://canvas.scdn.co/canvas/abc.mp4"
        val client = client { operation, variables ->
            when (operation) {
                "searchDesktop" ->
                    if (searchTerm(variables) == "Song") searchAnswer("Song", "Artist", 180_000)
                    else emptySearchAnswer()
                "canvas" -> canvasAnswer(url)
                else -> "{}"
            }
        }

        assertEquals(url, client.canvasUrl(track("Song", "Artist", 180_000)))
        assertEquals(
            listOf("Song Artist", "Song"),
            requests.filter { it.first == "searchDesktop" }.map { searchTerm(it.second) },
        )
    }

    @Test
    fun `a transient search failure is not cached as a miss`() = runBlocking {
        val client = client { operation, _ ->
            when (operation) {
                "searchDesktop" -> "{}"
                "canvas" -> canvasAnswer("https://canvas.scdn.co/canvas/abc.mp4")
                else -> "{}"
            }
        }
        val track = track("Song", "Artist", 180_000)

        assertEquals(CanvasLookup.TransientFailure, client.canvasLookup(track))
        val afterFirst = requests.size
        assertEquals(2, afterFirst)
        assertTrue(requests.none { it.first == "canvas" })

        assertEquals(CanvasLookup.TransientFailure, client.canvasLookup(track))
        assertEquals(afterFirst * 2, requests.size)
        assertTrue(requests.none { it.first == "canvas" })
    }

    @Test
    fun `a genuine empty search is cached as a miss`() = runBlocking {
        val client = client { operation, _ ->
            when (operation) {
                "searchDesktop" -> emptySearchAnswer()
                else -> "{}"
            }
        }
        val track = track("Song", "Artist", 180_000)

        assertEquals(CanvasLookup.NoCanvas, client.canvasLookup(track))
        val afterFirst = requests.size
        assertTrue(afterFirst > 0)
        assertEquals(CanvasLookup.NoCanvas, client.canvasLookup(track))
        assertEquals(afterFirst, requests.size)
    }

    @Test
    fun `an open spotify track link is accepted as the uri`() {
        assertEquals(
            "spotify:track:abc",
            SpotifyCanvas.trackUri("https://open.spotify.com/track/abc?si=x"),
        )
        assertNull(SpotifyCanvas.trackUri("https://example.com/track/abc"))
    }
}
