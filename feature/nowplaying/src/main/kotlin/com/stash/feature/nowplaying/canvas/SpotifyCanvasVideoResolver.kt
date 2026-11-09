package com.stash.feature.nowplaying.canvas

import com.stash.core.model.Track
import com.stash.data.spotify.CanvasLookup
import com.stash.data.spotify.SpotifyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The production Canvas resolver: the Spotify data layer's own [SpotifyApiClient.canvasLookup], behind
 * a tiny interface Now Playing can fake in tests. The client already caches positive and negative
 * answers in memory for a bounded time; [CanvasNegativeCache] adds a short-lived disk memory for
 * confirmed misses so reopening the player on the same song asks again only after a while.
 *
 * A transient Spotify/network failure is deliberately NOT written to the disk cache: it is unknown
 * whether the song has a Canvas, and a false ten-minute miss is worse than one retry.
 */
@Singleton
class SpotifyCanvasVideoResolver @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val negativeCache: CanvasNegativeCache,
) : CanvasVideoResolver {

    override suspend fun canvasUrl(track: Track): String? = withContext(Dispatchers.IO) {
        if (negativeCache.isNegative(track)) return@withContext null
        when (val lookup = spotify.canvasLookup(track)) {
            is CanvasLookup.Found -> lookup.url
            CanvasLookup.NoCanvas -> {
                negativeCache.markNegative(track)
                null
            }
            CanvasLookup.TransientFailure -> null
        }
    }
}
