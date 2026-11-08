package com.stash.core.media.service

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrossfadeEngineHardCutTest {
    private val audioManager: AudioManager = mockk(relaxed = true) {
        every { requestAudioFocus(any()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private val context = mockk<Context> {
        every { getSystemService(Context.AUDIO_SERVICE) } returns audioManager
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun master(queue: List<MediaItem>, current: Int) = mockk<ExoPlayer>(relaxed = true) {
        every { mediaItemCount } returns queue.size
        every { getMediaItemAt(any()) } answers { queue[firstArg()] }
        every { currentMediaItemIndex } returns current
        every { nextMediaItemIndex } returns current + 1
    }

    private fun spare(incoming: MediaItem, ready: Boolean = true) = mockk<ExoPlayer>(relaxed = true) {
        var count = 1
        every { mediaItemCount } answers { count }
        every { currentMediaItem } returns incoming
        every { playbackState } returns if (ready) Player.STATE_READY else Player.STATE_BUFFERING
        every { isPlaying } returns true
        every { addMediaItems(any(), any()) } answers { count += secondArg<List<MediaItem>>().size }
        every { addMediaItems(any<List<MediaItem>>()) } answers { count += firstArg<List<MediaItem>>().size }
    }

    private fun engine(scope: CoroutineScope, master: ExoPlayer, spare: ExoPlayer): CrossfadeEngine {
        val players = ArrayDeque(listOf(master, spare))
        return CrossfadeEngine(context, { players.removeFirst() }, scope).also { it.initialize() }
    }

    @Test
    fun `a ready spare primed for the next item becomes the master on a hard cut`() = runTest {
        val queue = listOf(item("a"), item("b"), item("c"))
        val out = master(queue, current = 0)
        val inc = spare(queue[1])
        var swapped: ExoPlayer? = null
        val engine = engine(backgroundScope, out, inc)

        val ok = engine.performHardCut { swapped = it }

        assertThat(ok).isTrue()
        assertThat(swapped).isSameInstanceAs(inc)
        assertThat(engine.masterPlayer).isSameInstanceAs(inc)
        verify(exactly = 1) { inc.play() }
        verify { out.stop() }
    }

    @Test
    fun `a spare that is not READY is not used`() = runTest {
        val queue = listOf(item("a"), item("b"))
        val out = master(queue, current = 0)
        val inc = spare(queue[1], ready = false)
        var swapped: ExoPlayer? = null
        val engine = engine(backgroundScope, out, inc)

        assertThat(engine.performHardCut { swapped = it }).isFalse()
        assertThat(swapped).isNull()
        assertThat(engine.masterPlayer).isSameInstanceAs(out)
        verify(exactly = 0) { inc.play() }
    }

    @Test
    fun `a spare primed for a different item is never consumed`() = runTest {
        val queue = listOf(item("a"), item("b"), item("c"))
        val out = master(queue, current = 0)
        val inc = spare(queue[2]) // stale: the next item is b
        var swapped: ExoPlayer? = null
        val engine = engine(backgroundScope, out, inc)

        assertThat(engine.performHardCut { swapped = it }).isFalse()
        assertThat(swapped).isNull()
        assertThat(engine.masterPlayer).isSameInstanceAs(out)
    }

    @Test
    fun `primeNext prepares the spare paused and silent, without crossfade`() = runTest {
        val queue = listOf(item("a"), item("b"))
        val out = master(queue, current = 0)
        val inc = spare(queue[1])
        val engine = engine(backgroundScope, out, inc)

        engine.prepareNext(queue[1])

        assertThat(engine.isPreparedFor("b")).isTrue()
        verify { inc.playWhenReady = false }
        verify { inc.volume = 0f }
        verify { inc.prepare() }
        verify { inc.seekTo(0) }
    }
}
