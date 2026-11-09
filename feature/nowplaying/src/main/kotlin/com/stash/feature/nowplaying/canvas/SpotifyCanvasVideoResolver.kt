package com.stash.feature.nowplaying.canvas

import com.stash.core.model.Track
import com.stash.data.spotify.SpotifyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The production Canvas resolver: the Spotify data layer's own [SpotifyApiClient.canvasUrl], behind
 * a tiny interface Now Playing can fake in tests. The client already caches positive and negative
 * answers in memory for a bounded time and fails closed on anything ambiguous; [CanvasNegativeCache]
 * adds a short-lived disk memory for misses so reopening the player on the same song asks again only
 * after a while.
 */
@Singleton
class SpotifyCanvasVideoResolver @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val negativeCache: CanvasNegativeCache,
) : CanvasVideoResolver {

    override suspend fun canvasUrl(track: Track): String? = withContext(Dispatchers.IO) {
        if (negativeCache.isNegative(track)) return@withContext null
        val url = spotify.canvasUrl(track)
        if (url == null) negativeCache.markNegative(track)
        url
    }
}
