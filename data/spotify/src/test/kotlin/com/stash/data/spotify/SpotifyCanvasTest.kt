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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        """{"data":{"search":{"tracks":{"items":[{"track":{
            "uri":"spotify:track:good","name":"$name",
            "artists":{"items":[{"profile":{"name":"$artist"}}]},
            "duration":{"totalMilliseconds":$durationMs}
        }}]}}}}"""

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

    // ── Exact-match rejection ──────────────────────────────────────────

    @Test
    fun `an exact title artist and length match is accepted`() {
        val accepted = SpotifyCanvas.exactCandidate(
            track("Song", "Artist", 180_000),
            listOf(candidate("good", "Song", listOf("Artist"), 181_500)),
        )
        assertEquals("good", accepted?.id)
    }

    @Test
    fun `normalized punctuation and split artist credits still match exactly`() {
        val accepted = SpotifyCanvas.exactCandidate(
            track("Don't Stop!", "A, B", 180_000),
            listOf(candidate("good", "Dont Stop", listOf("A", "B"), 180_000)),
        )
        assertNull(accepted)
        val samePunctuation = SpotifyCanvas.exactCandidate(
            track("Don't Stop!", "A, B", 180_000),
            listOf(candidate("good", "DON'T STOP", listOf("A", "B"), 180_000)),
        )
        assertEquals("good", samePunctuation?.id)
    }

    @Test
    fun `a near-miss title is rejected`() {
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist"),
                listOf(candidate("live", "Song (Live)", listOf("Artist"))),
            ),
        )
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist"),
                listOf(candidate("remaster", "Song - Remastered", listOf("Artist"))),
            ),
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
    fun `a length more than two seconds away is rejected, unknown lengths pass`() {
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist", 180_000),
                listOf(candidate("long", "Song", listOf("Artist"), 182_001)),
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
    fun `two different exact ids are ambiguous and yield no canvas`() {
        assertNull(
            SpotifyCanvas.exactCandidate(
                track("Song", "Artist"),
                listOf(
                    candidate("one", "Song", listOf("Artist")),
                    candidate("two", "Song", listOf("Artist")),
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
    fun `a near-miss search result never asks for a canvas`() = runBlocking {
        val client = client { operation, _ ->
            when (operation) {
                "searchDesktop" -> searchAnswer("Song (Live)", "Artist", 181_000)
                "canvas" -> canvasAnswer("https://canvas.scdn.co/canvas/abc.mp4")
                else -> "{}"
            }
        }
        val track = track("Song", "Artist", 180_000)

        assertNull(client.canvasUrl(track))
        assertNull(client.canvasUrl(track))
        assertEquals(listOf("searchDesktop"), requests.map { it.first })
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
