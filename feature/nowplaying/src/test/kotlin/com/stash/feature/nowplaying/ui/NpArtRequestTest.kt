package com.stash.feature.nowplaying.ui

import android.content.Context
import com.stash.core.ui.util.ArtSizeInterceptor
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The big cover's decode size and request (see [rememberNpArtDecodePx], [npArtRequest]), and the
 * neighbours' prefetch plan ([neighbourPrefetchModels]). The URL grammar itself is tested in
 * `ArtUrlSizerTest`; the interceptor's flag behaviour in `ArtSizeInterceptorTest`.
 */
class NpArtRequestTest {

    private val context: Context = mockk(relaxed = true)

    // ── Decode size: the measured cover width, bucketed and capped ────────────────────────────

    @Test
    fun `the decode is the cover width bucketed, not the screen short side`() {
        // Pixel 5: 393dp short side (1080px at 2.75), 24dp margins -> a 948px cover -> 1024 (was 1080).
        assertEquals(1024, npArtDecodePx(screenWidthDp = 393, screenHeightDp = 851, density = 2.75f))
        // Pixel 6 Pro: 411dp short side (1439px at 3.5), 24dp margins -> a ~1271px cover -> 1280 (was 1440).
        assertEquals(1280, npArtDecodePx(screenWidthDp = 411, screenHeightDp = 891, density = 3.5f))
        // A tablet's 1600px short side, less the margins, is capped.
        assertEquals(1280, npArtDecodePx(screenWidthDp = 800, screenHeightDp = 1280, density = 2f))
    }

    @Test
    fun `the decode is stable across rotation and within the cap`() {
        for ((w, h, d) in listOf(
            Triple(393, 851, 2.75f),
            Triple(411, 891, 3.5f),
            Triple(800, 1280, 2f),
        )) {
            assertEquals(
                "rotation kept the key",
                npArtDecodePx(w, h, d),
                npArtDecodePx(h, w, d),
            )
            assertTrue(npArtDecodePx(w, h, d) in 1..1280)
        }
    }

    @Test
    fun `a wider side padding decodes smaller`() {
        val none = npArtDecodePx(393, 851, 2.75f, sidePaddingDp = 0f)
        val padded = npArtDecodePx(393, 851, 2.75f, sidePaddingDp = 24f)
        assertTrue("$none vs $padded", none >= padded)
    }

    @Test
    fun `a neighbour's decode is capped below the front cover's`() {
        assertEquals(640, neighbourArtDecodePx(1280))
        assertEquals(640, neighbourArtDecodePx(640))
        assertEquals(480, neighbourArtDecodePx(480))
        assertTrue(neighbourArtDecodePx(1280) <= NEIGHBOUR_ART_MAX_DECODE_PX)
    }

    // ── Neighbour prefetch: only after the front, only unmetered, next first ──────────────────

    @Test
    fun `neighbours are prefetched next first, only once the front has landed and unmetered`() {
        assertEquals(
            listOf<Any>("next", "previous"),
            neighbourPrefetchModels(frontReady = true, metered = false, next = "next", previous = "previous"),
        )
        assertEquals(
            listOf<Any>("next"),
            neighbourPrefetchModels(frontReady = true, metered = false, next = "next", previous = null),
        )
        // The front is still loading: nothing may compete with it.
        assertEquals(
            emptyList<Any>(),
            neighbourPrefetchModels(frontReady = false, metered = false, next = "next", previous = "previous"),
        )
        // Metered: the front's bytes and the user's data plan come first.
        assertEquals(
            emptyList<Any>(),
            neighbourPrefetchModels(frontReady = true, metered = true, next = "next", previous = "previous"),
        )
    }

    // ── The request: the placeholder key the mini player's bitmap is cached under, and the flag ─

    @Test
    fun `the cover request keeps the stored-url placeholder key and opts into NP sizing`() {
        val url = "https://static.qobuz.com/images/covers/25/40/0060254794025_600.jpg"
        val request = npArtRequest(context, url, decodePx = 1024)

        assertEquals(url, request.data)
        // The mini player's bitmap is cached under the stored URL; this is the same key.
        assertEquals(url, request.placeholderMemoryCacheKey?.key)
        assertTrue(request.extras[ArtSizeInterceptor.NowPlayingArt] == true)
    }
}
