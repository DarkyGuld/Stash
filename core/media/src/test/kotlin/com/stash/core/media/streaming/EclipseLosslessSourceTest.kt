package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EclipseLosslessSourceTest {
    private val repository: EclipseAddonRepository = mockk()
    private val resolver: EclipseAddonStreamResolver = mockk()
    private val dao: TrackDao = mockk()

    private val row = TrackEntity(id = 7, title = "Song", artist = "Artist", durationMs = 200_000)
    private val query = TrackQuery(artist = "Artist", title = "Song", trackId = 7)

    private fun source() = EclipseLosslessSource(repository, resolver, dao)

    private fun cloud(enabled: Boolean = true) = EclipseAddon(
        id = "eclipse-music-cloud",
        name = "Cloud",
        version = "1",
        manifestUrl = "https://addons.test/cloud/manifest.json",
        enabled = enabled,
        resources = listOf("stream", "resolve"),
    )

    private fun progressive() = StreamUrl(
        url = "https://streaming-qobuz-std.akamaized.net/file?etsp=1762000000&sig=fake",
        expiresAtMs = 1_762_000_000_000L,
        codec = "flac",
        bitsPerSample = 24,
        sampleRateHz = 44_100,
        origin = "addon:Cloud",
        isHls = false,
        isDash = false,
    )

    @Test
    fun `a direct Qobuz-shaped URL maps to a download result`() = runTest {
        coEvery { dao.getById(7) } returns row
        coEvery { resolver.resolve(row) } returns progressive()

        val result = source().resolve(query)!!

        assertThat(result.sourceId).isEqualTo(EclipseLosslessSource.ID)
        assertThat(result.downloadUrl).isEqualTo(progressive().url)
        assertThat(result.downloadHeaders).isEmpty()
        assertThat(result.confidence).isEqualTo(1.0f)
        assertThat(result.format.codec).isEqualTo("flac")
        assertThat(result.format.bitsPerSample).isEqualTo(24)
        assertThat(result.format.sampleRateHz).isEqualTo(44_100)
        assertThat(result.format.fileExtension).isEqualTo("flac")
        assertThat(result.isDash).isFalse()
    }

    @Test
    fun `HLS and DASH answers are not downloadable here`() = runTest {
        coEvery { dao.getById(7) } returns row
        coEvery { resolver.resolve(row) } returns progressive().copy(isHls = true)
        assertThat(source().resolve(query)).isNull()

        coEvery { resolver.resolve(row) } returns progressive().copy(isDash = true)
        assertThat(source().resolve(query)).isNull()
    }

    @Test
    fun `isEnabled counts only enabled resolvable addons`() = runTest {
        coEvery { repository.enabledNow() } returns listOf(cloud())
        assertThat(source().isEnabled()).isTrue()

        coEvery { repository.enabledNow() } returns listOf(cloud(enabled = false))
        assertThat(source().isEnabled()).isFalse()

        // A search-only addon has no item to resolve from metadata.
        coEvery { repository.enabledNow() } returns listOf(
            cloud().copy(id = "search-only", resources = listOf("stream", "search")),
        )
        assertThat(source().isEnabled()).isFalse()
    }

    @Test
    fun `a resolver miss or a row without an id is a normal miss`() = runTest {
        coEvery { dao.getById(7) } returns row
        coEvery { resolver.resolve(row) } returns null
        assertThat(source().resolve(query)).isNull()

        assertThat(source().resolve(query.copy(trackId = null))).isNull()
        assertThat(source().resolve(query.copy(trackId = 99))).isNull()
    }

    @Test
    fun `the source id matches the availability row and joins the registry`() = runTest {
        coEvery { repository.enabledNow() } returns listOf(cloud())
        val source = source()
        assertThat(source.id).isEqualTo("eclipse-addon")
        assertThat(source.displayName).isEqualTo("Eclipse addons")

        val prefs: com.stash.data.download.lossless.LosslessSourcePreferences = mockk {
            coEvery { priorityOrderNow() } returns emptyList()
        }
        val registry = LosslessSourceRegistry(
            sources = setOf(source),
            prefs = prefs,
            healthGate = mockk(relaxed = true),
            streamingPreference = mockk(relaxed = true),
            availability = mockk(relaxed = true),
        )

        assertThat(registry.orderedSources().map { it.id }).contains("eclipse-addon")
    }
}
