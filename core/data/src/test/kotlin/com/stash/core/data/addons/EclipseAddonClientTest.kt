package com.stash.core.data.addons

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.store.EncryptedTokenStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Eclipse protocol is https-only, but MockWebServer serves plain http. The
 * interceptor records each request's real (https) URL and rewrites only the
 * transport to the mock server, so URL derivation is tested as production does it.
 */
class EclipseAddonClientTest {
    private lateinit var server: MockWebServer
    private lateinit var serverUrl: HttpUrl
    private val requested = mutableListOf<HttpUrl>()
    private lateinit var client: EclipseAddonClient
    private val tokenStore: EncryptedTokenStore = mockk(relaxed = true)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        serverUrl = server.url("/")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            requested += original.url
            // Build the target from the captured base URL: server.url() refuses after shutdown.
            val target = serverUrl.newBuilder()
                .encodedPath(original.url.encodedPath)
                .encodedQuery(original.url.encodedQuery)
                .build()
            chain.proceed(original.newBuilder().url(target).build())
        }.build()
        client = EclipseAddonClient(http, tokenStore)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `fetchManifest parses the manifest and sends Stash's User-Agent`() = kotlinx.coroutines.test.runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"nebula","name":"Nebula","version":"1.2.3","description":"TorBox",
                   "resources":["search","stream"],"types":["track"],"contentType":"music","extra":1}""",
            ),
        )

        val manifest = client.fetchManifest("https://addons.test/tok123/manifest.json?token=abc")

        assertThat(manifest.id).isEqualTo("nebula")
        assertThat(manifest.name).isEqualTo("Nebula")
        assertThat(manifest.version).isEqualTo("1.2.3")
        assertThat(manifest.resources).containsExactly("search", "stream")
        val request = server.takeRequest()
        assertThat(request.getHeader("User-Agent")).isEqualTo(EclipseAddonClient.USER_AGENT)
        assertThat(request.path).isEqualTo("/tok123/manifest.json?token=abc")
    }

    @Test
    fun `a base URL gets manifest json appended, keeping the token path and query`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setBody("""{"id":"a","name":"A","version":"1","resources":["search","stream"]}"""))

        client.fetchManifest("https://addons.test/tok123?token=abc")

        val path = server.takeRequest().path
        assertThat(path).isEqualTo("/tok123/manifest.json?token=abc")
    }

    @Test
    fun `search keeps the token query, adds q, and accepts tracks plus a bare array`() = kotlinx.coroutines.test.runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"tracks":[{"id":"t1","title":"Kesariya","artist":"Arijit Singh","album":"Brahmastra",
                   "duration":268,"artworkURL":"https://img.test/a.jpg","isrc":"INZ031412345",
                   "format":"flac","streamURL":"https://addon.test/t/t1.flac"}]}""",
            ),
        )

        val tracks = client.search("https://addons.test/tok123/manifest.json?token=abc", "Arijit Singh Kesariya")

        assertThat(tracks).hasSize(1)
        assertThat(tracks[0].id).isEqualTo("t1")
        assertThat(tracks[0].title).isEqualTo("Kesariya")
        assertThat(tracks[0].artist).isEqualTo("Arijit Singh")
        assertThat(tracks[0].durationMs).isEqualTo(268_000)
        assertThat(tracks[0].artworkUrl).isEqualTo("https://img.test/a.jpg")
        assertThat(tracks[0].isrc).isEqualTo("INZ031412345")
        assertThat(tracks[0].format).isEqualTo("flac")

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/tok123/search?token=abc&q=Arijit%20Singh%20Kesariya")
        assertThat(requested.last().host).isEqualTo("addons.test")

        server.enqueue(MockResponse().setBody("""[{"id":"t2","title":"Other","artist":"X"}]"""))
        assertThat(client.search("https://addons.test/manifest.json", "Other").map { it.id }).containsExactly("t2")
    }

    @Test
    fun `search honours the limit`() = kotlinx.coroutines.test.runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a","title":"A","artist":"X"},{"id":"b","title":"B","artist":"X"},{"id":"c","title":"C","artist":"X"}]""",
            ),
        )

        assertThat(client.search("https://addons.test/manifest.json?token=t", "X", limit = 2).map { it.id })
            .containsExactly("a", "b")
            .inOrder()
    }

    @Test
    fun `stream parses url, codec, expiry, and manifest kind`() = kotlinx.coroutines.test.runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"url":"https://cdn.test/t1.flac","format":"flac","quality":"lossless",
                   "expiresAt":1762000000,"codec":"flac","container":"flac","manifest":"none",
                   "sampleRate":96000,"bitDepth":24}""",
            ),
        )

        val stream = client.stream("https://addons.test/manifest.json", "t1")

        assertThat(stream.url).isEqualTo("https://cdn.test/t1.flac")
        assertThat(stream.format).isEqualTo("flac")
        assertThat(stream.quality).isEqualTo("lossless")
        assertThat(stream.expiresAt).isEqualTo(1762000000L)
        assertThat(stream.sampleRate).isEqualTo(96000)
        assertThat(stream.bitDepth).isEqualTo(24)
        assertThat(server.takeRequest().path).isEqualTo("/stream/t1")
    }

    @Test
    fun `503 means the addon is preparing, not an HTTP failure`() = kotlinx.coroutines.test.runTest {
        // A cache-backed addon may ask for minutes; Stash caps the wait at 30 s.
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "300"))

        val failure = runCatching { client.stream("https://addons.test/manifest.json", "t1") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(EclipseAddonException.Preparing::class.java)
        assertThat((failure as EclipseAddonException.Preparing).retryAfterSeconds).isEqualTo(30)
    }

    @Test
    fun `stream calls get a longer timeout than manifest and search`() = kotlinx.coroutines.test.runTest {
        // 9 s is past the 8 s metadata budget but inside the stream one.
        server.enqueue(
            MockResponse().setBody("""{"id":"a","name":"A","resources":["search","stream"]}""")
                .setBodyDelay(9, java.util.concurrent.TimeUnit.SECONDS),
        )
        val metadataFailure = runCatching { client.fetchManifest("https://addons.test/manifest.json") }
            .exceptionOrNull()
        assertThat(metadataFailure).isInstanceOf(EclipseAddonException.Unreachable::class.java)

        server.enqueue(
            MockResponse().setBody("""{"url":"https://cdn.test/slow.flac"}""")
                .setBodyDelay(9, java.util.concurrent.TimeUnit.SECONDS),
        )
        val stream = client.stream("https://addons.test/manifest.json", "t1")
        assertThat(stream.url).isEqualTo("https://cdn.test/slow.flac")
    }

    @Test
    fun `other non-2xx is a typed HTTP failure`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val failure = runCatching { client.fetchManifest("https://addons.test/manifest.json") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(EclipseAddonException.HttpFailure::class.java)
        assertThat((failure as EclipseAddonException.HttpFailure).code).isEqualTo(404)
    }

    @Test
    fun `an unparseable body is a typed invalid response`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setBody("not json"))

        val failure = runCatching { client.fetchManifest("https://addons.test/manifest.json") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(EclipseAddonException.InvalidResponse::class.java)
    }

    @Test
    fun `plain http and unparseable addon URLs are rejected before any request`() = kotlinx.coroutines.test.runTest {
        assertThat(EclipseAddonClient.manifestUrlOrNull("http://addons.test/manifest.json")).isNull()
        assertThat(EclipseAddonClient.manifestUrlOrNull("not a url")).isNull()
        assertThat(EclipseAddonClient.manifestUrlOrNull("  ")).isNull()

        val failure = runCatching { client.fetchManifest("http://addons.test/manifest.json") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(EclipseAddonException.InvalidUrl::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a dropped connection is a typed unreachable`() = kotlinx.coroutines.test.runTest {
        server.shutdown()

        val failure = runCatching { client.fetchManifest("https://addons.test/manifest.json") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(EclipseAddonException.Unreachable::class.java)
    }

    // ── settings, resolve, resolve-isrc ─────────────────────────────────────

    private val resolveOnlyManifest = """
        {"id":"addon","name":"Addon","version":"2.0","contentType":"music",
         "resources":["stream","settings","resolve"],
         "capabilities":{"isrc":true,"unknown":{"deep":[1,2]}},
         "settings":[
           {"key":"quality","type":"select","default":"LOSSLESS","perNetwork":true},
           {"key":"appleQuality","type":"select","default":"LOSSLESS","perNetwork":true}
         ]}
    """.trimIndent()

    @Test
    fun `a resolve-only manifest is parsed, with its settings defaults`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setBody(resolveOnlyManifest))

        val manifest = client.fetchManifest("https://addons.test/tok/manifest.json?token=abc")

        assertThat(manifest.resources).containsExactly("stream", "settings", "resolve")
        assertThat(manifest.settingDefaults).containsExactly("quality", "LOSSLESS", "appleQuality", "LOSSLESS")
    }

    @Test
    fun `manifest settings keep their full schema, labels and option labels`() = kotlinx.coroutines.test.runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"a","name":"A","resources":["stream","resolve"],
                   "settings":[
                     {"key":"quality","type":"select","label":"Quality","help":"Pick one",
                      "default":"LOSSLESS","perNetwork":true,
                      "options":[{"value":"HI_RES_LOSSLESS","label":"Hi-Res"},
                                 {"value":"LOSSLESS","label":"CD"}]},
                     {"key":"tagline","type":"text","label":"Tagline","maxLength":40}
                   ]}""",
            ),
        )

        val manifest = client.fetchManifest("https://addons.test/manifest.json")
        val quality = manifest.settingDefinitions.first { it.key == "quality" }

        assertThat(quality.type).isEqualTo("select")
        assertThat(quality.label).isEqualTo("Quality")
        assertThat(quality.help).isEqualTo("Pick one")
        assertThat(quality.default).isEqualTo("LOSSLESS")
        assertThat(quality.perNetwork).isTrue()
        assertThat(quality.options.map { it.value }).containsExactly("HI_RES_LOSSLESS", "LOSSLESS").inOrder()
        assertThat(quality.options.map { it.label }).containsExactly("Hi-Res", "CD").inOrder()
        assertThat(manifest.settingDefinitions.first { it.key == "tagline" }.maxLength).isEqualTo(40)
        assertThat(manifest.settingOptions["quality"]).containsExactly("HI_RES_LOSSLESS", "LOSSLESS")
        assertThat(manifest.settingDefaults).containsExactly("quality", "LOSSLESS")
    }

    @Test
    fun `an object-shaped settings map keeps defaults and treats primitives as defaults`() = kotlinx.coroutines.test.runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"a","name":"A","resources":["stream","resolve"],
                   "settings":{"quality":{"type":"select","default":"320kbps",
                                          "options":["320kbps","128kbps"]},"enabled":true}}""",
            ),
        )

        val manifest = client.fetchManifest("https://addons.test/manifest.json")

        assertThat(manifest.settingDefaults).containsExactly("quality", "320kbps", "enabled", "true")
        assertThat(manifest.settingOptions["quality"]).containsExactly("320kbps", "128kbps")
        assertThat(manifest.settingDefinitions.first { it.key == "enabled" }.default).isEqualTo("true")
    }

    @Test
    fun `settings go on search, stream and resolve requests without dropping the token`() = kotlinx.coroutines.test.runTest {
        val settings = mapOf("quality" to "LOSSLESS", "appleQuality" to "LOSSLESS")
        val url = "https://addons.test/tok/manifest.json?token=abc"

        server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
        client.search(url, "x", settings = settings)
        val searchUrl = server.takeRequest().requestUrl!!
        assertThat(searchUrl.queryParameter("token")).isEqualTo("abc")
        assertThat(searchUrl.queryParameter("quality")).isEqualTo("LOSSLESS")
        assertThat(searchUrl.queryParameter("appleQuality")).isEqualTo("LOSSLESS")
        assertThat(searchUrl.queryParameter("q")).isEqualTo("x")

        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.test/t.flac"}"""))
        client.stream(url, "t1", settings = settings)
        val streamUrl = server.takeRequest().requestUrl!!
        assertThat(streamUrl.encodedPath).isEqualTo("/tok/stream/t1")
        assertThat(streamUrl.queryParameter("token")).isEqualTo("abc")
        assertThat(streamUrl.queryParameter("quality")).isEqualTo("LOSSLESS")

        server.enqueue(MockResponse().setBody("""{"item":null}"""))
        client.resolve(url, "Title", "Artist", 1_000, "ISRC1", settings = settings)
        val resolveUrl = server.takeRequest().requestUrl!!
        assertThat(resolveUrl.encodedPath).isEqualTo("/tok/resolve")
        assertThat(resolveUrl.queryParameter("quality")).isEqualTo("LOSSLESS")
    }

    @Test
    fun `resolve sends isrc, title, artist and duration, and reads item or a bare object`() = kotlinx.coroutines.test.runTest {
        val url = "https://addons.test/manifest.json?token=abc"
        server.enqueue(
            MockResponse().setBody(
                """{"item":{"id":"r1","type":"track","title":"Blinding Lights",
                   "artist":"The Weeknd","isrc":"USUM71923546"}}""",
            ),
        )

        val item = client.resolve(url, "Blinding Lights", "The Weeknd", 200_000, "USUM71923546")

        assertThat(item?.id).isEqualTo("r1")
        val query = server.takeRequest().requestUrl!!
        assertThat(query.encodedPath).isEqualTo("/resolve")
        assertThat(query.queryParameter("title")).isEqualTo("Blinding Lights")
        assertThat(query.queryParameter("artist")).isEqualTo("The Weeknd")
        assertThat(query.queryParameter("durationMs")).isEqualTo("200000")
        assertThat(query.queryParameter("isrc")).isEqualTo("USUM71923546")
        assertThat(query.queryParameter("token")).isEqualTo("abc")

        server.enqueue(MockResponse().setBody("""{"item":null}"""))
        assertThat(client.resolve(url, "Missing", null, null, null)).isNull()

        server.enqueue(MockResponse().setBody("""{"id":"direct","title":"Direct"}"""))
        assertThat(client.resolve(url, "Direct", null, null, null)?.id).isEqualTo("direct")
    }

    @Test
    fun `resolve 403 Unavailable is a normal miss, not a failure`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Unavailable"}"""))

        assertThat(client.resolve("https://addons.test/manifest.json", "X", "Y", null, null)).isNull()
    }

    @Test
    fun `resolve-isrc returns the id the addon names, and 404 is a miss`() = kotlinx.coroutines.test.runTest {
        val url = "https://addons.test/manifest.json?token=abc"

        server.enqueue(MockResponse().setBody("""{"trackId":"t9"}"""))
        val byTrackId = client.resolveIsrc(url, "USUM71923546")
        assertThat(byTrackId?.id).isEqualTo("t9")
        assertThat(byTrackId?.isrc).isEqualTo("USUM71923546")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("isrc")).isEqualTo("USUM71923546")

        server.enqueue(MockResponse().setBody("""{"id":"t10"}"""))
        assertThat(client.resolveIsrc(url, "ISRC2")?.id).isEqualTo("t10")

        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.resolveIsrc(url, "ISRC3")).isNull()
    }

    // ── Eclipse account addons (Cloud) ─────────────────────────────────────

    private val cloudManifestUrl = "https://api.eclipsemusic.app/addon/abc/manifest.json"
    private val cloudManifest = """
        {"id":"eclipse-music-cloud","name":"Cloud","version":"1.0","contentType":"music",
         "resources":["stream","settings","resolve"],
         "settings":[{"key":"quality","type":"select","default":"LOSSLESS",
                      "options":["HI_RES_LOSSLESS","LOSSLESS","HIGH"]}]}
    """.trimIndent()

    @Test
    fun `cloud gets Authorization and the web platform, never X-Eclipse-Access or a mint`() =
        kotlinx.coroutines.test.runTest {
            coEvery { tokenStore.getEclipseToken() } returns "acct-fake"
            server.enqueue(MockResponse().setBody(cloudManifest))

            client.fetchManifest(cloudManifestUrl)

            val request = server.takeRequest()
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer acct-fake")
            assertThat(request.getHeader("X-Client-Platform")).isEqualTo("web")
            assertThat(request.getHeader("X-Eclipse-Access")).isNull()
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(request.path).isEqualTo("/addon/abc/manifest.json")
        }

    @Test
    fun `a third-party addon never sees the Eclipse account token`() = kotlinx.coroutines.test.runTest {
        coEvery { tokenStore.getEclipseToken() } returns "acct-fake"
        server.enqueue(MockResponse().setBody("""{"id":"a","name":"A","resources":["search","stream"]}"""))

        client.fetchManifest("https://addons.test/manifest.json")

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isNull()
        assertThat(request.getHeader("X-Eclipse-Access")).isNull()
        assertThat(request.getHeader("X-Client-Platform")).isEqualTo("web")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `another account addon mints X-Eclipse-Access, cached until it expires`() =
        kotlinx.coroutines.test.runTest {
            coEvery { tokenStore.getEclipseToken() } returns "acct-fake"
            val otherUrl = "https://api.eclipsemusic.app/addon/xyz/manifest.json"

            server.enqueue(MockResponse().setBody("""{"token":"access-fake","expiresIn":300}"""))
            server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
            client.search(otherUrl, "x", addonId = "other-addon")

            val mint = server.takeRequest()
            assertThat(mint.path).isEqualTo("/api/addon-access/token?addon=other-addon")
            assertThat(mint.getHeader("Authorization")).isEqualTo("Bearer acct-fake")
            val search = server.takeRequest()
            assertThat(search.getHeader("X-Eclipse-Access")).isEqualTo("access-fake")
            assertThat(search.getHeader("Authorization")).isEqualTo("Bearer acct-fake")

            // Cached: a second search mints nothing.
            server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
            client.search(otherUrl, "y", addonId = "other-addon")
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(server.takeRequest().path).startsWith("/addon/xyz/search")
        }

    @Test
    fun `an expired access token is minted again`() = kotlinx.coroutines.test.runTest {
        coEvery { tokenStore.getEclipseToken() } returns "acct-fake"
        val otherUrl = "https://api.eclipsemusic.app/addon/xyz/manifest.json"
        val alreadyExpired = System.currentTimeMillis() / 1_000 - 10

        server.enqueue(MockResponse().setBody("""{"token":"old","expiresAt":$alreadyExpired}"""))
        server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
        client.search(otherUrl, "x", addonId = "other-addon")

        server.enqueue(MockResponse().setBody("""{"token":"fresh","expiresIn":300}"""))
        server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
        client.search(otherUrl, "y", addonId = "other-addon")

        val paths = List(4) { server.takeRequest().path }
        assertThat(paths.count { it == "/api/addon-access/token?addon=other-addon" }).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `a failed mint cools down and the call still goes out without the header`() =
        kotlinx.coroutines.test.runTest {
            coEvery { tokenStore.getEclipseToken() } returns "acct-fake"
            val otherUrl = "https://api.eclipsemusic.app/addon/xyz/manifest.json"

            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
            client.search(otherUrl, "x", addonId = "other-addon")
            assertThat(server.takeRequest().path).isEqualTo("/api/addon-access/token?addon=other-addon")
            assertThat(server.takeRequest().getHeader("X-Eclipse-Access")).isNull()

            // Cooling down: no second mint attempt.
            server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
            client.search(otherUrl, "y", addonId = "other-addon")
            assertThat(server.requestCount).isEqualTo(3)
        }

    @Test
    fun `no account token means no Authorization, no X-Eclipse-Access and no mint`() =
        kotlinx.coroutines.test.runTest {
            coEvery { tokenStore.getEclipseToken() } returns null
            server.enqueue(MockResponse().setBody(cloudManifest))

            client.fetchManifest(cloudManifestUrl)

            val request = server.takeRequest()
            assertThat(request.getHeader("Authorization")).isNull()
            assertThat(request.getHeader("X-Eclipse-Access")).isNull()
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `cloud stream uses stream with trackId and quality, not stream by path`() =
        kotlinx.coroutines.test.runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"url":"https://api.eclipsemusic.app/api/tidal/hls/1.m3u8?quality=HI_RES_LOSSLESS&exp=1762000000&sig=x",
                       "quality":"HI_RES_LOSSLESS","codec":"flac","bitDepth":24,"sampleRate":44100,
                       "format":"hls","container":"fmp4","manifest":"hls","encrypted":false}""",
                ),
            )

            val stream = client.streamCloud(cloudManifestUrl, "track-1", "HI_RES_LOSSLESS", addonId = "eclipse-music-cloud")

            assertThat(stream.url).contains(".m3u8")
            assertThat(stream.codec).isEqualTo("flac")
            assertThat(stream.bitDepth).isEqualTo(24)
            val request = server.takeRequest()
            assertThat(request.requestUrl!!.encodedPath).isEqualTo("/addon/abc/stream")
            assertThat(request.requestUrl!!.queryParameter("trackId")).isEqualTo("track-1")
            assertThat(request.requestUrl!!.queryParameter("quality")).isEqualTo("HI_RES_LOSSLESS")
        }

    @Test
    fun `a refused hi-res stream is retried once at CD quality`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.test/2.m3u8","format":"hls","codec":"flac"}"""))

        val stream = client.streamCloud(cloudManifestUrl, "track-1", "HI_RES_LOSSLESS", addonId = "eclipse-music-cloud")

        assertThat(stream.url).isEqualTo("https://cdn.test/2.m3u8")
        val first = server.takeRequest()
        assertThat(first.requestUrl!!.queryParameter("quality")).isEqualTo("HI_RES_LOSSLESS")
        val second = server.takeRequest()
        assertThat(second.requestUrl!!.queryParameter("quality")).isEqualTo("LOSSLESS")
        assertThat(second.requestUrl!!.queryParameter("trackId")).isEqualTo("track-1")
    }

    @Test
    fun `cloud resolve-isrc uses the tidal route`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setBody("""{"trackId":"t5"}"""))

        val resolved = client.resolveIsrc(
            cloudManifestUrl,
            "USUM71923546",
            addonId = "eclipse-music-cloud",
            path = listOf("tidal", "resolve-isrc"),
        )

        assertThat(resolved?.id).isEqualTo("t5")
        assertThat(server.takeRequest().path).isEqualTo("/addon/abc/tidal/resolve-isrc?isrc=USUM71923546")
    }

    @Test
    fun `a cancelled call cancels its HTTP request instead of running to completion`() =
        kotlinx.coroutines.test.runTest {
            server.enqueue(
                MockResponse().setBody("""{"tracks":[]}""")
                    .setHeadersDelay(10, java.util.concurrent.TimeUnit.SECONDS),
            )
            val job = async {
                runCatching { client.search("https://addons.test/manifest.json", "x") }
            }
            // The request is on the wire; cancelling the coroutine must cancel the call.
            server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)

            val startedNs = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000

            assertThat(job.isCancelled).isTrue()
            assertThat(elapsedMs).isLessThan(3_000)
        }

    // ── Cloud's Apple Music fallback ───────────────────────────────────────

    @Test
    fun `apple search reads iTunes hits`() = kotlinx.coroutines.test.runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"resultCount":1,"results":[{"trackId":6812560833,"trackName":"Mario",
                   "artistName":"David Guetta & Jast","collectionName":"Album",
                   "trackTimeMillis":120238,"isrc":"FAKEISRC"}]}""",
            ),
        )

        val hits = client.searchAppleTracks("Mario", "David Guetta & Jast")

        assertThat(hits).hasSize(1)
        assertThat(hits[0].trackId).isEqualTo(6812560833L)
        assertThat(hits[0].trackName).isEqualTo("Mario")
        assertThat(hits[0].artistName).isEqualTo("David Guetta & Jast")
        assertThat(hits[0].collectionName).isEqualTo("Album")
        assertThat(hits[0].trackTimeMillis).isEqualTo(120238L)
        assertThat(hits[0].isrc).isEqualTo("FAKEISRC")
        val url = server.takeRequest().requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/search")
        assertThat(url.queryParameter("term")).isEqualTo("Mario David Guetta & Jast")
        assertThat(url.queryParameter("entity")).isEqualTo("song")
        assertThat(url.queryParameter("limit")).isEqualTo("10")
        assertThat(url.queryParameter("country")).isNotNull()
    }

    @Test
    fun `apple search runs the device country and US together and takes the first non-empty`() =
        kotlinx.coroutines.test.runTest {
            val previous = java.util.Locale.getDefault()
            try {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("nb-NO"))
                // The two searches race; answer by country so the order can't matter.
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                        val country = request.requestUrl!!.queryParameter("country")
                        return if (country == "US") {
                            MockResponse().setBody(
                                """{"resultCount":1,"results":[{"trackId":7,"trackName":"Mario","artistName":"Artist"}]}""",
                            )
                        } else {
                            MockResponse().setBody("""{"resultCount":0,"results":[]}""")
                        }
                    }
                }

                val hits = client.searchAppleTracks("Mario", "Artist")

                assertThat(hits.map { it.trackId }).containsExactly(7L)
                // Exactly the locale search plus one fallback, no sequential retries.
                assertThat(server.requestCount).isEqualTo(2)
                val countries = List(2) { server.takeRequest().requestUrl!!.queryParameter("country") }
                assertThat(countries).containsExactly("NO", "US")
            } finally {
                java.util.Locale.setDefault(previous)
            }
        }

    @Test
    fun `cloud apple stream uses the Apple route with the Cloud headers`() = kotlinx.coroutines.test.runTest {
        coEvery { tokenStore.getEclipseToken() } returns "acct-fake"
        server.enqueue(
            MockResponse().setBody(
                """{"url":"https://cdn.test/1.m3u8?exp=1762000000","quality":"HI_RES_LOSSLESS",
                   "codec":"flac","bitDepth":24,"sampleRate":44100,"format":"hls","manifest":"hls"}""",
            ),
        )

        val stream = client.streamApple(
            // The real Cloud manifest is under `/music`; the Apple route must
            // not repeat that segment (`/music/music/applemusic/stream`).
            manifestUrl = "https://api.eclipsemusic.app/addon/abc/music/manifest.json",
            appleTrackId = "6812560833",
            appleQuality = "HI_RES_MAX",
            isrc = "FAKEISRC",
            addonId = "eclipse-music-cloud",
        )

        assertThat(stream.codec).isEqualTo("flac")
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/addon/abc/music/applemusic/stream")
        assertThat(url.queryParameter("trackId")).isEqualTo("6812560833")
        assertThat(url.queryParameter("appleQuality")).isEqualTo("HI_RES_MAX")
        assertThat(url.queryParameter("isrc")).isEqualTo("FAKEISRC")
        // Cloud headers: browser UA + Bearer + platform, never an access-token mint.
        assertThat(request.getHeader("User-Agent")).isEqualTo(EclipseAddonClient.BROWSER_USER_AGENT)
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer acct-fake")
        assertThat(request.getHeader("X-Client-Platform")).isEqualTo("web")
        assertThat(request.getHeader("X-Eclipse-Access")).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `Eclipse-hosted requests use a browser UA, third-party addons keep Stash's`() = kotlinx.coroutines.test.runTest {        server.enqueue(MockResponse().setBody(cloudManifest))
        client.fetchManifest(cloudManifestUrl)
        val manifestUa = server.takeRequest().getHeader("User-Agent")!!
        assertThat(manifestUa).isEqualTo(EclipseAddonClient.BROWSER_USER_AGENT)
        assertThat(manifestUa).contains("Chrome/")
        assertThat(manifestUa).doesNotContain("; wv")

        server.enqueue(MockResponse().setBody("""{"tracks":[]}"""))
        client.search(cloudManifestUrl, "x")
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo(EclipseAddonClient.BROWSER_USER_AGENT)

        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.test/1.m3u8"}"""))
        client.streamCloud(cloudManifestUrl, "t1", "LOSSLESS", addonId = "eclipse-music-cloud")
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo(EclipseAddonClient.BROWSER_USER_AGENT)

        server.enqueue(MockResponse().setBody("""{"id":"a","name":"A","resources":["search","stream"]}"""))
        client.fetchManifest("https://addons.test/manifest.json")
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo(EclipseAddonClient.USER_AGENT)
    }

    @Test
    fun `cloud resolve carries the preferred quality`() = kotlinx.coroutines.test.runTest {        server.enqueue(MockResponse().setBody("""{"item":null}"""))

        client.resolve(
            manifestUrl = cloudManifestUrl,
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationMs = 200_000,
            isrc = "USUM71923546",
            addonId = "eclipse-music-cloud",
            quality = "HI_RES_LOSSLESS",
        )

        val url = server.takeRequest().requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/addon/abc/resolve")
        assertThat(url.queryParameter("quality")).isEqualTo("HI_RES_LOSSLESS")
        assertThat(url.queryParameter("isrc")).isEqualTo("USUM71923546")
    }
}
