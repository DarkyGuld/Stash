package com.stash.core.data.addons

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.spotify.SpotifyApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class EclipseIsrcEnricherTest {
    private lateinit var server: MockWebServer
    private lateinit var serverUrl: HttpUrl
    private lateinit var http: OkHttpClient
    private val spotify: SpotifyApiClient = mockk()
    private val dao: TrackDao = mockk(relaxed = true)
    private val gate: SpotifyIsrcGate = mockk(relaxed = true) {
        every { backoffUntilMs() } returns 0L
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        serverUrl = server.url("/")
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val target = serverUrl.newBuilder()
                .encodedPath(original.url.encodedPath)
                .encodedQuery(original.url.encodedQuery)
                .build()
            chain.proceed(original.newBuilder().url(target).build())
        }.build()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun enricher() = EclipseIsrcEnricher(spotify, dao, gate, http)

    private fun track(isrc: String? = null) = TrackEntity(
        id = 1615,
        title = "Feel Love",
        artist = "Dimitri Vegas & Like Mike",
        durationMs = 170_800,
        isrc = isrc,
        spotifyUri = "spotify:track:5ZuIWk2CWo9NTh3Ijzbn3r",
    )

    @Test
    fun `Spotify's isrc is cached in memory and backfilled once`() = runTest {
        coEvery { spotify.getTracksIsrc(listOf("5ZuIWk2CWo9NTh3Ijzbn3r")) } returns
            SpotifyApiClient.IsrcBatch.Success(mapOf("5ZuIWk2CWo9NTh3Ijzbn3r" to "BEIW12600564"))
        val enricher = enricher()

        assertThat(enricher.isrcFor(track())).isEqualTo("BEIW12600564")
        assertThat(enricher.isrcFor(track())).isEqualTo("BEIW12600564")

        coVerify(exactly = 1) { spotify.getTracksIsrc(any()) }
        coVerify(exactly = 1) { dao.backfillIsrcIfMissing(1615, "BEIW12600564") }
    }

    @Test
    fun `a track that already has an ISRC never calls Spotify or Deezer`() = runTest {
        val enricher = enricher()

        assertThat(enricher.isrcFor(track(isrc = "EXISTING123"))).isEqualTo("EXISTING123")

        coVerify(exactly = 0) { spotify.getTracksIsrc(any()) }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `without a Spotify URI, Deezer search supplies the ISRC`() = runTest {
        coEvery { spotify.getTracksIsrc(any()) } returns SpotifyApiClient.IsrcBatch.Failed
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"title":"Feel Love","artist":{"name":"Dimitri Vegas & Like Mike"},
                   "duration":171,"isrc":"BEIW12600564"}]}""",
            ),
        )
        val enricher = enricher()
        val noUri = track().copy(spotifyUri = null)

        assertThat(enricher.isrcFor(noUri)).isEqualTo("BEIW12600564")
        coVerify(exactly = 0) { spotify.getTracksIsrc(any()) }
    }

    @Test
    fun `an active Spotify backoff skips Spotify and uses the cleaned Deezer query`() = runTest {
        every { gate.backoffUntilMs() } returns System.currentTimeMillis() + 3_600_000L
        val queries = mutableListOf<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val q = request.requestUrl!!.queryParameter("q").orEmpty()
                queries += q
                return if (q.contains("Dimitri Vegas") && !q.contains("Maddix")) {
                    MockResponse().setBody(
                        """{"data":[{"title":"Feel Love","artist":{"name":"Dimitri Vegas & Like Mike"},
                           "duration":171,"isrc":"BEIW12600564"}]}""",
                    )
                } else {
                    MockResponse().setBody("""{"data":[]}""")
                }
            }
        }

        assertThat(enricher().isrcFor(track().copy(spotifyUri = null))).isEqualTo("BEIW12600564")
        coVerify(exactly = 0) { spotify.getTracksIsrc(any()) }
        assertThat(queries.first()).contains("Dimitri Vegas & Like Mike")
        assertThat(queries.first()).doesNotContain("Maddix")
    }

    @Test
    fun `a lookup failure is just no ISRC`() = runTest {
        coEvery { spotify.getTracksIsrc(any()) } returns SpotifyApiClient.IsrcBatch.Failed
        server.enqueue(MockResponse().setResponseCode(500))
        val enricher = enricher()

        assertThat(enricher.isrcFor(track().copy(spotifyUri = null))).isNull()
    }
}
