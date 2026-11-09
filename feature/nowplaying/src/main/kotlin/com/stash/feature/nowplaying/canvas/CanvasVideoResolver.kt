package com.stash.feature.nowplaying.canvas

import com.stash.core.model.Track

/**
 * Asks the Spotify data layer for a song's Canvas video URL.
 *
 * It is deliberately a tiny seam: production binds [SpotifyCanvasVideoResolver] (which calls
 * `SpotifyApiClient.canvasUrl`), while Now Playing's tests and hand-built screens use [None] and
 * therefore never touch the network.
 */
interface CanvasVideoResolver {

    /** The song's Canvas MP4 URL, or null when it has no Canvas (or none can be resolved). */
    suspend fun canvasUrl(track: Track): String?

    /** No Canvas at all: the default for tests and previews. */
    object None : CanvasVideoResolver {
        override suspend fun canvasUrl(track: Track): String? = null
    }
}
