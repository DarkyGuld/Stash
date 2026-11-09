package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The "Inspired by" source MySpoti uses for song radio. Everything is answered
 * by an interceptor: no network, and the requests themselves are asserted for
 * the exact endpoint and web-player headers.
 */
class SpotifyInspiredByTest {

    private val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** Answer for the seed endpoint; tests replace it per case. */
    private var seedCode = 200
    private var seedBody: String? = """{"mediaItems":[{"uri":"spotify:playlist:mix1"}]}"""

    /** Answer per `fetchPlaylist` offset; a missing offset is a server error. */
    private val playlistPages = mutableMapOf<Int, String>()

    private val tokenManager = mockk<TokenManager> {
        coEvery { getSpotifyAccessToken() } returns "access-token"
        coEvery { getSpotifyClientId() } returns "client-id"
        coEvery { forceRefreshSpotifyAccessToken() } returns null
    }

    private val authManager = mockk<SpotifyAuthManager> {
        every { getClientVersion() } returns "1.2.3.4.g0"
        coEvery { getClientToken("client-id") } returns "client-token"
        // No client_credentials token: playlist reads take the GraphQL walk.
        coEvery { getClientCredentialsToken() } returns null
        every { scrapePersistedQueryHash(any(), any()) } returns null
    }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val url = request.url
        val (code, body) = if (url.host == "spclient.wg.spotify.com") {
            seedCode to seedBody
        } else {
            val offset = Regex(""""offset":(\d+)""")
                .find(url.queryParameter("variables").orEmpty())
                ?.groupValues?.get(1)?.toInt()
            val page = offset?.let { playlistPages[it] }
            if (page != null) 200 to page else 500 to """{"error":"boom"}"""
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body?.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val client = SpotifyApiClient(http, tokenManager, authManager)

    private fun seedRequests(): List<Request> =
        synchronized(requests) {
            requests.filter { it.url.host == "spclient.wg.spotify.com" }
        }

    private fun playlistRequests(): List<Request> =
        synchronized(requests) {
            requests.filter { it.url.host != "spclient.wg.spotify.com" }
        }

    // ── Seed lookup ─────────────────────────────────────────────────────

    @Test
    fun `seed lookup uses the inspiredby endpoint and web-player headers`() = runBlocking {
        val uri = client.inspiredByPlaylistUri("spotify:track:seed1")

        assertEquals("spotify:playlist:mix1", uri)
        val request = seedRequests().single()
        assertEquals(
            "https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/spotify:track:seed1",
            request.url.toString().substringBefore("?"),
        )
        assertEquals("json", request.url.queryParameter("response-format"))
        assertEquals("Bearer access-token", request.header("Authorization"))
        assertEquals("client-token", request.header("Client-Token"))
        assertEquals("WebPlayer", request.header("App-Platform"))
        assertEquals("1.2.3.4.g0", request.header("Spotify-App-Version"))
        assertEquals("https://open.spotify.com/", request.header("Origin"))
        assertEquals("https://open.spotify.com/", request.header("Referer"))
        assertTrue(request.header("User-Agent").orEmpty().contains("Mozilla/5.0"))
    }

    @Test
    fun `the first media item is read and only a playlist URI is accepted`() = runBlocking {
        seedBody = """{"mediaItems":[{"uri":"spotify:playlist:first"},{"uri":"spotify:playlist:second"}]}"""

        assertEquals("spotify:playlist:first", client.inspiredByPlaylistUri("spotify:track:s"))

        seedCode = 200
        seedBody = """{"mediaItems":[{"uri":"spotify:artist:not-a-playlist"}]}"""
        assertNull(client.inspiredByPlaylistUri("spotify:track:other"))
    }

    @Test
    fun `empty body and non-playlist answers are null`() {
        assertNull(parseInspiredByPlaylistUri(null))
        assertNull(parseInspiredByPlaylistUri(""))
        assertNull(parseInspiredByPlaylistUri("""{"mediaItems":[]}"""))
        assertNull(parseInspiredByPlaylistUri("""{"mediaItems":[{"uri":"spotify:album:x"}]}"""))
        assertNull(parseInspiredByPlaylistUri("""{"mediaItems":[{"uri":"spotify:playlist:"}]}"""))
        assertEquals(
            "spotify:playlist:ok",
            parseInspiredByPlaylistUri("""{"mediaItems":[{"uri":"spotify:playlist:ok"}]}"""),
        )
    }

    @Test
    fun `a positive seed answer is cached for the next call`() = runBlocking {
        assertEquals("spotify:playlist:mix1", client.inspiredByPlaylistUri("spotify:track:seed1"))
        assertEquals("spotify:playlist:mix1", client.inspiredByPlaylistUri("spotify:track:seed1"))

        assertEquals(1, seedRequests().size)
    }

    @Test
    fun `a negative seed answer is cached for the next call`() = runBlocking {
        seedCode = 404
        seedBody = null

        assertNull(client.inspiredByPlaylistUri("spotify:track:seed1"))
        assertNull(client.inspiredByPlaylistUri("spotify:track:seed1"))

        assertEquals(1, seedRequests().size)
    }

    @Test
    fun `a 204 is a miss and never reaches the playlist walk`() = runBlocking {
        seedCode = 204
        seedBody = null

        assertNull(client.inspiredByPlaylistUri("spotify:track:seed1"))
        assertEquals(0, playlistRequests().size)
    }

    @Test
    fun `a failed seed request is a miss`() = runBlocking {
        seedCode = 500
        seedBody = """{"error":"boom"}"""

        assertNull(client.inspiredByPlaylistUri("spotify:track:seed1"))
    }

    @Test
    fun `non-Spotify seeds make no request`() = runBlocking {
        assertNull(client.inspiredByPlaylistUri(""))
        assertNull(client.inspiredByPlaylistUri("https://example.com/not-a-uri"))

        assertEquals(0, seedRequests().size)
    }

    @Test
    fun `cache policy is 24 hours for a hit, 10 minutes for a miss, 64 entries`() {
        assertEquals(24L * 60 * 60 * 1000, SpotifyApiClient.INSPIRED_BY_POSITIVE_TTL_MS)
        assertEquals(10L * 60 * 1000, SpotifyApiClient.INSPIRED_BY_NEGATIVE_TTL_MS)
        assertEquals(64, SpotifyApiClient.INSPIRED_BY_CACHE_MAX_ENTRIES)
    }

    // ── Playlist tracks ─────────────────────────────────────────────────

    @Test
    fun `playlist tracks map fields and leave the seed track out`() = runBlocking {
        playlistPages[0] = playlistPage(
            listOf(
                trackData("seed1", "Seed Song", "Seed Artist", "Seed Album", 100_000, ART_300),
                trackData("t2", "Second Song", "Second Artist", "Second Album", 200_000, ART_300),
                trackData("t3", "Third Song", "Third Artist", "Third Album", 300_000, ART_300),
            ),
            totalCount = 3,
        )

        val tracks = client.inspiredByTracks("spotify:track:seed1")

        assertEquals(listOf("Second Song", "Third Song"), tracks.map { it.title })
        assertEquals(listOf("Second Artist", "Third Artist"), tracks.map { it.artist })
        assertEquals(listOf("Second Album", "Third Album"), tracks.map { it.album })
        assertEquals(listOf(200_000L, 300_000L), tracks.map { it.durationMs })
        assertEquals(listOf("spotify:track:t2", "spotify:track:t3"), tracks.map { it.spotifyUri })
        assertTrue(tracks.all { it.albumArtUrl?.contains(ART_640) == true })
        assertEquals(1, seedRequests().size)
        assertEquals(1, playlistRequests().size)
    }

    @Test
    fun `limit caps the mapped playlist`() = runBlocking {
        playlistPages[0] = playlistPage(
            listOf(
                trackData("seed1", "Seed Song", "Artist", "Album", 1_000, null),
                trackData("t2", "Second Song", "Artist", "Album", 2_000, null),
                trackData("t3", "Third Song", "Artist", "Album", 3_000, null),
            ),
            totalCount = 3,
        )

        val tracks = client.inspiredByTracks("spotify:track:seed1", limit = 1)

        assertEquals(listOf("Second Song"), tracks.map { it.title })
    }

    @Test
    fun `an unresolved seed yields no playlist read and no tracks`() = runBlocking {
        seedCode = 404
        seedBody = null

        assertTrue(client.inspiredByTracks("spotify:track:seed1").isEmpty())
        assertEquals(0, playlistRequests().size)
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun trackData(
        id: String,
        name: String,
        artist: String,
        album: String,
        durationMs: Long,
        art: String?,
    ): String {
        val cover = art?.let { ""","coverArt":{"sources":[{"url":"$it","width":300}]}""" }.orEmpty()
        return """{"__typename":"Track","uri":"spotify:track:$id","name":"$name",""" +
            """"trackDuration":{"totalMilliseconds":$durationMs},""" +
            """"artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"$artist"}}]},""" +
            """"albumOfTrack":{"uri":"spotify:album:al","name":"$album"$cover}}"""
    }

    private fun playlistPage(items: List<String>, totalCount: Int): String {
        val list = items.joinToString(",") { """{"itemV2":{"data":$it}}""" }
        return """{"data":{"playlistV2":{"content":{"totalCount":$totalCount,"items":[$list]}}}}"""
    }

    private companion object {
        const val ART_300 = "https://i.scdn.co/image/ab67616d00001e02deadbeef"
        const val ART_640 = "ab67616d0000b273deadbeef"
    }
}
