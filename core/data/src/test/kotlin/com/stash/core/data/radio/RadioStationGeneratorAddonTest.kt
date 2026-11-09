package com.stash.core.data.radio

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonRepository
import com.stash.core.data.addons.EclipseAddonTrack
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarArtist
import com.stash.core.data.lastfm.LastFmSimilarTrack
import com.stash.core.data.lastfm.LastFmTopTrack
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.ytmusic.CanonicalMatch
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Addon-first radio resolution: with only an Eclipse addon enabled (YouTube
 * source off) every Last.fm candidate must still become a playable track, as a
 * metadata-only [com.stash.core.model.Track] the player resolves at play time.
 * YouTube stays the fallback whenever the addon misses or none is enabled.
 */
class RadioStationGeneratorAddonTest {
    private val lastFm: LastFmApiClient = mockk(relaxed = true)
    private val yt: YTMusicApiClient = mockk(relaxed = true)
    private val addons: EclipseAddonRepository = mockk(relaxed = true)
    private val prefs: StreamingPreference = mockk()

    private val addon = EclipseAddon(
        id = "tidal-addon",
        name = "Tidal addon",
        version = "1.0.0",
        manifestUrl = "https://addons.test/manifest.json",
        resources = listOf("resolve", "stream"),
    )
    private val cloudAddon = addon.copy(id = "eclipse-music-cloud", name = "Eclipse Cloud")
    private val secondAddon = addon.copy(
        id = "second-addon",
        name = "Second addon",
        manifestUrl = "https://addons.test/second/manifest.json",
    )

    private fun gen() = RadioStationGenerator(lastFm, yt, addons)
    private fun genWithPrefs() = RadioStationGenerator(lastFm, yt, addons, prefs)

    /** One similar track: the seed's own candidate carries a videoId and never resolves. */
    private fun oneSimilarTrack() {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("Tidal Artist", "Tidal Song", 0.9f)))
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(emptyList())
    }

    @Test fun `addon hit emits a metadata-only track and skips the YouTube search`() = runTest {
        oneSimilarTrack()
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery {
            addons.resolve(addon, "Tidal Song", "Tidal Artist", null, null)
        } returns EclipseAddonTrack(
            id = "tidal:123",
            title = "Tidal Song",
            artist = "Tidal Artist",
            duration = 210, // seconds per the addon protocol
            artworkUrl = "https://cdn.test/cover.jpg",
        )

        val (_, batch) = gen().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        val track = batch.single { it.youtubeId == null }
        assertThat(track.id).isEqualTo(("addon:" + "tidal:123").hashCode().toLong())
        assertThat(track.title).isEqualTo("Tidal Song")
        assertThat(track.artist).isEqualTo("Tidal Artist")
        assertThat(track.durationMs).isEqualTo(210_000L)
        assertThat(track.albumArtUrl).isEqualTo("https://cdn.test/cover.jpg")
        assertThat(track.isStreamable).isTrue()
        coVerify(exactly = 0) { yt.searchCanonicalMatch(any(), any()) }
        // A resolve HIT is final: no search round-trip.
        coVerify(exactly = 0) { addons.search(any(), any(), any()) }
    }

    @Test fun `resolve miss falls back to the addon search and emits the searched id`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("DJ Snake, AlunaGeorge", "You Know You Like It", 0.9f)))
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(emptyList())
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery {
            addons.search(addon, "You Know You Like It DJ Snake, AlunaGeorge", 10)
        } returns listOf(
            EclipseAddonTrack(
                id = "38014823",
                title = "You Know You Like It",
                artist = "DJ Snake, AlunaGeorge",
                duration = 247,
                isrc = "USUM71414293",
            ),
            // A same-artist decoy with a different title must not win.
            EclipseAddonTrack(id = "111", title = "Get Low", artist = "DJ Snake", duration = 200),
        )

        val (_, batch) = gen().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        val track = batch.single { it.youtubeId == null }
        assertThat(track.id).isEqualTo(("addon:" + "38014823").hashCode().toLong())
        assertThat(track.title).isEqualTo("You Know You Like It")
        assertThat(track.artist).isEqualTo("DJ Snake, AlunaGeorge")
        assertThat(track.durationMs).isEqualTo(247_000L)
        // Both variants are issued in parallel; only the full-credit one hit here.
        coVerify(exactly = 1) { addons.search(addon, "You Know You Like It DJ Snake, AlunaGeorge", 10) }
        coVerify(exactly = 1) { addons.search(addon, "You Know You Like It DJ Snake", 10) }
        coVerify(exactly = 0) { yt.searchCanonicalMatch(any(), any()) }
    }

    @Test fun `a live version from search is rejected`() = runTest {
        oneSimilarTrack()
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { addons.search(any(), any(), any()) } returns listOf(
            EclipseAddonTrack(id = "live-1", title = "Tidal Song (Live)", artist = "Tidal Artist", duration = 230),
        )
        coEvery { yt.searchCanonicalMatch("Tidal Artist", "Tidal Song") } returns
            CanonicalMatch(videoId = "vTidal00001", thumbnailUrl = null)

        val (_, batch) = gen().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        val resolved = batch.single { it.artist == "Tidal Artist" }
        assertThat(resolved.youtubeId).isEqualTo("vTidal00001")
        assertThat(batch.none { it.youtubeId == null }).isTrue()
    }

    @Test fun `an edit label on the target is accepted from search`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success(
                listOf(LastFmSimilarTrack("DJ Snake, AlunaGeorge", "You Know You Like It (Radio Edit)", 0.9f)),
            )
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(emptyList())
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { addons.search(any(), any(), any()) } returns listOf(
            EclipseAddonTrack(id = "38014823", title = "You Know You Like It", artist = "DJ Snake, AlunaGeorge", duration = 247),
        )

        val (_, batch) = gen().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        val track = batch.single { it.youtubeId == null }
        assertThat(track.id).isEqualTo(("addon:" + "38014823").hashCode().toLong())
        // Metadata comes from the candidate, not the hit's shorter label.
        assertThat(track.title).isEqualTo("You Know You Like It (Radio Edit)")
        coVerify(exactly = 0) { yt.searchCanonicalMatch(any(), any()) }
    }

    @Test fun `an addon error still falls back to the YouTube search`() = runTest {
        oneSimilarTrack()
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        coEvery { addons.search(any(), any(), any()) } throws RuntimeException("offline")
        coEvery { yt.searchCanonicalMatch("Tidal Artist", "Tidal Song") } returns
            CanonicalMatch(videoId = "vTidal00001", thumbnailUrl = null)

        val (_, batch) = gen().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        val resolved = batch.single { it.artist == "Tidal Artist" }
        assertThat(resolved.youtubeId).isEqualTo("vTidal00001")
    }

    @Test fun `addon miss falls back to the YouTube search`() = runTest {
        oneSimilarTrack()
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { addons.search(any(), any(), any()) } returns emptyList()
        coEvery { yt.searchCanonicalMatch("Tidal Artist", "Tidal Song") } returns
            CanonicalMatch(videoId = "vTidal00001", thumbnailUrl = null)

        val (_, batch) = gen().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        val resolved = batch.single { it.artist == "Tidal Artist" }
        assertThat(resolved.youtubeId).isEqualTo("vTidal00001")
        coVerify(exactly = 1) { addons.resolve(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { addons.search(any(), any(), any()) }
        coVerify(exactly = 1) { yt.searchCanonicalMatch("Tidal Artist", "Tidal Song") }
    }

    @Test fun `no enabled addon keeps the YouTube-only behaviour`() = runTest {
        oneSimilarTrack()
        coEvery { addons.enabledNow() } returns emptyList()
        coEvery { yt.searchCanonicalMatch(any(), any()) } returns
            CanonicalMatch(videoId = "vTidal00001", thumbnailUrl = null)

        val (_, batch) = gen().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        assertThat(batch.mapNotNull { it.youtubeId }).contains("vTidal00001")
        assertThat(batch.all { !it.youtubeId.isNullOrBlank() }).isTrue()
        coVerify(exactly = 0) { addons.resolve(any(), any(), any(), any(), any()) }
    }

    @Test fun `the same addon item is emitted only once per session`() = runTest {
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "tidal:777", title = "Same", artist = "Same", duration = 200)

        val generator = gen()
        val session = RadioSession(
            RadioSeed.Song("S", "A"),
            mutableListOf(
                RadioCandidate("Artist A", "Song A", null, 1f),
                RadioCandidate("Artist B", "Song B", null, 1f),
            ),
            mutableSetOf(),
        )

        val first = generator.drainBatch(session, 10)
        assertThat(first).hasSize(1)
        assertThat(first.single().youtubeId).isNull()

        // A later batch reaching the same addon item is deduped by the played set.
        session.ordered += RadioCandidate("Artist C", "Song C", null, 1f)
        val second = generator.drainBatch(session, 10)
        assertThat(second).isEmpty()
    }

    @Test fun `YouTube disabled never searches YouTube and emits only addon hits`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success(
                listOf(
                    LastFmSimilarTrack("Tidal Artist", "Tidal Song", 0.9f),
                    LastFmSimilarTrack("Other Artist", "Missing Song", 0.8f),
                ),
            )
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(emptyList())
        coEvery { prefs.isYoutubeEnabled() } returns false
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } answers {
            if (secondArg<String>() == "Tidal Song") {
                EclipseAddonTrack(id = "tidal:5", title = "Tidal Song", artist = "Tidal Artist", duration = 200)
            } else {
                null
            }
        }
        coEvery { addons.search(any(), any(), any()) } returns emptyList()

        val (_, batch) = genWithPrefs().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        // Seed (known videoId) + the addon hit; the addon miss is dropped, never searched on YouTube.
        assertThat(batch).hasSize(2)
        assertThat(batch.single { it.artist == "Tidal Artist" }.youtubeId).isNull()
        assertThat(batch.none { it.artist == "Other Artist" }).isTrue()
        coVerify(exactly = 0) { yt.searchCanonicalMatch(any(), any()) }
    }

    @Test fun `addons disabled never touches the addon repository`() = runTest {
        oneSimilarTrack()
        coEvery { prefs.isYoutubeEnabled() } returns true
        coEvery { prefs.isEclipseAddonsEnabled() } returns false
        coEvery { yt.searchCanonicalMatch("Tidal Artist", "Tidal Song") } returns
            CanonicalMatch(videoId = "vTidal00001", thumbnailUrl = null)

        val (_, batch) = genWithPrefs().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        assertThat(batch.single { it.artist == "Tidal Artist" }.youtubeId).isEqualTo("vTidal00001")
        coVerify(exactly = 0) { addons.enabledNow() }
        coVerify(exactly = 0) { addons.resolve(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { addons.search(any(), any(), any()) }
    }

    @Test fun `Cloud addon searches first and a search hit skips resolve`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("DJ Snake, AlunaGeorge", "You Know You Like It", 0.9f)))
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(emptyList())
        coEvery { prefs.isYoutubeEnabled() } returns true
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { addons.enabledNow() } returns listOf(cloudAddon)
        coEvery {
            addons.search(cloudAddon, "You Know You Like It DJ Snake, AlunaGeorge", 10)
        } returns listOf(
            EclipseAddonTrack(id = "38014823", title = "You Know You Like It", artist = "DJ Snake, AlunaGeorge", duration = 247),
        )

        val (_, batch) = genWithPrefs().start(RadioSeed.Song("Seed Song", "Seed Artist", ytVideoId = "vSeed000001"))

        val track = batch.single { it.youtubeId == null }
        assertThat(track.id).isEqualTo(("addon:" + "38014823").hashCode().toLong())
        // Both variants are fired in parallel; the search hit means no resolve.
        coVerify(exactly = 2) { addons.search(cloudAddon, any(), 10) }
        // Search-first means the slower resolve is never asked on a search hit.
        coVerify(exactly = 0) { addons.resolve(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { yt.searchCanonicalMatch(any(), any()) }
    }

    @Test fun `similar and top tracks are pooled together`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("Other Artist", "Similar Song", 0.9f)))
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns
            Result.success(listOf(LastFmTopTrack("Seed Artist", "Top Song", 100)))
        coEvery { addons.enabledNow() } returns emptyList()
        coEvery { yt.searchCanonicalMatch(any(), any()) } answers {
            CanonicalMatch(
                videoId = "v%010d".format(secondArg<String>().hashCode().toLong() and 0x7fffffffL),
                thumbnailUrl = null,
            )
        }

        val (_, batch) = gen().start(RadioSeed.Song("Seed", "Seed Artist", ytVideoId = "vSeed000001"))

        // Both parallel Last.fm legs reach the pool (seed + top + similar).
        assertThat(batch.map { it.title }).containsAtLeast("Seed", "Top Song", "Similar Song")
    }

    @Test fun `the primary-artist variant finds a multi-artist-credit track`() = runTest {
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { addons.search(addon, any(), 10) } answers {
            // The catalogue search only finds it without the second credited artist.
            if (secondArg<String>() == "Milky Way BENNETT") {
                listOf(EclipseAddonTrack(id = "448663796", title = "Milky Way", artist = "BENNETT", duration = 172))
            } else {
                emptyList()
            }
        }

        val session = RadioSession(
            RadioSeed.Song("Milky Way", "BENNETT, Creeds"),
            mutableListOf(RadioCandidate("BENNETT, Creeds", "Milky Way", null, 1f)),
            mutableSetOf(),
        )
        val batch = gen().drainBatch(session, 10)

        val track = batch.single()
        assertThat(track.id).isEqualTo(("addon:" + "448663796").hashCode().toLong())
        assertThat(track.youtubeId).isNull()
        // Both variants were issued (in parallel); only the primary-artist query
        // can find it, and the full-credit query was still sent.
        coVerify(exactly = 1) { addons.search(addon, "Milky Way BENNETT, Creeds", 10) }
        coVerify(exactly = 1) { addons.search(addon, "Milky Way BENNETT", 10) }
        coVerify(exactly = 2) { addons.search(addon, any(), 10) }
        coVerify(exactly = 0) { yt.searchCanonicalMatch(any(), any()) }
    }

    @Test fun `a hit on the second query variant stops further searches`() = runTest {
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { addons.search(addon, any(), 10) } answers {
            if (secondArg<String>() == "Song Artist A") {
                listOf(EclipseAddonTrack(id = "v2hit", title = "Song", artist = "Artist A", duration = 200))
            } else {
                emptyList()
            }
        }

        val session = RadioSession(
            RadioSeed.Song("Song - Ely Oaks Version", "Artist A, Artist B"),
            mutableListOf(RadioCandidate("Artist A, Artist B", "Song - Ely Oaks Version", null, 1f)),
            mutableSetOf(),
        )
        val batch = gen().drainBatch(session, 10)

        assertThat(batch.single().id).isEqualTo(("addon:" + "v2hit").hashCode().toLong())
        // Two variants are fired (in parallel); the hit on the second proves the
        // search stopped at two and no third query was issued.
        coVerify(exactly = 2) { addons.search(addon, any(), 10) }
    }

    @Test fun `thin seed pulls neighbours when YouTube is enabled`() = runTest {
        // The device-log shape: similar=0 but seedTop=5. Last.fm's own answer for
        // these seeds — the station still needs the neighbour graph.
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns Result.success(emptyList())
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(
            (1..5).map { LastFmTopTrack("Seed Artist", "Top $it", 100 - it) },
        )
        coEvery { lastFm.getSimilarArtists("Seed Artist", any()) } returns
            Result.success(listOf(LastFmSimilarArtist("Neighbour", 0.9f)))
        // An added addon is active here too — the fallback must still pick the
        // neighbour graph because YouTube is on.
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { yt.resolveArtist("Neighbour") } returns ArtistSummary("NB_ID", "Neighbour", null)
        coEvery { yt.getArtist("NB_ID") } returns ArtistProfile(
            "NB_ID", "Neighbour", null, null,
            listOf(TrackSummary("nb1", "Neighbour Song", "Neighbour", null, 180.0, null)),
            emptyList(), emptyList(), emptyList(),
        )

        val (_, batch) = gen().start(RadioSeed.Song("Seed", "Seed Artist", ytVideoId = "vSeed000001"))

        assertThat(batch.map { it.title }).contains("Neighbour Song")
        assertThat(batch).isNotEmpty()
        coVerify(exactly = 1) { lastFm.getSimilarArtists("Seed Artist", any()) }
        // The addon-native artist fallback belongs to the YouTube-off path only.
        coVerify(exactly = 0) { addons.search(any(), "Seed Artist", 25) }
    }

    @Test fun `addon-only thin seed fills the pool from the artist search`() = runTest {
        val seedArtist = "Timmy Trumpet, POLTERGST, Naeleck"
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns Result.success(emptyList())
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns
            Result.success(listOf(LastFmTopTrack(seedArtist, "Top One", 100)))
        coEvery { prefs.isYoutubeEnabled() } returns false
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.search(addon, "Timmy Trumpet", 25) } returns listOf(
            EclipseAddonTrack(id = "a1", title = "Addon Artist Song", artist = "Timmy Trumpet", duration = 200),
            // Same item twice → deduped by id.
            EclipseAddonTrack(id = "a1", title = "Addon Artist Song", artist = "Timmy Trumpet", duration = 200),
            // A different artist sharing no token → filtered out.
            EclipseAddonTrack(id = "a2", title = "Other Song", artist = "Some Other Act", duration = 190),
        )
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } answers {
            if (secondArg<String>() == "Addon Artist Song") {
                EclipseAddonTrack(id = "a1", title = "Addon Artist Song", artist = "Timmy Trumpet", duration = 200)
            } else {
                null
            }
        }

        val (session, batch) = genWithPrefs().start(
            RadioSeed.Song("Seed Song", seedArtist, ytVideoId = "vSeed000001"),
        )

        coVerify(exactly = 1) { addons.search(addon, "Timmy Trumpet", 25) }
        // One candidate, artist-keyed to the seed, mid weight, no videoId.
        val fallback = session.ordered.single { it.title == "Addon Artist Song" }
        assertThat(fallback.artist).isEqualTo(seedArtist)
        assertThat(fallback.weight).isEqualTo(0.6f)
        assertThat(fallback.videoId).isNull()
        assertThat(session.ordered.none { it.title == "Other Song" }).isTrue()

        val track = batch.single { it.title == "Addon Artist Song" }
        assertThat(track.youtubeId).isNull()
        assertThat(track.id).isEqualTo(("addon:" + "a1").hashCode().toLong())
        coVerify(exactly = 0) { yt.searchCanonicalMatch(any(), any()) }
    }

    @Test fun `addon-only thin seed with no enabled addon stays empty`() = runTest {
        val seedArtist = "Timmy Trumpet, POLTERGST, Naeleck"
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns Result.success(emptyList())
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns
            Result.success(listOf(LastFmTopTrack(seedArtist, "Top One", 100)))
        coEvery { prefs.isYoutubeEnabled() } returns false
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { addons.enabledNow() } returns emptyList()

        val (_, batch) = genWithPrefs().start(RadioSeed.Song("Seed Song", seedArtist))

        // Nothing can serve a candidate: no addon, YouTube off → NoStation.
        assertThat(batch).isEmpty()
        coVerify(exactly = 0) { addons.search(any(), any(), any()) }
    }

    @Test fun `thin seed with YouTube disabled does not look up neighbours`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns Result.success(emptyList())
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns
            Result.success(listOf(LastFmTopTrack("Seed Artist", "Only Song", 100)))
        coEvery { prefs.isYoutubeEnabled() } returns false
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns
            EclipseAddonTrack(id = "tidal:only", title = "Only Song", artist = "Seed Artist", duration = 200)

        val (_, batch) = genWithPrefs().start(RadioSeed.Song("Seed", "Seed Artist", ytVideoId = "vSeed000001"))

        val addonTrack = batch.single { it.artist == "Seed Artist" && it.youtubeId == null }
        assertThat(addonTrack.id).isEqualTo(("addon:" + "tidal:only").hashCode().toLong())
        // The artist graph is YT-only; with YouTube off it must not be consulted.
        coVerify(exactly = 0) { lastFm.getSimilarArtists(any(), any()) }
        coVerify(exactly = 0) { yt.resolveArtist(any()) }
        coVerify(exactly = 0) { yt.getArtist(any()) }
    }

    @Test fun `a normal song seed does not pull neighbours`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("Other Artist", "Similar Song", 0.9f)))
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(
            listOf(
                LastFmTopTrack("Seed Artist", "Top One", 100),
                LastFmTopTrack("Seed Artist", "Top Two", 90),
            ),
        )
        coEvery { yt.searchCanonicalMatch(any(), any()) } returns
            CanonicalMatch(videoId = "vSome000001", thumbnailUrl = null)

        val (_, batch) = gen().start(RadioSeed.Song("Seed", "Seed Artist", ytVideoId = "vSeed000001"))

        assertThat(batch).isNotEmpty()
        coVerify(exactly = 0) { lastFm.getSimilarArtists(any(), any()) }
    }

    @Test fun `a multi-artist seed uses the primary artist when the full credit is empty`() = runTest {
        coEvery { lastFm.getSimilarTracks("BENNETT, Creeds", any(), any()) } returns
            Result.success(emptyList())
        coEvery { lastFm.getSimilarTracks("BENNETT", any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("Neighbour", "Similar Song", 0.9f)))
        coEvery { lastFm.getArtistTopTracks("BENNETT, Creeds", any()) } returns
            Result.success(emptyList())
        coEvery { lastFm.getArtistTopTracks("BENNETT", any()) } returns Result.success(
            listOf(
                LastFmTopTrack("Bennett", "Top One", 100),
                LastFmTopTrack("Bennett", "Top Two", 90),
            ),
        )

        val (session, _) = gen().start(RadioSeed.Song("Milky Way", "BENNETT, Creeds", ytVideoId = "vSeed000001"))

        assertThat(session.ordered).isNotEmpty()
        assertThat(session.ordered.map { it.title }).containsAtLeast("Top One", "Similar Song")
        coVerify(exactly = 1) { lastFm.getSimilarTracks("BENNETT", any(), any()) }
        coVerify(exactly = 1) { lastFm.getArtistTopTracks("BENNETT", any()) }
    }

    @Test fun `the full-artist variant wins when both return results`() = runTest {
        coEvery { lastFm.getSimilarTracks("Solo, Featured", any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("Full Artist Friend", "Full Song", 0.9f)))
        coEvery { lastFm.getSimilarTracks("Solo", any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("Primary Friend", "Primary Song", 0.9f)))
        coEvery { lastFm.getArtistTopTracks("Solo, Featured", any()) } returns Result.success(
            listOf(LastFmTopTrack("Solo", "Top One", 100), LastFmTopTrack("Solo", "Top Two", 90)),
        )
        coEvery { lastFm.getArtistTopTracks("Solo", any()) } returns
            Result.success(listOf(LastFmTopTrack("Solo", "Primary Top", 80)))

        val (session, _) = gen().start(RadioSeed.Song("Seed", "Solo, Featured", ytVideoId = "vSeed000001"))

        // Both variants were started (two calls each); the full-artist answer wins.
        assertThat(session.ordered.map { it.title }).contains("Full Song")
        assertThat(session.ordered.none { it.title == "Primary Song" }).isTrue()
        coVerify(exactly = 2) { lastFm.getSimilarTracks(any(), any(), any()) }
        coVerify(exactly = 2) { lastFm.getArtistTopTracks(any(), any()) }
    }

    @Test fun `a single-artist seed still makes one Lastfm call per leg`() = runTest {
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns
            Result.success(listOf(LastFmSimilarTrack("Friend", "Similar Song", 0.9f)))
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(
            listOf(LastFmTopTrack("Solo", "Top One", 100), LastFmTopTrack("Solo", "Top Two", 90)),
        )

        gen().start(RadioSeed.Song("Seed", "Solo", ytVideoId = "vSeed000001"))

        coVerify(exactly = 1) { lastFm.getSimilarTracks("Solo", any(), any()) }
        coVerify(exactly = 1) { lastFm.getArtistTopTracks("Solo", any()) }
    }

    @Test fun `addon search variants run concurrently and the first hit stops the rest`() = runTest {
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.resolve(any(), any(), any(), any(), any()) } returns null
        coEvery { addons.search(addon, "Milky Way BENNETT, Creeds", 10) } coAnswers {
            delay(1_000) // both variants' latency must overlap, not add up
            emptyList()
        }
        coEvery { addons.search(addon, "Milky Way BENNETT", 10) } coAnswers {
            delay(1_000)
            listOf(EclipseAddonTrack(id = "448663796", title = "Milky Way", artist = "BENNETT", duration = 172))
        }

        val session = RadioSession(
            RadioSeed.Song("Milky Way", "BENNETT, Creeds"),
            mutableListOf(RadioCandidate("BENNETT, Creeds", "Milky Way", null, 1f)),
            mutableSetOf(),
        )
        val batch = gen().drainBatch(session, 10)

        assertThat(batch.single().id).isEqualTo(("addon:" + "448663796").hashCode().toLong())
        // Two 1 s searches in parallel finish at ~1 s; serialized they would take 2 s.
        assertThat(testScheduler.currentTime).isAtMost(1_000L)
        coVerify(exactly = 2) { addons.search(addon, any(), 10) }
    }

    @Test fun `addon-only artist radio builds from addon artist search and keeps metadata`() = runTest {
        coEvery { prefs.isYoutubeEnabled() } returns false
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { lastFm.getSimilarArtists("Seed Artist", any()) } returns
            Result.success(listOf(LastFmSimilarArtist("Neighbour", 0.8f)))
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.search(addon, "Seed Artist", 25) } returns listOf(
            EclipseAddonTrack(
                id = "seed-1",
                title = "Seed Song",
                artist = "Seed Artist",
                duration = 201, // seconds per the addon protocol
                artworkUrl = "https://cdn.test/seed.jpg",
            ),
        )
        coEvery { addons.search(addon, "Neighbour", 25) } returns listOf(
            EclipseAddonTrack(
                id = "n-1",
                title = "Neighbour Song",
                artist = "Neighbour",
                duration = 180,
                artworkUrl = "https://cdn.test/neighbour.jpg",
            ),
        )

        val (_, batch) = genWithPrefs().start(RadioSeed.Artist("Seed Artist"))

        assertThat(batch).hasSize(2)
        val seed = batch.single { it.title == "Seed Song" }
        assertThat(seed.artist).isEqualTo("Seed Artist")
        assertThat(seed.durationMs).isEqualTo(201_000L)
        assertThat(seed.albumArtUrl).isEqualTo("https://cdn.test/seed.jpg")
        assertThat(seed.youtubeId).isNull()
        val neighbour = batch.single { it.title == "Neighbour Song" }
        assertThat(neighbour.artist).isEqualTo("Neighbour")
        assertThat(neighbour.durationMs).isEqualTo(180_000L)
        assertThat(neighbour.albumArtUrl).isEqualTo("https://cdn.test/neighbour.jpg")
        // The addon supplied the whole artist-radio pool: no YT Popular walk.
        coVerify(exactly = 0) { yt.resolveArtist(any()) }
        coVerify(exactly = 0) { yt.getArtist(any()) }
        coVerify(exactly = 0) { yt.searchCanonicalMatch(any(), any()) }
    }

    @Test fun `addon-only artist radio widens through the next Lastfm neighbour`() = runTest {
        coEvery { prefs.isYoutubeEnabled() } returns false
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { lastFm.getSimilarArtists("Seed Artist", any()) } returns Result.success(
            (1..13).map { LastFmSimilarArtist("N$it", 1f - it * 0.01f) },
        )
        coEvery { addons.search(addon, any(), any()) } answers {
            val query = secondArg<String>()
            val title = if (query == "Seed Artist") "Seed Song" else "Song $query"
            listOf(
                EclipseAddonTrack(
                    id = "id-$query",
                    title = title,
                    artist = query,
                    duration = 180,
                    artworkUrl = "https://cdn.test/$query.jpg",
                ),
            )
        }

        val generator = genWithPrefs()
        val (session, first) = generator.start(RadioSeed.Artist("Seed Artist"))
        val second = generator.nextBatch(session)

        // The initial pool held N1..N12; N13 can only come from widen's addon leg.
        assertThat((first + second).map { it.title }).contains("Song N13")
        assertThat(session.exhausted).isFalse()
        coVerify(exactly = 0) { yt.resolveArtist(any()) }
        coVerify(exactly = 0) { yt.getArtist(any()) }
    }

    @Test fun `artist search tries every enabled addon in order`() = runTest {
        coEvery { prefs.isYoutubeEnabled() } returns false
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { lastFm.getSimilarArtists("Seed Artist", any()) } returns Result.success(emptyList())
        coEvery { addons.enabledNow() } returns listOf(addon, secondAddon)
        coEvery { addons.search(addon, any(), any()) } returns emptyList()
        coEvery { addons.search(secondAddon, "Seed Artist", 25) } returns listOf(
            EclipseAddonTrack(id = "second:1", title = "Second Song", artist = "Seed Artist", duration = 200),
        )

        val (_, batch) = genWithPrefs().start(RadioSeed.Artist("Seed Artist"))

        assertThat(batch.single().id).isEqualTo(("addon:" + "second:1").hashCode().toLong())
        coVerify(exactly = 1) { addons.search(addon, "Seed Artist", 25) }
        coVerify(exactly = 1) { addons.search(secondAddon, "Seed Artist", 25) }
    }

    @Test fun `a Lastfm graph failure falls back to addon artist search`() = runTest {
        coEvery { prefs.isYoutubeEnabled() } returns false
        coEvery { prefs.isEclipseAddonsEnabled() } returns true
        coEvery { lastFm.getSimilarArtists(any(), any()) } throws RuntimeException("no Last.fm key")
        coEvery { addons.enabledNow() } returns listOf(addon)
        coEvery { addons.search(addon, "Seed Artist", 25) } returns listOf(
            EclipseAddonTrack(
                id = "seed-1",
                title = "Seed Song",
                artist = "Seed Artist",
                duration = 200,
                artworkUrl = "https://cdn.test/seed.jpg",
            ),
        )

        val (_, batch) = genWithPrefs().start(RadioSeed.Artist("Seed Artist"))

        assertThat(batch.single().title).isEqualTo("Seed Song")
        assertThat(batch.single().youtubeId).isNull()
    }

    @Test fun `resolve falls through to the next enabled addon`() = runTest {
        coEvery { addons.enabledNow() } returns listOf(addon, secondAddon)
        coEvery { addons.resolve(addon, any(), any(), any(), any()) } returns null
        coEvery { addons.search(addon, any(), any()) } returns emptyList()
        coEvery { addons.resolve(secondAddon, any(), any(), any(), any()) } returns EclipseAddonTrack(
            id = "second:1",
            title = "Fallback Song",
            artist = "Fallback Artist",
            duration = 210,
        )

        val session = RadioSession(
            RadioSeed.Song("Fallback Song", "Fallback Artist"),
            mutableListOf(RadioCandidate("Fallback Artist", "Fallback Song", null, 1f)),
            mutableSetOf(),
        )
        val batch = gen().drainBatch(session, 10)

        assertThat(batch.single().id).isEqualTo(("addon:" + "second:1").hashCode().toLong())
        coVerify(exactly = 1) { addons.resolve(addon, "Fallback Song", "Fallback Artist", null, null) }
        coVerify(exactly = 1) { addons.resolve(secondAddon, "Fallback Song", "Fallback Artist", null, null) }
    }
}
