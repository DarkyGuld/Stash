package com.stash.feature.nowplaying

import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackQualityTextTest {

    private fun track(
        format: String,
        origin: String? = null,
        bits: Int? = null,
        rate: Int? = null,
        kbps: Int = 0,
        filePath: String? = null,
        downloaded: Boolean = false,
    ) = Track(
        id = 1L, title = "t", artist = "a", fileFormat = format, bitsPerSample = bits,
        sampleRateHz = rate, qualityKbps = kbps, streamOrigin = origin, filePath = filePath,
        isDownloaded = downloaded,
    )

    /** The quality line never names Qobuz or Tidal: the signal-path sheet does. */
    @Test fun `a Qobuz stream names no service`() {
        assertEquals(
            "FLAC · 24-bit/96.0 kHz",
            trackQualityText(track("flac", origin = "qbdlx", bits = 24, rate = 96_000)),
        )
    }

    @Test fun `a Tidal stream names no service`() {
        assertEquals("AAC · 320 kbps", trackQualityText(track("aac", origin = "tidal", kbps = 320)))
        assertEquals(
            "FLAC · 16-bit/44.1 kHz",
            trackQualityText(track("flac", origin = "tidal", bits = 16, rate = 44_100)),
        )
    }

    @Test fun `lossy fallbacks keep the old suffixes`() {
        assertEquals("AAC · via JioSaavn", trackQualityText(track("aac", origin = "jiosaavn")))
        assertEquals("OPUS · 160 kbps · via YT", trackQualityText(track("opus", origin = "youtube", kbps = 160)))
    }

    /**
     * A stream that hasn't resolved yet still carries the DB default
     * fileFormat ("opus"). The line must stay empty instead of flashing
     * OPUS and then disappearing when the real format lands.
     */
    @Test fun `an unresolved stream renders no line`() {
        assertEquals(null, trackQualityText(track("opus")))
        assertEquals(null, trackQualityText(track("opus", kbps = 160)))
    }

    @Test fun `a downloaded file without a path still shows its format`() {
        assertEquals("FLAC", trackQualityText(track("flac", downloaded = true)))
    }

    @Test fun `an addon stream names the addon that served it`() {
        assertEquals(
            "FLAC · 24-bit/96.0 kHz · via Nebula",
            trackQualityText(
                track("flac", origin = "addon:Nebula", bits = 24, rate = 96_000),
            ),
        )
        // A nameless stamp still says it came from the addon rung.
        assertEquals("FLAC · via Addon", trackQualityText(track("flac", origin = "addon:")))
    }

    @Test fun `a downloaded file names no service`() {
        assertEquals(
            "FLAC · 16-bit/44.1 kHz",
            trackQualityText(track("flac", bits = 16, rate = 44_100, filePath = "/music/a.flac")),
        )
    }

    @Test fun `a downloaded file's extension beats a stale format`() {
        assertEquals("FLAC · 900 kbps", trackQualityText(track("opus", kbps = 900, filePath = "/music/a.flac")))
    }

    @Test fun `a blank format with no file renders no line`() {
        assertEquals(null, trackQualityText(track("")))
    }
}
