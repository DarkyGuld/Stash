package com.stash.feature.nowplaying

import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Canvas gates: the experimental setting must never cause a look-up when it is off, when
 * the song is paused or buffering, on a metered network or under Battery Saver, and the video must
 * never cover the lyrics or a cast receiver, or stay on after the song changes.
 */
class CanvasPlaybackRulesTest {

    private fun canResolve(
        enabled: Boolean? = true,
        hasTrack: Boolean = true,
        playing: Boolean = true,
        buffering: Boolean = false,
        unmetered: Boolean = true,
        batterySaverOn: Boolean = false,
    ) = CanvasPlaybackRules.canResolve(enabled, hasTrack, playing, buffering, unmetered, batterySaverOn)

    @Test fun `the setting is off by default and nothing is looked up`() {
        assertFalse(canResolve(enabled = false))
        assertFalse(canResolve(enabled = null))
    }

    @Test fun `only a playing, unbuffered song on Wi-Fi without Battery Saver is looked up`() {
        assertTrue(canResolve())
        assertFalse(canResolve(playing = false))
        assertFalse(canResolve(buffering = true))
        assertFalse(canResolve(unmetered = false))
        assertFalse(canResolve(batterySaverOn = true))
        assertFalse(canResolve(hasTrack = false))
    }

    private fun shouldShow(
        enabled: Boolean? = true,
        url: String? = "https://i.scdn.co/canvas.mp4",
        trackMatches: Boolean = true,
        inSight: Boolean = true,
        casting: Boolean = false,
        lyrics: Boolean = false,
        fullArt: Boolean = false,
        unmetered: Boolean = true,
        batterySaverOn: Boolean = false,
    ) = CanvasPlaybackRules.shouldShow(
        enabled, url, trackMatches, inSight, casting, lyrics, fullArt, unmetered, batterySaverOn,
    )

    @Test fun `a Canvas shows full screen while Now Playing owns the background`() {
        assertTrue(shouldShow())
    }

    @Test fun `every fallback keeps the normal cover`() {
        assertFalse(shouldShow(enabled = false))
        assertFalse(shouldShow(enabled = null))
        assertFalse(shouldShow(url = null))
        assertFalse(shouldShow(url = ""))
        assertFalse(shouldShow(trackMatches = false))
        assertFalse(shouldShow(inSight = false))
        assertFalse(shouldShow(casting = true))
        assertFalse(shouldShow(lyrics = true))
        assertFalse(shouldShow(fullArt = true))
        assertFalse(shouldShow(unmetered = false))
        assertFalse(shouldShow(batterySaverOn = true))
    }

    @Test fun `the video follows the music and holds still in a buffer`() {
        assertTrue(CanvasPlaybackRules.videoShouldPlay(isPlaying = true, playWhenReady = true, buffering = false))
        assertFalse(CanvasPlaybackRules.videoShouldPlay(isPlaying = false, playWhenReady = true, buffering = false))
        assertFalse(CanvasPlaybackRules.videoShouldPlay(isPlaying = true, playWhenReady = false, buffering = false))
        assertFalse(CanvasPlaybackRules.videoShouldPlay(isPlaying = true, playWhenReady = true, buffering = true))
    }

    @Test fun `the track key moves with a transient stream id`() {
        val first = Track(id = -1L, title = "t", artist = "a", youtubeId = "abc")
        val same = first.copy()
        val other = first.copy(id = -2L)
        assertEquals(canvasTrackKey(first), canvasTrackKey(same))
        assertNotEquals(canvasTrackKey(first), canvasTrackKey(other))
        assertEquals(null, canvasTrackKey(null))
    }
}
