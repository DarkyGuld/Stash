package com.stash.core.data.addons

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.data.spotify.SpotifyApiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ISRCs for the songs in the queue, fetched in one go.
 *
 * An addon that is asked to resolve a track by ISRC matches the recording
 * itself. Without one it has to match on title, artist and duration - and a
 * remix, an edit or a live take can slip through, or the song can be sent to
 * another source entirely.
 *
 * The phone's Spotify session has its own quota, so this is one call for up
 * to [BATCH] songs, never one call per song. The per-play lookup keeps the
 * foreground lane; this one keeps to itself and steps aside when the account
 * is in a backoff.
 *
 * Nothing is downloaded here: an ISRC is a dozen bytes of text.
 */
@Singleton
class SpotifyIsrcQueuePrefetcher @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val trackDao: TrackDao,
    private val gate: SpotifyIsrcGate,
) {
    /** One lane, so two queue changes cannot spend two calls at once. */
    private val lock = Mutex()

    /** The last call, so a burst of queue changes does not spend the quota. */
    @Volatile
    private var lastCallAtMs = 0L

    /**
     * Fetches the ISRCs for [tracks] and writes them into the track rows.
     *
     * Best effort: every failure is just "no ISRC", and the play itself
     * looks the song up as it always did.
     *
     * @param tracks the queue's songs, in play order
     */
    suspend fun prefetch(tracks: List<com.stash.core.model.Track>) {
        if (tracks.isEmpty()) return

        // The account is in a backoff: every Spotify call would be refused.
        if (System.currentTimeMillis() < gate.backoffUntilMs()) return

        val wanted = tracks
            .filter { it.isrc.isNullOrBlank() && it.spotifyUri?.isNotBlank() == true }
            .take(BATCH)
        if (wanted.isEmpty()) return

        val ids = wanted.mapNotNull { spotifyId(it.spotifyUri) }
        if (ids.isEmpty()) return

        lock.withLock {
            // Two quick queue changes: the second one waits for the first.
            val since = System.currentTimeMillis() - lastCallAtMs
            if (since < MIN_INTERVAL_MS) {
                delay(MIN_INTERVAL_MS - since)
            }
            lastCallAtMs = System.currentTimeMillis()

            when (val batch = spotify.getTracksIsrc(ids)) {
                is SpotifyApiClient.IsrcBatch.Success -> {
                    var written = 0
                    for (track in wanted) {
                        val id = spotifyId(track.spotifyUri) ?: continue
                        val isrc = batch.isrcById[id] ?: continue
                        runCatching { trackDao.backfillIsrcIfMissing(track.id, isrc) }
                            .onSuccess { written++ }
                    }
                    Log.i(TAG, "ISRC for the queue: ${batch.isrcById.size} of ${ids.size}, $written written")
                }
                is SpotifyApiClient.IsrcBatch.RateLimited -> {
                    gate.setBackoff(batch.retryAfterSeconds)
                    Log.w(TAG, "Spotify 429 during the queue prefetch; backoff for ${batch.retryAfterSeconds}s")
                }
                SpotifyApiClient.IsrcBatch.Failed -> {
                    Log.w(TAG, "the queue prefetch failed; the play looks the song up itself")
                }
            }
        }
    }

    private fun spotifyId(uri: String?): String? {
        val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw.substringAfterLast("spotify:track:", raw)
            .substringAfterLast("open.spotify.com/track/")
            .substringBefore('?')
            .takeIf { it.isNotEmpty() && it != raw }
    }

    companion object {
        private const val TAG = "SpotifyIsrcQueue"

        /** Spotify's own batch size for /v1/tracks. */
        private const val BATCH = 50

        /** The shortest gap between two calls. */
        private const val MIN_INTERVAL_MS = 15_000L
    }
}
