package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HlsPlaylistParserTest {
    private val base = "https://cdn.test/path/master.m3u8?exp=1&sig=abc"

    @Test
    fun `a finite media playlist gives init then segments in order`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg-1.m4s
            #EXTINF:4.0,
            https://cdn.test/path/seg-2.m4s?exp=1
            #EXT-X-ENDLIST
        """.trimIndent()

        val plan = HlsPlaylistParser.parse(text, base)!!

        assertThat(plan.initUrl).isEqualTo("https://cdn.test/path/init.mp4")
        assertThat(plan.segmentUrls).containsExactly(
            "https://cdn.test/path/seg-1.m4s",
            "https://cdn.test/path/seg-2.m4s?exp=1",
        ).inOrder()
    }

    @Test
    fun `encrypted, live and master playlists are unsupported`() {
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:1,\na.m4s\n#EXT-X-ENDLIST"
        val live = "#EXTM3U\n#EXTINF:1,\na.m4s"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvariant.m3u8"
        val empty = "#EXTM3U\n#EXT-X-ENDLIST"

        assertThat(HlsPlaylistParser.parse(encrypted, base)).isNull()
        assertThat(HlsPlaylistParser.parse(live, base)).isNull()
        assertThat(HlsPlaylistParser.parse(master, base)).isNull()
        assertThat(HlsPlaylistParser.parse(empty, base)).isNull()
    }

    @Test
    fun `METHOD=NONE is not encryption`() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:1,\na.m4s\n#EXT-X-ENDLIST"
        assertThat(HlsPlaylistParser.parse(text, base)?.segmentUrls).hasSize(1)
    }
}
