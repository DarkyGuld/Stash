package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EclipseAddonCacheKeyFactoryTest {
    private fun url(
        signed: String,
        codec: String = "flac",
        origin: String = "addon:Cloud",
    ) = StreamUrl(
        url = "https://streaming-qobuz-std.akamaized.net/file?$signed",
        expiresAtMs = Long.MAX_VALUE,
        codec = codec,
        bitsPerSample = 24,
        sampleRateHz = 44_100,
        origin = origin,
    )

    @Test
    fun `the signed query is ignored, so a fresh signature reuses the cached file`() {
        val a = url("uid=1&etsp=1762000000&sig=aaa")
        val b = url("uid=1&etsp=1790000000&sig=bbb&hmac=ccc")

        assertThat(addonCacheKey(42, a, a.url))
            .isEqualTo(addonCacheKey(42, b, b.url))
    }

    @Test
    fun `an HLS manifest and its segments have distinct stable keys`() {
        val manifestA = StreamUrl(
            "https://cdn.test/path/master.m3u8?exp=1&sig=aaa",
            Long.MAX_VALUE, codec = "flac", origin = "addon:Cloud", isHls = true,
        )
        val manifestB = manifestA.copy(
            url = "https://cdn.test/path/master.m3u8?exp=999&sig=bbb",
        )
        val segment = manifestA.copy(
            url = "https://cdn.test/path/seg-1.m4s?exp=1&sig=aaa",
        )

        assertThat(addonCacheKey(9, manifestA, manifestA.url))
            .isEqualTo(addonCacheKey(9, manifestB, manifestB.url))
        assertThat(addonCacheKey(9, manifestA, manifestA.url))
            .isNotEqualTo(addonCacheKey(9, segment, segment.url))
    }

    @Test
    fun `track, addon and quality separate entries`() {
        val base = url("uid=1")

        assertThat(addonCacheKey(1, base, base.url))
            .isNotEqualTo(addonCacheKey(2, base, base.url))
        assertThat(addonCacheKey(1, base, base.url))
            .isNotEqualTo(addonCacheKey(1, base.copy(codec = "mp3"), base.url))
        assertThat(addonCacheKey(1, base, base.url))
            .isNotEqualTo(addonCacheKey(1, base.copy(origin = "addon:Other"), base.url))
    }
}
