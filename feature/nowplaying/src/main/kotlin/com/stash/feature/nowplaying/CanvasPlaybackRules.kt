package com.stash.feature.nowplaying

import com.stash.core.model.Track

/**
 * The one place that decides when Spotify Canvas may be looked up and shown.
 *
 * A Canvas is a cosmetic extra: every missing condition (no setting, no song, paused, buffering,
 * a metered network, Battery Saver) quietly keeps Now Playing's ordinary cover and full art. The
 * rules are pure so they can be pinned in JVM tests without a player or a screen.
 */
internal object CanvasPlaybackRules {

    /**
     * Whether the current song's Canvas may be looked up now. Only the current song is ever asked
     * for ([NowPlayingViewModel] drives this), and only while it is actually playing, on an
     * unmetered network and with Battery Saver off.
     */
    fun canResolve(
        enabled: Boolean?,
        hasTrack: Boolean,
        playing: Boolean,
        buffering: Boolean,
        unmetered: Boolean,
        batterySaverOn: Boolean,
    ): Boolean = enabled == true &&
        hasTrack &&
        playing &&
        !buffering &&
        unmetered &&
        !batterySaverOn

    /**
     * Whether the Canvas belongs on screen now: the setting is on, this song has a resolved URL,
     * Now Playing is settled in front of the listener, nothing else owns the background
     * (casting, lyrics, full art), and the network/power gates still allow it.
     */
    fun shouldShow(
        enabled: Boolean?,
        url: String?,
        trackMatches: Boolean,
        inSight: Boolean,
        casting: Boolean,
        lyrics: Boolean,
        fullArt: Boolean,
        unmetered: Boolean,
        batterySaverOn: Boolean,
    ): Boolean = enabled == true &&
        !url.isNullOrBlank() &&
        trackMatches &&
        inSight &&
        !casting &&
        !lyrics &&
        !fullArt &&
        unmetered &&
        !batterySaverOn

    /** The video follows the music: playing, not stalled in a buffer. Paused, it holds its last frame. */
    fun videoShouldPlay(isPlaying: Boolean, playWhenReady: Boolean, buffering: Boolean): Boolean =
        isPlaying && playWhenReady && !buffering
}

/**
 * A stable identity for the playing song's Canvas. The player can reconstruct a slim track whose id
 * is enough on its own, but the extra fields keep a transient stream id from looking like another
 * song's id.
 */
internal fun canvasTrackKey(track: Track?): String? = track?.let {
    "${it.id}:${it.youtubeId.orEmpty()}:${it.spotifyUri.orEmpty()}"
}
