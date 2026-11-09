package com.stash.feature.nowplaying

import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.CrossPlatformLikeResolver
import com.stash.core.model.Track
import com.stash.data.spotify.SpotifyApiClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The answer to "build Spotify radio for this song": a finite, id-bearing mix, no Spotify match
 * for the seed, or nothing Spotify can offer. A separate type from a bare list so an empty answer
 * from a real mix is never confused with a song that cannot be matched at all.
 */
sealed interface SpotifyRadioMix {
    /** The mix, already persisted with real ids and without the seed song. */
    data class Ready(val tracks: List<Track>) : SpotifyRadioMix

    /** The current song has no safe Spotify identity, so there is nothing to seed the mix with. */
    data object NoSpotifyMatch : SpotifyRadioMix

    /** Spotify has no mix (or the lookup failed). The current queue is left alone. */
    data object Unavailable : SpotifyRadioMix
}

/**
 * Builds Spotify's song radio: the current track as the seed, then a finite "Inspired by" mix.
 * Deliberately not the radio station: no station build, no auto-grow, no exit snapshot.
 *
 * The production source resolves the seed's Spotify URI when missing, asks the data layer for the
 * mix and persists every mix track so the queue has real ids. The ViewModel only prepends the
 * current song and calls `setQueue`.
 */
interface SpotifyRadioSource {

    /**
     * The ready-to-queue mix for [current], at most [limit] songs. Never throws on a network or
     * persistence failure: an empty or failed answer is [SpotifyRadioMix.Unavailable], leaving the
     * caller's queue untouched. Tests and previews use [None], which never touches the network.
     */
    suspend fun mix(current: Track, limit: Int = DEFAULT_LIMIT): SpotifyRadioMix

    object None : SpotifyRadioSource {
        override suspend fun mix(current: Track, limit: Int): SpotifyRadioMix = SpotifyRadioMix.Unavailable
    }

    companion object {
        /** How deep a song radio goes: enough for a listen, small enough to build quickly. */
        const val DEFAULT_LIMIT = 30
    }
}

/**
 * The production source over the Spotify data layer. The seed's Spotify URI is resolved and
 * persisted through [CrossPlatformLikeResolver] when the playing row is missing one; the mix comes
 * from [SpotifyApiClient.inspiredByTracks] (metadata-only, id 0) and every entry is persisted via
 * [MusicRepository.ensureTrackPersisted] so no queue item shares the id-0 media identity.
 *
 * A transient Spotify failure is not cached anywhere here: the next tap simply asks again.
 */
@Singleton
class SpotifyRadioSourceImpl @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val identities: CrossPlatformLikeResolver,
    private val music: MusicRepository,
) : SpotifyRadioSource {

    override suspend fun mix(current: Track, limit: Int): SpotifyRadioMix = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext SpotifyRadioMix.Unavailable
        val seed = try {
            identities.ensureSpotifyUri(current)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed lookup is not the same as a song Spotify has no match for: say unavailable,
            // not "needs a match", and let the next tap ask again.
            return@withContext SpotifyRadioMix.Unavailable
        } ?: return@withContext SpotifyRadioMix.NoSpotifyMatch

        val raw = try {
            spotify.inspiredByTracks(seed, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (raw.isEmpty()) return@withContext SpotifyRadioMix.Unavailable

        val mix = ArrayList<Track>(raw.size)
        for (candidate in raw) {
            if (mix.size >= limit) break
            // The seed is already left out by the data layer, but a variant of the same recording
            // can come back on another URI: keep the playing song out of its own Up Next.
            if (sameSong(candidate, current)) continue
            val id = try {
                music.ensureTrackPersisted(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue // best effort: one unpersistable mix row doesn't sink the radio
            }
            if (current.id > 0L && id == current.id) continue
            if (mix.any { it.id == id }) continue // the same row under two Spotify variants
            mix += candidate.copy(id = id)
        }
        if (mix.isEmpty()) SpotifyRadioMix.Unavailable else SpotifyRadioMix.Ready(mix)
    }

    /**
     * True when [candidate] is the playing song under another identity: the same persisted row,
     * Spotify URI, YouTube id or ISRC, or the same words (a re-recording that survived the data
     * layer's own seed filter).
     */
    private fun sameSong(candidate: Track, current: Track): Boolean {
        if (candidate.id > 0L && candidate.id == current.id) return true
        candidate.spotifyUri?.takeIf { it.isNotBlank() }?.let { if (it == current.spotifyUri) return true }
        candidate.youtubeId?.takeIf { it.isNotBlank() }?.let { if (it == current.youtubeId) return true }
        candidate.isrc?.takeIf { it.isNotBlank() }?.let { if (it == current.isrc) return true }
        if (candidate.title.isBlank() || candidate.artist.isBlank()) return false
        return wordsKey(candidate.title, candidate.artist) == wordsKey(current.title, current.artist)
    }

    private fun wordsKey(title: String, artist: String): String =
        "${title.lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()}|" +
            artist.lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()

    private companion object {
        val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    }
}

/** Binds the production Spotify-radio source, like the Canvas seams' module. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SpotifyRadioModule {

    @Binds
    abstract fun bindSpotifyRadioSource(impl: SpotifyRadioSourceImpl): SpotifyRadioSource
}
