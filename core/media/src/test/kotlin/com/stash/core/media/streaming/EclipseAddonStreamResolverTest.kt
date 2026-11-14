package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonException
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.addons.EclipseAddonStream
import com.stash.core.data.addons.EclipseAddonTrack
import com.stash.core.data.addons.EclipseAppleLookupStore
import com.stash.core.data.addons.EclipseAppleTrack
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EclipseAddonStreamResolverTest {
    private val now = 1_762_000_000_000L

    private fun addon(
        id: String = "nebula",
        resources: List<String> = listOf("search", "stream"),
        settings: Map<String, String> = emptyMap(),
    ) = EclipseAddon(
        id = id,
        name = id,
        version = "1",
        manifestUrl = "https://addons.test/$id/manifest.json",
        resources = resources,
        settings = settings,
    )

    private fun stream(
        url: String = "https://cdn.test/123.flac",
        format: String? = null,
        quality: String? = null,
        expiresAt: Long? = null,
        codec: String? = null,
        manifest: String? = null,
        sampleRate: Int? = null,
        bitDepth: Int? = null,
    ) = EclipseAddonStream(
        url = url,
        format = format,
        quality = quality,
        expiresAt = expiresAt,
        codec = codec,
        manifest = manifest,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
    )

    // ── mapping ─────────────────────────────────────────────────────────────

    @Test
    fun `expiresAt in seconds is converted to millis`() {
        val mapped = mapStream(stream(expiresAt = 1_762_000_300L), nowMs = now)!!

        assertThat(mapped.expiresAtMs).isEqualTo(1_762_000_300_000L)
    }

    @Test
    fun `expiresAt already in millis is kept`() {
        val mapped = mapStream(stream(expiresAt = 1_762_000_300_000L), nowMs = now)!!

        assertThat(mapped.expiresAtMs).isEqualTo(1_762_000_300_000L)
    }

    @Test
    fun `a missing expiry becomes now plus thirty minutes`() {
        val mapped = mapStream(stream(), nowMs = now)!!

        assertThat(mapped.expiresAtMs).isEqualTo(now + 30 * 60_000L)
    }

    @Test
    fun `bit depth and sample rate are carried only when positive`() {
        val hiRes = mapStream(stream(sampleRate = 96_000, bitDepth = 24), nowMs = now)!!
        assertThat(hiRes.sampleRateHz).isEqualTo(96_000)
        assertThat(hiRes.bitsPerSample).isEqualTo(24)

        val unknown = mapStream(stream(sampleRate = 0, bitDepth = 0), nowMs = now)!!
        assertThat(unknown.sampleRateHz).isNull()
        assertThat(unknown.bitsPerSample).isNull()
    }

    @Test
    fun `codec prefers the stream's own field, then format, then the URL extension`() {
        assertThat(mapStream(stream(codec = "FLAC", format = "mp3"), nowMs = now)!!.codec).isEqualTo("flac")
        assertThat(mapStream(stream(format = "M4A"), nowMs = now)!!.codec).isEqualTo("m4a")
        assertThat(mapStream(stream(url = "https://cdn.test/x/abc.MP3?token=1"), nowMs = now)!!.codec).isEqualTo("mp3")
    }

    @Test
    fun `quality's kbps number is the bitrate, and lossless has none`() {
        assertThat(mapStream(stream(quality = "320kbps"), nowMs = now)!!.bitrateKbps).isEqualTo(320)
        assertThat(mapStream(stream(quality = "256 kbps"), nowMs = now)!!.bitrateKbps).isEqualTo(256)
        assertThat(mapStream(stream(quality = "lossless"), nowMs = now)!!.bitrateKbps).isNull()
    }

    @Test
    fun `origin is addon, and the manifest kind selects DASH or HLS`() {
        val dash = mapStream(stream(manifest = "dash"), nowMs = now)!!
        assertThat(dash.origin).isEqualTo("addon")
        assertThat(dash.isDash).isTrue()
        assertThat(dash.isHls).isFalse()

        val hls = mapStream(stream(manifest = "hls"), nowMs = now)!!
        assertThat(hls.isHls).isTrue()
        assertThat(mapStream(stream(manifest = "none"), nowMs = now)!!.isDash).isFalse()
    }

    @Test
    fun `a stream with no URL is not playable`() {
        assertThat(mapStream(stream(url = "  "), nowMs = now)).isNull()
    }

    @Test
    fun `Cloud's direct Qobuz FLAC answer maps progressive, never HLS`() {
        val mapped = mapStream(
            EclipseAddonStream(
                url = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1762000000",
                format = null,
                codec = "flac",
                container = "flac",
                manifest = "none",
                sampleRate = 44_100,
                bitDepth = 24,
            ),
            nowMs = now,
        )!!

        assertThat(mapped.isHls).isFalse()
        assertThat(mapped.isDash).isFalse()
        assertThat(mapped.codec).isEqualTo("flac")
        assertThat(mapped.bitsPerSample).isEqualTo(24)
        assertThat(mapped.sampleRateHz).isEqualTo(44_100)
        assertThat(mapped.origin).isEqualTo("addon")
        // The quality stamp reads FLAC 24-bit / 44.1 kHz; StreamService labels origin addon:* as "via <name>".
    }

    @Test
    fun `a cloud HLS answer maps isHls, codec and the exp from its signed URL`() {
        val mapped = mapStream(
            stream(
                url = "https://api.eclipsemusic.app/api/tidal/hls/1.m3u8?quality=HI_RES_LOSSLESS&exp=1762000000&sig=x",
                format = "hls",
                quality = "HI_RES_LOSSLESS",
                codec = "flac",
                bitDepth = 24,
                sampleRate = 44_100,
            ),
            nowMs = now,
        )!!

        assertThat(mapped.isHls).isTrue()
        assertThat(mapped.isDash).isFalse()
        assertThat(mapped.codec).isEqualTo("flac")
        assertThat(mapped.bitsPerSample).isEqualTo(24)
        assertThat(mapped.sampleRateHz).isEqualTo(44_100)
        assertThat(mapped.expiresAtMs).isEqualTo(1_762_000_000_000L)
    }

    @Test
    fun `a cloud HLS answer without exp falls back to six hours`() {
        val mapped = mapStream(
            stream(url = "https://cdn.test/2.m3u8", format = "hls"),
            nowMs = now,
        )!!

        assertThat(mapped.expiresAtMs).isEqualTo(now + 6 * 60 * 60_000L)
    }

    // ── resolve ─────────────────────────────────────────────────────────────

    @Test
    fun `resolve searches enabled addons in order and maps the first confident match`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val first = addon("first")
        val second = addon("second")
        coEvery { repository.enabledNow() } returns listOf(first, second)
        coEvery { repository.search(first, any(), any()) } returns emptyList()
        coEvery { repository.search(second, any(), any()) } returns listOf(
            EclipseAddonTrack(id = "t1", title = "Blinding Lights", artist = "The Weeknd", duration = 200),
        )
        coEvery { repository.stream(second, "t1") } returns stream(quality = "lossless", format = "flac")

        val result = EclipseAddonStreamResolver(repository)
            .resolve(TrackEntity(title = "Blinding Lights", artist = "The Weeknd", durationMs = 200_000))

        assertThat(result).isNotNull()
        assertThat(result!!.url).isEqualTo("https://cdn.test/123.flac")
        // The service label names the addon that served.
        assertThat(result.origin).isEqualTo("addon:second")
        coVerify(exactly = 1) { repository.search(first, "The Weeknd Blinding Lights", 25) }
    }

    @Test
    fun `a failing addon is skipped, and no match at all is null`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val broken = addon("broken")
        coEvery { repository.enabledNow() } returns listOf(broken)
        coEvery { repository.search(broken, any(), any()) } throws IllegalStateException("503")

        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNull()

        coEvery { repository.enabledNow() } returns emptyList()
        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNull()
    }

    @Test
    fun `a candidate that is not really the track is never streamed`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon()
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.search(addon, any(), any()) } returns listOf(
            EclipseAddonTrack(id = "other", title = "Blinding Lights (Live)", artist = "The Weeknd", duration = 200),
        )

        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNull()
        coVerify(exactly = 0) { repository.stream(any(), any()) }
    }

    @Test
    fun `resolve-only addons resolve first and never search`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val resolveOnly = addon("addon", resources = listOf("stream", "settings", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(resolveOnly)
        coEvery { repository.resolve(resolveOnly, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "r1", title = "Blinding Lights", isrc = "USUM71923546")
        coEvery { repository.stream(resolveOnly, "r1") } returns stream(quality = "lossless", format = "flac")

        val result = EclipseAddonStreamResolver(repository).resolve(track())

        assertThat(result?.origin).isEqualTo("addon:addon")
        coVerify(exactly = 0) { repository.search(any(), any(), any()) }
        coVerify(exactly = 0) { repository.resolveIsrc(any(), any()) }
    }

    @Test
    fun `resolve sends the YouTube id for a row without an ISRC or Spotify URI`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val resolveOnly = addon("addon", resources = listOf("stream", "settings", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(resolveOnly)
        coEvery {
            repository.resolve(resolveOnly, any(), any(), any(), any(), any(), "Y1HUL-TALoE")
        } returns EclipseAddonTrack(id = "r1", title = "Edge of Desire", isrc = "GBCPZ2524512")
        coEvery { repository.stream(resolveOnly, "r1") } returns stream(quality = "lossless", format = "flac")

        val edge = TrackEntity(
            title = "Edge Of Desire (From Defected In The House Livestream)",
            artist = "Jonas Blue, Malive",
            durationMs = 204_000,
            youtubeId = "Y1HUL-TALoE",
            source = com.stash.core.model.MusicSource.YOUTUBE,
        )
        val result = EclipseAddonStreamResolver(repository).resolve(edge)

        assertThat(result?.origin).isEqualTo("addon:addon")
        coVerify(exactly = 1) {
            repository.resolve(resolveOnly, any(), any(), any(), any(), any(), "Y1HUL-TALoE")
        }
    }

    @Test
    fun `a strict identity addon never falls back to search after a resolve miss`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val strict = addon("strict", resources = listOf("stream", "resolve", "search"))
            .copy(settings = mapOf("strictIdentity" to "true"))
        coEvery { repository.enabledNow() } returns listOf(strict)
        coEvery { repository.resolve(strict, any(), any(), any(), any()) } returns null
        coEvery { repository.search(strict, any(), any()) } returns listOf(
            EclipseAddonTrack(id = "s1", title = "Blinding Lights", artist = "The Weeknd", duration = 200),
        )

        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNull()
        coVerify(exactly = 0) { repository.search(strict, any(), any()) }
    }

    @Test
    fun `a resolve miss falls back to search only when the addon offers it`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val both = addon("both", resources = listOf("stream", "resolve", "search"))
        coEvery { repository.enabledNow() } returns listOf(both)
        coEvery { repository.resolve(both, any(), any(), any(), any()) } returns null
        coEvery { repository.search(both, any(), any()) } returns listOf(
            EclipseAddonTrack(id = "s1", title = "Blinding Lights", artist = "The Weeknd", duration = 200),
        )
        coEvery { repository.stream(both, "s1") } returns stream()

        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNotNull()
        coVerify(exactly = 1) { repository.search(both, any(), any()) }

        // Resolve-only: a null resolve ends this addon, no search exists to try.
        val resolveOnly = addon("ro", resources = listOf("stream", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(resolveOnly)
        coEvery { repository.resolve(resolveOnly, any(), any(), any(), any()) } returns null

        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNull()
        coVerify(exactly = 0) { repository.search(resolveOnly, any(), any()) }
    }

    @Test
    fun `the ISRC route is used when the manifest lists isrc and resolve has no match`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon("isrc", resources = listOf("stream", "isrc"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolveIsrc(addon, "USUM71923546") } returns
            EclipseAddonTrack(id = "i1", isrc = "USUM71923546")
        coEvery { repository.stream(addon, "i1") } returns stream()

        val result = EclipseAddonStreamResolver(repository)
            .resolve(track().copy(isrc = "USUM71923546"))

        assertThat(result).isNotNull()
        coVerify(exactly = 0) { repository.resolve(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.search(any(), any(), any()) }
    }

    @Test
    fun `cloud falls back to resolve-isrc even when its manifest does not list isrc`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = addon("eclipse-music-cloud", resources = listOf("stream", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.resolveIsrc(cloud, "USUM71923546") } returns
            EclipseAddonTrack(id = "c1", isrc = "USUM71923546")
        coEvery { repository.stream(cloud, "c1") } returns stream()

        val result = EclipseAddonStreamResolver(repository)
            .resolve(track().copy(isrc = "USUM71923546"))

        assertThat(result).isNotNull()
        coVerify(exactly = 1) { repository.resolveIsrc(cloud, "USUM71923546") }
    }

    @Test
    fun `a 503 preparing is waited out and retried once on the same addon`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon("cloud", resources = listOf("stream", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(addon, "r1") } throws EclipseAddonException.Preparing(1) andThen
            stream(quality = "lossless", format = "flac")

        val result = EclipseAddonStreamResolver(repository).resolve(track())

        assertThat(result?.origin).isEqualTo("addon:cloud")
        coVerify(exactly = 2) { repository.stream(addon, "r1") }
    }

    @Test
    fun `a preparing addon gets four retries before the song is treated as a miss`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon("aero", resources = listOf("stream", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(addon, "r1") } throws EclipseAddonException.Preparing(0) andThenThrows
            EclipseAddonException.Preparing(0) andThenThrows
            EclipseAddonException.Preparing(0) andThenThrows
            EclipseAddonException.Preparing(0) andThen
            stream(quality = "lossless", format = "flac")

        val result = EclipseAddonStreamResolver(repository).resolve(track())

        assertThat(result?.origin).isEqualTo("addon:aero")
        coVerify(exactly = 5) { repository.stream(addon, "r1") }
    }

    /**
     * The poll window is the six seconds the player can afford, and the poll
     * happens every 1.5 s inside it.
     *
     * An addon that answers "preparing" for the whole window is not asked a
     * fifth time: the song goes to another source and the file is picked up
     * on a later play.
     */
    @Test
    fun `a preparing addon is polled every second and a half for six seconds`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon("aero", resources = listOf("stream", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(addon, "r1") } throws EclipseAddonException.Preparing(null)

        val started = currentTime
        val result = EclipseAddonStreamResolver(repository).resolve(track())

        assertThat(result).isNull()
        // Four waits of 1.5 s, then the answer that ends the wait.
        assertThat(currentTime - started).isEqualTo(6_000L)
        coVerify(exactly = 5) { repository.stream(addon, "r1") }
    }

    /**
     * A 503 "preparing" is the addon working, not the addon broken.
     *
     * Cooling it down would silence it for a minute - and the next songs
     * would go to YouTube while it was installing exactly the file they
     * need.
     */
    @Test
    fun `a preparing addon is asked again for the next song`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon("aero", resources = listOf("stream", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "r1") andThen EclipseAddonTrack(id = "r2")
        coEvery { repository.stream(addon, "r1") } throws EclipseAddonException.Preparing(null)
        coEvery { repository.stream(addon, "r2") } returns stream()

        val resolver = EclipseAddonStreamResolver(repository)
        val first = resolver.resolve(track().copy(id = 1L))
        // Another song: its own resolve, its own item id.
        val second = resolver.resolve(track().copy(id = 2L, title = "Save Your Tears"))

        assertThat(first).isNull()
        // The addon was not silenced: it answered the next song.
        assertThat(second).isNotNull()
        coVerify(exactly = 1) { repository.stream(addon, "r2") }
    }

    /**
     * The player must know that a fallback to another source was only standing
     * in: the addon is installing the file, and it is minutes away.
     */
    @Test
    fun `wasPreparing is set while the addon installs the file and cleared when it serves`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon("aero", resources = listOf("stream", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        var calls = 0
        coEvery { repository.stream(addon, "r1") } answers {
            calls++
            if (calls <= 5) throw EclipseAddonException.Preparing(null)
            stream(quality = "lossless", format = "flac")
        }

        val resolver = EclipseAddonStreamResolver(repository)
        val song = track().copy(id = 7L)

        assertThat(resolver.wasPreparing(song.id)).isFalse()
        // The whole window goes by without the file.
        assertThat(resolver.resolve(song)).isNull()
        assertThat(resolver.wasPreparing(song.id)).isTrue()

        // Now it has the file: the flag may go, and the song plays from it.
        assertThat(resolver.resolve(song)).isNotNull()
        assertThat(resolver.wasPreparing(song.id)).isFalse()
    }

    @Test
    fun `a 404 re-resolves the item once rather than retrying it blindly`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = addon("cloud", resources = listOf("stream", "resolve"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(addon, "r1") } throws EclipseAddonException.HttpFailure(404)

        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNull()
        // The id is dropped (it may have rotated) and resolved once more, then given up.
        coVerify(exactly = 2) { repository.resolve(addon, any(), any(), any(), any()) }
        coVerify(exactly = 2) { repository.stream(addon, "r1") }
    }

    // ── Transient failures and cooldown ───────────────────────────────────

    @Test
    fun `a 429 cools the addon, stops its other routes, and skips it on the next play`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val aero = addon("aero", resources = listOf("stream", "resolve", "isrc", "search"))
        val next = addon("next", resources = listOf("stream", "search"))
        coEvery { repository.enabledNow() } returns listOf(aero, next)
        coEvery { repository.resolve(aero, any(), any(), any(), any()) } throws
            EclipseAddonException.HttpFailure(429)
        coEvery { repository.search(aero, any(), any()) } returns listOf(
            EclipseAddonTrack(id = "late", title = "Blinding Lights", artist = "The Weeknd", duration = 200),
        )
        coEvery { repository.search(next, any(), any()) } returns listOf(
            EclipseAddonTrack(id = "n1", title = "Blinding Lights", artist = "The Weeknd", duration = 200),
        )
        coEvery { repository.stream(next, "n1") } returns stream()
        val resolver = EclipseAddonStreamResolver(repository)

        assertThat(resolver.resolve(track())?.origin).isEqualTo("addon:next")
        // The first transient failure stops resolve-isrc/search/query variants
        // and moves straight to the next addon.
        coVerify(exactly = 1) { repository.resolve(aero, any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.resolveIsrc(aero, any()) }
        coVerify(exactly = 0) { repository.search(aero, any(), any()) }
        coVerify(exactly = 1) { repository.search(next, any(), any()) }

        // The addon is cooling: the next play does not call it at all.
        assertThat(resolver.resolve(track())?.origin).isEqualTo("addon:next")
        coVerify(exactly = 1) { repository.resolve(aero, any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.search(aero, any(), any()) }
        coVerify(exactly = 2) { repository.search(next, any(), any()) }
    }

    @Test
    fun `a 403 Tidal resolve is not cached as a catalog miss`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } throws
            EclipseAddonException.HttpFailure(403)
        coEvery { repository.searchApple(cloud, any(), any()) } returns emptyList()
        val resolver = cachedResolver(repository)

        assertThat(resolver.resolve(track())).isNull()
        assertThat(resolver.resolve(track())).isNull()

        // A rights refusal is not "not in the catalog": both plays ask again.
        coVerify(exactly = 2) {
            repository.resolve(cloud, "Blinding Lights", "The Weeknd", any(), any())
        }
    }

    // ── Radio: Cloud first, non-Cloud after ────────────────────────────────

    @Test
    fun `radio tries Eclipse Cloud before a non-Cloud addon regardless of user order`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val local = addon("local", resources = listOf("stream", "search"))
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(local, cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "c1")
        coEvery { repository.stream(cloud, "c1") } returns stream()
        coEvery { repository.search(local, any(), any()) } returns listOf(
            EclipseAddonTrack(id = "l1", title = "Blinding Lights", artist = "The Weeknd", duration = 200),
        )
        coEvery { repository.stream(local, "l1") } returns stream()
        val resolver = EclipseAddonStreamResolver(repository)

        val radio = resolver.resolveForRadio(track())

        assertThat(radio?.origin).isEqualTo("addon:eclipse-music-cloud")
        coVerify(exactly = 0) { repository.search(local, any(), any()) }

        // Normal playback keeps the user's order: local comes first and serves.
        val normal = resolver.resolve(track())
        assertThat(normal?.origin).isEqualTo("addon:local")
        coVerify(exactly = 1) { repository.search(local, any(), any()) }
    }

    // ── Resolve cache ──────────────────────────────────────────────────────

    private fun cachedResolver(
        repository: EclipseAddonRepository,
        cache: EclipseAddonResolveCache = EclipseAddonResolveCache(),
    ) = EclipseAddonStreamResolver.forTest(repository, cache)

    private fun resolveOnly(id: String = "cloud") = addon(id, resources = listOf("stream", "resolve"))

    @Test
    fun `a cached resolve skips the metadata lookup on the next play`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = resolveOnly()
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(addon, "r1") } returns stream()
        val resolver = cachedResolver(repository)

        resolver.resolve(track())
        resolver.resolve(track())

        coVerify(exactly = 1) { repository.resolve(addon, any(), any(), any(), any()) }
        coVerify(exactly = 2) { repository.stream(addon, "r1") }
    }

    @Test
    fun `a cached id below the addon's quality setting is looked up again`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        // The addon asks for CD quality.
        val addon = resolveOnly().copy(settings = mapOf("quality" to "lossless"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        // The first answer names an AAC tier - the file the addon had then.
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "nb:abc:high")
        coEvery { repository.stream(addon, any()) } returns stream()
        val resolver = cachedResolver(repository)

        resolver.resolve(track())
        // The addon has since fetched the CD file. The cached id names the
        // worse tier, so it must not be trusted for six hours.
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "nb:abc:lossless")
        resolver.resolve(track())

        coVerify(exactly = 2) { repository.resolve(addon, any(), any(), any(), any()) }
    }

    @Test
    fun `a cached id is trusted when the setting asks for no particular tier`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = resolveOnly().copy(settings = mapOf("quality" to "max"))
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "nb:abc:high")
        coEvery { repository.stream(addon, any()) } returns stream()
        val resolver = cachedResolver(repository)

        resolver.resolve(track())
        resolver.resolve(track())

        coVerify(exactly = 1) { repository.resolve(addon, any(), any(), any(), any()) }
    }

    @Test
    fun `a cached miss is not looked up again, and another track is`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = resolveOnly()
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns null
        val resolver = cachedResolver(repository)

        resolver.resolve(track())
        resolver.resolve(track())
        coVerify(exactly = 1) { repository.resolve(addon, any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.stream(any(), any()) }

        resolver.resolve(track().copy(id = 2, title = "Another Song"))
        coVerify(exactly = 2) { repository.resolve(addon, any(), any(), any(), any()) }
    }

    @Test
    fun `a stream 404 drops the cached id and re-resolves once`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = resolveOnly()
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "r1") andThen EclipseAddonTrack(id = "r2")
        coEvery { repository.stream(addon, "r1") } returns stream() andThenThrows
            EclipseAddonException.HttpFailure(404)
        coEvery { repository.stream(addon, "r2") } returns stream()
        val resolver = cachedResolver(repository)

        resolver.resolve(track())
        val second = resolver.resolve(track())

        assertThat(second).isNotNull()
        coVerify(exactly = 2) { repository.resolve(addon, any(), any(), any(), any()) }
        coVerify(exactly = 2) { repository.stream(addon, "r1") }
        coVerify(exactly = 1) { repository.stream(addon, "r2") }
    }

    @Test
    fun `a settings change is a different cache key, so the id is resolved afresh`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val addon = resolveOnly()
        var enabled = listOf(addon)
        coEvery { repository.enabledNow() } answers { enabled }
        coEvery { repository.resolve(addon, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(addon, "r1") } returns stream()
        val resolver = cachedResolver(repository)

        resolver.resolve(track())
        enabled = listOf(addon.copy(settings = mapOf("quality" to "LOSSLESS")))
        resolver.resolve(track())

        // Two different cache keys: the base addon and the settings-changed copy.
        coVerify(exactly = 2) { repository.resolve(any(), any(), any(), any(), any()) }
    }

    // ── Cloud's Apple Music fallback ───────────────────────────────────────

    private val appleHit = EclipseAppleTrack(
        trackId = 6812560833L,
        trackName = "Blinding Lights",
        artistName = "The Weeknd",
        collectionName = "After Hours",
        trackTimeMillis = 200_238L,
        isrc = "FAKEISRC",
    )

    @Test
    fun `cloud falls back to Apple when Tidal has no item`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(cloud, "Blinding Lights", "The Weeknd") } returns listOf(appleHit)
        coEvery { repository.streamApple(cloud, "6812560833", any()) } returns stream(format = "hls", manifest = "hls")

        val result = EclipseAddonStreamResolver(repository).resolve(track())

        assertThat(result?.origin).isEqualTo("addon:eclipse-music-cloud")
        coVerify(exactly = 0) { repository.stream(any(), any()) }
        coVerify(exactly = 1) { repository.searchApple(cloud, any(), any()) }
    }

    @Test
    fun `a Tidal success ignores the parallel Apple search`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(cloud, "r1") } returns stream()
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(appleHit)

        val result = EclipseAddonStreamResolver(repository).resolve(track())

        assertThat(result?.origin).isEqualTo("addon:eclipse-music-cloud")
        coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
    }

    @Test
    fun `both Tidal and Apple missing is a miss`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(cloud, any(), any()) } returns emptyList()

        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNull()
        coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
    }

    @Test
    fun `a dead Tidal stream id falls back to Apple`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(cloud, "r1") } throws EclipseAddonException.HttpFailure(404)
        coEvery { repository.searchApple(cloud, any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(cloud, any(), any()) } returns stream(format = "hls", manifest = "hls")

        val result = EclipseAddonStreamResolver(repository).resolve(track())

        assertThat(result?.origin).isEqualTo("addon:eclipse-music-cloud")
        coVerify(exactly = 1) { repository.searchApple(cloud, any(), any()) }
    }

    @Test
    fun `a third-party addon never calls Apple`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val nebula = resolveOnly("nebula")
        coEvery { repository.enabledNow() } returns listOf(nebula)
        coEvery { repository.resolve(nebula, any(), any(), any(), any()) } returns null

        assertThat(EclipseAddonStreamResolver(repository).resolve(track())).isNull()
        coVerify(exactly = 0) { repository.searchApple(any(), any(), any()) }
        coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
    }

    @Test
    fun `a dead Apple stream id is dropped, so the next play searches again`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(cloud, any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(cloud, "6812560833", any()) } throws
            EclipseAddonException.HttpFailure(404)
        val resolver = cachedResolver(repository)

        assertThat(resolver.resolve(track())).isNull()
        assertThat(resolver.resolve(track())).isNull()

        // The first play cached the id, its 404 dropped it, the second searched again.
        coVerify(exactly = 2) { repository.searchApple(cloud, any(), any()) }
    }

    @Test
    fun `the Apple track id is cached, so the second play skips the iTunes search`() = runTest {        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(cloud, any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(cloud, "6812560833", any()) } returns stream(format = "hls")
        val resolver = cachedResolver(repository)

        resolver.resolve(track())
        resolver.resolve(track())

        coVerify(exactly = 1) { repository.searchApple(cloud, any(), any()) }
        coVerify(exactly = 2) { repository.streamApple(cloud, "6812560833", any()) }
    }

    // ── Cloud fastest-source (raced Tidal + Apple) ─────────────────────────

    private fun fastResolver(
        repository: EclipseAddonRepository,
        cache: EclipseAddonResolveCache = EclipseAddonResolveCache(),
    ) = EclipseAddonStreamResolver.forTest(repository, cache, cloudFastestSource = { true })

    private fun slowResolver(
        repository: EclipseAddonRepository,
        cache: EclipseAddonResolveCache = EclipseAddonResolveCache(),
    ) = EclipseAddonStreamResolver.forTest(repository, cache, cloudFastestSource = { false })

    private val mario = TrackEntity(
        title = "Mario",
        artist = "David Guetta & Jast",
        durationMs = 120_238,
    )

    private val marioHit = EclipseAppleTrack(
        trackId = 555_000_001L,
        trackName = "Mario",
        artistName = "David Guetta, Jast",
        trackTimeMillis = 120_238,
    )

    @Test
    fun `fast mode serves the strong Apple match while Tidal is still looking`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        // Tidal is slow and misses; iTunes matches exactly.
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(2_000)
            null
        }
        coEvery { repository.searchApple(cloud, any(), any()) } returns listOf(marioHit)
        coEvery { repository.streamApple(cloud, "555000001", any()) } returns stream(format = "hls")

        val result = fastResolver(repository).resolve(mario)

        assertThat(result?.origin).isEqualTo("addon:eclipse-music-cloud")
        coVerify(exactly = 1) { repository.streamApple(cloud, "555000001", any()) }
        coVerify(exactly = 0) { repository.stream(any(), any()) }
    }

    @Test
    fun `fast mode prefers Tidal when it answers first, and does not stream Apple`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns EclipseAddonTrack(id = "r1")
        coEvery { repository.stream(cloud, "r1") } returns stream()
        coEvery { repository.searchApple(cloud, any(), any()) } coAnswers {
            kotlinx.coroutines.delay(2_000)
            listOf(marioHit)
        }

        val result = fastResolver(repository).resolve(mario)

        assertThat(result?.origin).isEqualTo("addon:eclipse-music-cloud")
        coVerify(exactly = 1) { repository.stream(cloud, "r1") }
        coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
    }

    @Test
    fun `fast mode falls back to best for ambiguous or unknown-duration hits, never a weak artist`() = runTest {
        // Not strong, but the normal matcher still accepts these, so Apple may play.
        for (hits in listOf(
            listOf(marioHit, marioHit.copy(trackId = 555_000_002L)),
            listOf(marioHit.copy(trackId = 555_000_003L, trackTimeMillis = 0)),
        )) {
            val repository: EclipseAddonRepository = mockk()
            val cloud = resolveOnly("eclipse-music-cloud")
            coEvery { repository.enabledNow() } returns listOf(cloud)
            coEvery { repository.resolve(cloud, any(), any(), any(), any()) } coAnswers {
                kotlinx.coroutines.delay(2_000)
                null
            }
            coEvery { repository.searchApple(cloud, any(), any()) } returns hits
            coEvery { repository.streamApple(cloud, any(), any()) } returns stream(format = "hls")

            assertThat(fastResolver(repository).resolve(mario)).isNotNull()
            coVerify(exactly = 1) { repository.streamApple(cloud, any(), any()) }
        }

        // A wrong artist is rejected by best() too.
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(2_000)
            null
        }
        coEvery { repository.searchApple(cloud, any(), any()) } returns
            listOf(marioHit.copy(trackId = 555_000_004L, artistName = "Someone Else"))

        assertThat(fastResolver(repository).resolve(mario)).isNull()
        coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
    }

    @Test
    fun `a non-Cloud addon never calls Apple, fast mode or not`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val nebula = resolveOnly("nebula")
        coEvery { repository.enabledNow() } returns listOf(nebula)
        coEvery { repository.resolve(nebula, any(), any(), any(), any()) } returns null

        assertThat(fastResolver(repository).resolve(mario)).isNull()
        coVerify(exactly = 0) { repository.searchApple(any(), any(), any()) }
        coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
    }

    @Test
    fun `a Cloud Tidal miss is cached for six hours, a normal addon miss for five minutes`() = runTest {
        // Cloud: a second play an hour later does not pay the Tidal lookup again.
        var now = 1_000_000_000_000L
        val cloudCache = EclipseAddonResolveCache(clock = { now })
        val cloudRepo: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { cloudRepo.enabledNow() } returns listOf(cloud)
        coEvery { cloudRepo.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { cloudRepo.searchApple(cloud, any(), any()) } returns emptyList()
        val cloudResolver = slowResolver(cloudRepo, cloudCache)
        cloudResolver.resolve(mario)
        now += 60 * 60_000L
        // A speculative lookup may use the cached miss; a foreground play re-checks it.
        cloudResolver.resolve(mario, speculative = true)
        // Variant retries are part of the first play; the cached negative skips the full query.
        coVerify(exactly = 1) {
            cloudRepo.resolve(cloud, "Mario", "David Guetta & Jast", any(), any())
        }

        // A normal addon's miss expires after five minutes as before.
        var normalNow = 1_000_000_000_000L
        val normalCache = EclipseAddonResolveCache(clock = { normalNow })
        val normalRepo: EclipseAddonRepository = mockk()
        val nebula = resolveOnly("nebula")
        coEvery { normalRepo.enabledNow() } returns listOf(nebula)
        coEvery { normalRepo.resolve(nebula, any(), any(), any(), any()) } returns null
        val normalResolver = slowResolver(normalRepo, normalCache)
        normalResolver.resolve(mario)
        normalNow += 10 * 60_000L
        normalResolver.resolve(mario, speculative = true)
        coVerify(exactly = 2) {
            normalRepo.resolve(nebula, "Mario", "David Guetta & Jast", any(), any())
        }
    }

    // ── Persistent Apple lookup + queue warming ────────────────────────────

    @Test
    fun `a quality change still uses the cached Apple id, without another iTunes search`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        var enabled = listOf(cloud)
        coEvery { repository.enabledNow() } answers { enabled }
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(any(), "6812560833", any()) } returns stream()
        val resolver = cachedResolver(repository)

        resolver.resolve(track())
        // The user changes the Apple/Tidal quality: the Apple key has no settings snapshot.
        enabled = listOf(cloud.copy(settings = mapOf("appleQuality" to "LOSSLESS")))
        resolver.resolve(track())

        coVerify(exactly = 1) { repository.searchApple(any(), any(), any()) }
        coVerify(exactly = 2) { repository.streamApple(any(), "6812560833", any()) }
    }

    @Test
    fun `an Apple id cached on disk is used without an iTunes search`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.streamApple(cloud, "555000001", any()) } returns stream()
        val lookup: EclipseAppleLookupStore = mockk()
        coEvery { lookup.get("eclipse-music-cloud", any()) } returns
            com.stash.core.data.addons.AppleLookup("555000001", Long.MAX_VALUE)
        coEvery { lookup.put(any(), any(), any()) } returns Unit
        coEvery { lookup.remove(any(), any()) } returns Unit
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            cloudFastestSource = { false },
            appleLookup = lookup,
        )

        assertThat(resolver.resolve(mario)).isNotNull()
        coVerify(exactly = 0) { repository.searchApple(any(), any(), any()) }
        coVerify(exactly = 1) { repository.streamApple(cloud, "555000001", any()) }
    }

    @Test
    fun `a dead Apple id from the persistent cache is dropped and searched again`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { repository.streamApple(any(), any(), any()) } throws EclipseAddonException.HttpFailure(404)
        coEvery { repository.streamAppleAtTier(any(), any(), any(), any()) } throws
            EclipseAddonException.HttpFailure(404)
        coEvery { repository.searchApple(any(), any(), any()) } returns emptyList()

        var disk: com.stash.core.data.addons.AppleLookup? =
            com.stash.core.data.addons.AppleLookup("555000001", Long.MAX_VALUE)
        val lookup: EclipseAppleLookupStore = mockk()
        coEvery { lookup.get(any(), any()) } answers { disk }
        coEvery { lookup.remove(any(), any()) } answers {
            disk = null
            Unit
        }
        coEvery { lookup.put(any(), any(), any()) } returns Unit
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            cloudFastestSource = { false },
            appleLookup = lookup,
        )

        assertThat(resolver.resolve(mario)).isNull()
        coVerify(exactly = 1) { lookup.remove(any(), any()) }

        // The next play no longer has the id and searches iTunes once.
        assertThat(resolver.resolve(mario)).isNull()
        coVerify(exactly = 1) { repository.searchApple(any(), any(), any()) }
    }

    @Test
    fun `a disabled or removed Cloud addon never uses the cached Apple id`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        coEvery { repository.enabledNow() } returns emptyList()
        val resolver = cachedResolver(repository)

        assertThat(resolver.resolve(mario)).isNull()
        coVerify(exactly = 0) { repository.searchApple(any(), any(), any()) }
        coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
    }

    @Test
    fun `queue warming looks up Apple ids only, skipping files and cloud songs`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        every { repository.addonGeneration } returns kotlinx.coroutines.flow.MutableStateFlow(0L)
        coEvery { repository.searchApple(cloud, any(), any()) } returns listOf(appleHit)
        val resolver = cachedResolver(repository)

        resolver.warmAppleIds(
            listOf(
                track().copy(id = 1, isDownloaded = true),
                track().copy(id = 2, source = com.stash.core.model.MusicSource.CLOUD),
                track().copy(id = 3, title = "Another Song"),
            ),
        )

        coVerify(exactly = 1) { repository.searchApple(cloud, any(), any()) }
        coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
        // A second warm sees the memory cache and searches nothing.
        resolver.warmAppleIds(listOf(track().copy(id = 3, title = "Another Song")))
        coVerify(exactly = 1) { repository.searchApple(cloud, any(), any()) }
    }

    @Test
    fun `a Qobuz etsp expiry is used when exp is absent`() {
        val mapped = mapStream(
            EclipseAddonStream(
                url = "https://streaming-qobuz-std.akamaized.net/file?etsp=1762000000&uid=1",
                codec = "flac",
                manifest = "none",
                sampleRate = 44_100,
                bitDepth = 24,
            ),
            nowMs = now,
        )!!

        assertThat(mapped.expiresAtMs).isEqualTo(1_762_000_000_000L)
        assertThat(mapped.isHls).isFalse()
    }

    @Test
    fun `a persistent URL is served without calling Cloud`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        val stored = StreamUrl(
            url = "https://cdn.test/file.flac?etsp=1762000000",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "addon:eclipse-music-cloud",
        )
        val store: EclipseStreamUrlStore = mockk()
        coEvery { store.get(any(), any(), any()) } returns stored
        val memory: StreamUrlCache = mockk(relaxed = true)
        every { memory.get(any()) } returns null
        val diskResolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            cloudFastestSource = { false },
            appleLookup = null,
            urlCache = memory,
            streamUrlStore = store,
        )

        val result = diskResolver.resolve(mario)

        assertThat(result?.url).isEqualTo(stored.url)
        io.mockk.coVerify(exactly = 0) { repository.resolve(any(), any(), any(), any(), any()) }
        io.mockk.coVerify(exactly = 0) { repository.streamApple(any(), any(), any()) }
        io.mockk.verify { memory.put(mario.id, stored) }
    }

    @Test
    fun `a disabled addon does not read the persistent URL store`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        coEvery { repository.enabledNow() } returns emptyList()
        val store: EclipseStreamUrlStore = mockk(relaxed = true)
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            cloudFastestSource = { false },
            streamUrlStore = store,
        )

        assertThat(resolver.resolve(mario)).isNull()
        io.mockk.coVerify(exactly = 0) { store.get(any(), any(), any()) }
    }

    @Test
    fun `a served addon URL is written to both caches`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val nebula = addon("nebula", resources = listOf("stream", "search"))
        coEvery { repository.enabledNow() } returns listOf(nebula)
        coEvery { repository.search(nebula, any(), any()) } returns listOf(
            EclipseAddonTrack(id = "n1", title = "Blinding Lights", artist = "The Weeknd", duration = 200),
        )
        coEvery { repository.stream(nebula, "n1") } returns stream()
        val store: EclipseStreamUrlStore = mockk(relaxed = true)
        coEvery { store.get(any(), any(), any()) } returns null
        val memory: StreamUrlCache = mockk(relaxed = true)
        every { memory.get(any()) } returns null
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            cloudFastestSource = { false },
            urlCache = memory,
            streamUrlStore = store,
        )

        assertThat(resolver.resolve(track())).isNotNull()
        io.mockk.coVerify(exactly = 1) { store.put(nebula.id, any(), any(), any()) }
        io.mockk.verify(exactly = 1) { memory.put(track().id, any()) }
    }

    @Test
    fun `a search-only addon tries the query variants in order within the 25-hit limit`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val searchOnly = addon("search-only", resources = listOf("stream", "search"))
        coEvery { repository.enabledNow() } returns listOf(searchOnly)
        coEvery { repository.search(searchOnly, any(), 25) } answers {
            if (secondArg<String>() == "Ely Oaks Stay") {
                listOf(EclipseAddonTrack(id = "s1", title = "Stay", artist = "Ely Oaks", duration = 159))
            } else {
                emptyList()
            }
        }
        coEvery { repository.stream(searchOnly, "s1") } returns stream()

        val result = EclipseAddonStreamResolver(repository).resolve(
            TrackEntity(
                title = "Stay - Ely Oaks Version",
                artist = "Ely Oaks, Misdemeanor",
                durationMs = 159_246,
            ),
        )

        assertThat(result).isNotNull()
        coVerify(exactly = 1) {
            repository.search(searchOnly, "Ely Oaks, Misdemeanor Stay - Ely Oaks Version", 25)
        }
        coVerify(exactly = 1) { repository.search(searchOnly, "Ely Oaks Stay", 25) }
        // The third variant is not paid for once the second found the song.
        coVerify(exactly = 0) { repository.search(searchOnly, "Ely Oaks Stay - Ely Oaks Version", 25) }
    }

    @Test
    fun `a version suffix and multi-artist credit fall back to the cleaned Tidal query, then cache`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery {
            repository.resolve(cloud, "Stay - Ely Oaks Version", "Ely Oaks, Misdemeanor", any(), any())
        } returns null
        coEvery { repository.resolve(cloud, "Stay", "Ely Oaks", any(), null) } returns
            EclipseAddonTrack(id = "488516610", title = "Stay", artist = "Ely Oaks", isrc = "FAKEISRC")
        coEvery { repository.stream(cloud, "488516610") } returns stream()
        val resolver = cachedResolver(repository)
        val stay = TrackEntity(
            title = "Stay - Ely Oaks Version",
            artist = "Ely Oaks, Misdemeanor",
            durationMs = 159_246,
        )

        assertThat(resolver.resolve(stay)).isNotNull()
        coVerify(exactly = 1) { repository.resolve(cloud, "Stay", "Ely Oaks", any(), null) }

        // The found id is cached under the normal track identity: no second search.
        resolver.resolve(stay)
        coVerify(exactly = 1) {
            repository.resolve(cloud, "Stay - Ely Oaks Version", "Ely Oaks, Misdemeanor", any(), any())
        }
        coVerify(exactly = 1) { repository.resolve(cloud, "Stay", "Ely Oaks", any(), null) }
    }

    @Test
    fun `a foreground play ignores a stored Apple negative and searches iTunes`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(any(), "6812560833", any()) } returns stream(format = "hls")
        val lookup: EclipseAppleLookupStore = mockk(relaxed = true)
        coEvery { lookup.get(any(), any()) } returns
            com.stash.core.data.addons.AppleLookup(null, Long.MAX_VALUE)
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            cloudFastestSource = { false },
            appleLookup = lookup,
        )

        assertThat(resolver.resolve(track())).isNotNull()
        coVerify(exactly = 1) { repository.searchApple(any(), any(), any()) }
    }

    @Test
    fun `a speculative resolve may use the stored Apple negative`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        val lookup: EclipseAppleLookupStore = mockk(relaxed = true)
        coEvery { lookup.get(any(), any()) } returns
            com.stash.core.data.addons.AppleLookup(null, Long.MAX_VALUE)
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            cloudFastestSource = { false },
            appleLookup = lookup,
        )

        assertThat(resolver.resolve(track(), speculative = true)).isNull()
        coVerify(exactly = 0) { repository.searchApple(any(), any(), any()) }
    }

    @Test
    fun `the real Dancin remix hit matches and streams its Apple id`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(
            EclipseAppleTrack(
                trackId = 1_890_207_695L,
                trackName = "Dancin (feat. Luvli) [southstar Remix]",
                artistName = "Aaron Smith & southstar",
                trackTimeMillis = 173_333,
            ),
        )
        coEvery { repository.streamApple(any(), "1890207695", any()) } returns stream(format = "hls")
        val resolver = cachedResolver(repository)
        val dancin = TrackEntity(
            title = "Dancin (feat. Luvli) - southstar Remix",
            artist = "Aaron Smith, Luvli, southstar",
            durationMs = 173_333,
        )

        assertThat(resolver.resolve(dancin)).isNotNull()
        coVerify(exactly = 1) { repository.streamApple(cloud, "1890207695", any()) }
    }

    @Test
    fun `fast mode starts the Apple stream for the exact Dancin hit`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(2_000)
            null
        }
        val hit = EclipseAppleTrack(
            trackId = 1_890_207_695L,
            trackName = "Dancin (feat. Luvli) [southstar Remix]",
            artistName = "Aaron Smith & southstar",
            trackTimeMillis = 173_333,
        )
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(hit)
        coEvery { repository.streamApple(any(), "1890207695", any()) } returns stream(format = "hls")
        val dancin = TrackEntity(
            title = "Dancin (feat. Luvli) - southstar Remix",
            artist = "Aaron Smith, Luvli, southstar",
            durationMs = 173_333,
        )

        assertThat(fastResolver(repository).resolve(dancin)).isNotNull()
        coVerify(exactly = 1) { repository.streamApple(cloud, "1890207695", any()) }
    }

    @Test
    fun `fast mode falls back to best when the strict gate declines`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(2_000)
            null
        }
        // 12 s apart: inside the matcher's 15 s, outside the fast gate's 10 s.
        val hit = appleHit.copy(trackTimeMillis = 185_333)
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(hit)
        coEvery { repository.streamApple(any(), "6812560833", any()) } returns stream(format = "hls")

        assertThat(fastResolver(repository).resolve(track())).isNotNull()
        coVerify(exactly = 1) { repository.streamApple(any(), "6812560833", any()) }
    }

    @Test
    fun `the connector-token artist is a strong match`() = runTest {
        val resolver = cachedResolver(mockk(relaxed = true))
        val hit = EclipseAppleTrack(
            trackId = 1_890_207_695L,
            trackName = "Dancin (feat. Luvli) [southstar Remix]",
            artistName = "Aaron Smith & southstar",
            trackTimeMillis = 173_333,
        )
        val dancin = TrackEntity(
            title = "Dancin (feat. Luvli) - southstar Remix",
            artist = "Aaron Smith, Luvli, southstar",
            durationMs = 173_333,
        )

        assertThat(resolver.isStrongAppleHit(dancin, hit)).isTrue()
        assertThat(resolver.strongAppleId(dancin, listOf(hit))).isEqualTo("1890207695")
    }

    @Test
    fun `an Apple 404 at the top tier falls back to a lower tier`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(any(), "6812560833", any()) } throws
            EclipseAddonException.HttpFailure(404)
        coEvery { repository.streamAppleAtTier(any(), "6812560833", any(), "LOSSLESS") } returns stream(format = "hls")

        val result = cachedResolver(repository).resolve(track())

        assertThat(result).isNotNull()
        coVerify(exactly = 1) { repository.streamAppleAtTier(any(), "6812560833", any(), "LOSSLESS") }
    }

    @Test
    fun `an Apple id that 404s at every tier is never requested again this session`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(any(), "6812560833", any()) } throws
            EclipseAddonException.HttpFailure(404)
        coEvery { repository.streamAppleAtTier(any(), any(), any(), any()) } throws
            EclipseAddonException.HttpFailure(404)
        val resolver = cachedResolver(repository)

        assertThat(resolver.resolve(track())).isNull()
        assertThat(resolver.resolve(track())).isNull()

        // Two tiers on the first play (one primary + LOSSLESS); the dead id is filtered on the second.
        coVerify(exactly = 1) { repository.streamApple(any(), "6812560833", any()) }
        coVerify(exactly = 1) { repository.streamAppleAtTier(any(), "6812560833", any(), any()) }
    }

    @Test
    fun `an Apple id that 403s is retried on the next play`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(cloud, any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(cloud, "6812560833", any()) } throws
            EclipseAddonException.HttpFailure(403)
        coEvery { repository.streamAppleAtTier(cloud, any(), any(), any()) } throws
            EclipseAddonException.HttpFailure(403)
        val resolver = cachedResolver(repository)

        assertThat(resolver.resolve(track())).isNull()
        assertThat(resolver.resolve(track())).isNull()

        // A rights refusal is not a permanent 404 or an Apple negative: the next
        // play searches iTunes again and asks for the same id.
        coVerify(exactly = 2) { repository.searchApple(cloud, any(), any()) }
        coVerify(exactly = 2) { repository.streamApple(cloud, "6812560833", any()) }
    }

    @Test
    fun `a dead Apple id from a 404 is retried after an addon change`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        val generation = kotlinx.coroutines.flow.MutableStateFlow(0L)
        every { repository.addonGeneration } returns generation
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(cloud, any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(cloud, "6812560833", any()) } throws
            EclipseAddonException.HttpFailure(404)
        coEvery { repository.streamAppleAtTier(cloud, any(), any(), any()) } throws
            EclipseAddonException.HttpFailure(404)
        val resolver = cachedResolver(repository)

        assertThat(resolver.resolve(track())).isNull()
        assertThat(resolver.resolve(track())).isNull() // the dead id is filtered
        generation.value = 1L // an addon/quality setting changed
        assertThat(resolver.resolve(track())).isNull()

        // Once per play that was allowed to request the id; the filtered play was not.
        coVerify(exactly = 2) { repository.streamApple(cloud, "6812560833", any()) }
    }

    @Test
    fun `a dead Apple id from a 404 is retried after the account changes`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        val addonGeneration = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val accountFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val account: com.stash.core.data.addons.EclipseAccountManager = mockk()
        every { account.accountGeneration } returns accountFlow
        every { repository.addonGeneration } returns addonGeneration
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(cloud, any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(cloud, any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(cloud, "6812560833", any()) } throws
            EclipseAddonException.HttpFailure(404)
        coEvery { repository.streamAppleAtTier(cloud, any(), any(), any()) } throws
            EclipseAddonException.HttpFailure(404)
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            account = account,
        )

        assertThat(resolver.resolve(track())).isNull()
        assertThat(resolver.resolve(track())).isNull()
        accountFlow.value = 1L
        assertThat(resolver.resolve(track())).isNull()

        coVerify(exactly = 2) { repository.streamApple(cloud, "6812560833", any()) }
    }

    @Test
    fun `a foreground Cloud play bypasses a cached Tidal miss, speculative does not`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        val resolver = cachedResolver(repository)

        resolver.resolve(mario) // records the miss
        resolver.resolve(mario, speculative = true) // uses it
        coVerify(exactly = 2) { repository.resolve(any(), any(), any(), any(), any()) }
        // Full query + one cleaned variant on the first play only.
    }

    @Test
    fun `an Apple 404 without an ISRC retries once with the enriched ISRC`() = runTest {
        val repository: EclipseAddonRepository = mockk()
        val cloud = resolveOnly("eclipse-music-cloud")
        coEvery { repository.enabledNow() } returns listOf(cloud)
        coEvery { repository.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { repository.searchApple(any(), any(), any()) } returns listOf(appleHit)
        coEvery { repository.streamApple(any(), "6812560833", isNull()) } throws
            EclipseAddonException.HttpFailure(404)
        coEvery { repository.streamApple(any(), "6812560833", "FAKEISRC") } returns stream(format = "hls")
        coEvery { repository.streamAppleAtTier(any(), any(), any(), any()) } throws
            EclipseAddonException.HttpFailure(404)
        val enricher: com.stash.core.data.addons.EclipseIsrcEnricher = mockk {
            coEvery { isrcFor(any()) } returns "FAKEISRC"
        }
        val resolver = EclipseAddonStreamResolver.forTest(
            repository,
            EclipseAddonResolveCache(),
            cloudFastestSource = { false },
            isrcEnricher = enricher,
        )

        assertThat(resolver.resolve(track())).isNotNull()
        coVerify(exactly = 1) { repository.streamApple(any(), "6812560833", "FAKEISRC") }
    }

    private fun track() = TrackEntity(title = "Blinding Lights", artist = "The Weeknd", durationMs = 200_000)
}
