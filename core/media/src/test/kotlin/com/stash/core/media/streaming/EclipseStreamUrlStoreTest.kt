package com.stash.core.media.streaming

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EclipseStreamUrlStoreTest {
    private lateinit var file: File
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "eclipse_urls_TEST_${System.nanoTime()}.preferences_pb")
    }

    @After
    fun tearDown() {
        if (file.exists()) file.delete()
    }

    private fun store(maxEntries: Int = 2_000) = EclipseStreamUrlStore(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file },
        ),
        clock = { now },
        maxEntries = maxEntries,
    )

    private fun url(expiresAtMs: Long = now + 38 * 60_000L, origin: String = "addon:Cloud") = StreamUrl(
        url = "https://cdn.test/file.flac?etsp=${expiresAtMs / 1000}",
        expiresAtMs = expiresAtMs,
        codec = "flac",
        bitsPerSample = 24,
        sampleRateHz = 44_100,
        bitrateKbps = null,
        origin = origin,
        isHls = false,
        isDash = false,
    )

    @Test
    fun `a resolved URL round-trips with every field`() = runTest {
        val store = store()
        val original = url()
        store.put("eclipse-music-cloud", "settings", "isrc:fake", original)

        val loaded = store.get("eclipse-music-cloud", "settings", "isrc:fake")!!

        assertThat(loaded.url).isEqualTo(original.url)
        assertThat(loaded.expiresAtMs).isEqualTo(original.expiresAtMs)
        assertThat(loaded.codec).isEqualTo("flac")
        assertThat(loaded.bitsPerSample).isEqualTo(24)
        assertThat(loaded.sampleRateHz).isEqualTo(44_100)
        assertThat(loaded.origin).isEqualTo("addon:Cloud")
        assertThat(loaded.isHls).isFalse()
        assertThat(loaded.isDash).isFalse()
    }

    @Test
    fun `an expired URL is ignored`() = runTest {
        val store = store()
        store.put("cloud", "s", "id", url(expiresAtMs = now + 60_000))
        now += 60_001
        assertThat(store.get("cloud", "s", "id")).isNull()
    }

    @Test
    fun `a different settings snapshot is a different entry`() = runTest {
        val store = store()
        store.put("cloud", "quality=LOSSLESS", "id", url())
        assertThat(store.get("cloud", "quality=HI_RES_MAX", "id")).isNull()
    }

    @Test
    fun `the store is LRU-bounded`() = runTest {
        val store = store(maxEntries = 3)
        repeat(4) { index ->
            now += 1
            store.put("cloud", "s", "track-$index", url(expiresAtMs = now + 60_000))
        }
        assertThat(store.get("cloud", "s", "track-0")).isNull()
        assertThat(store.get("cloud", "s", "track-3")).isNotNull()
    }

    @Test
    fun `remove and clear drop entries`() = runTest {
        val store = store()
        store.put("cloud", "s", "id", url())
        store.remove("cloud", "s", "id")
        assertThat(store.get("cloud", "s", "id")).isNull()

        store.put("cloud", "s", "id2", url())
        store.clear()
        assertThat(store.get("cloud", "s", "id2")).isNull()
    }
}
