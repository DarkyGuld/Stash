package com.stash.core.media.streaming

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HlsManifestDataSourceTest {
    private val original = "https://cf-media.sndcdn.com/old"
    private val fresh = "https://cf-media.sndcdn.com/new"

    @Test fun `expired manifest refreshes once with its new URI`() {
        val opens = mutableListOf<String>()
        val inner = mockk<DataSource>(relaxed = true)
        every { inner.open(any()) } answers {
            val spec = firstArg<DataSpec>()
            opens += spec.uri.toString()
            if (opens.size == 1) throw forbidden(spec)
            10L
        }
        var invalidatedManifest: Boolean? = null
        val source = HlsManifestDataSource(inner, original, AtomicReference(original), { true }, { fresh },
            { _, manifest -> invalidatedManifest = manifest })
        assertEquals(10L, source.open(DataSpec(Uri.parse(original))))
        assertEquals(listOf(original, fresh), opens)
        assertEquals(true, invalidatedManifest)
    }

    @Test fun `the refreshed manifest URI can itself be refreshed`() {
        val inner = mockk<DataSource>(relaxed = true)
        var opens = 0
        every { inner.open(any()) } answers {
            opens++
            if (opens == 1) throw forbidden(firstArg())
            10L
        }
        every { inner.uri } returns null
        val source = HlsManifestDataSource(inner, original, AtomicReference(fresh), { true }, { "$fresh-2" }, { _, _ -> })
        assertEquals(10L, source.open(DataSpec(Uri.parse(fresh))))
        assertEquals(2, opens)
    }

    @Test fun `segment error invalidates only that segment and never refreshes the manifest`() {
        val inner = mockk<DataSource>(relaxed = true)
        every { inner.open(any()) } answers { throw forbidden(firstArg()) }
        var refreshed = false
        var invalidated: DataSpec? = null
        var invalidatedManifest: Boolean? = null
        val source = HlsManifestDataSource(inner, original, AtomicReference(original), { true },
            { refreshed = true; fresh },
            { spec, manifest -> invalidated = spec; invalidatedManifest = manifest })
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            source.open(DataSpec(Uri.parse("https://cf-media.sndcdn.com/segment-1")))
        }
        // Only the failing segment is handed to the invalidator, not the track.
        assertEquals("https://cf-media.sndcdn.com/segment-1", invalidated?.uri?.toString())
        assertEquals(false, invalidatedManifest)
        assertFalse(refreshed)
    }

    @Test fun `Download mode prevents network opens and refreshes`() {
        val inner = mockk<DataSource>()
        val source = HlsManifestDataSource(inner, original, AtomicReference(original), { false },
            { fail("network refresh in Download mode"); null }, { _, _ -> })
        assertThrows(IOException::class.java) { source.open(DataSpec(Uri.parse(original))) }
    }

    @Test fun `mode change after a failed open prevents credential refresh`() {
        var allowed = true
        val inner = mockk<DataSource>(relaxed = true)
        every { inner.open(any()) } answers { allowed = false; throw forbidden(firstArg()) }
        val source = HlsManifestDataSource(inner, original, AtomicReference(original), { allowed },
            { fail("network refresh after mode change"); null }, { _, _ -> })
        assertThrows(IOException::class.java) { source.open(DataSpec(Uri.parse(original))) }
    }

    private fun forbidden(spec: DataSpec) = HttpDataSource.InvalidResponseCodeException(
        403, "Forbidden", null, emptyMap(), spec, ByteArray(0),
    )
}
