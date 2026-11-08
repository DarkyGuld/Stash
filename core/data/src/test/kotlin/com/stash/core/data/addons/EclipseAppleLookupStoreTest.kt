package com.stash.core.data.addons

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
class EclipseAppleLookupStoreTest {
    private lateinit var file: File
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "apple_lookup_TEST_${System.nanoTime()}.preferences_pb")
    }

    @After
    fun tearDown() {
        if (file.exists()) file.delete()
    }

    private fun store(maxEntries: Int = 5_000) = EclipseAppleLookupStore(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file },
        ),
        clock = { now },
        maxEntries = maxEntries,
    )

    @Test
    fun `positive and negative entries round-trip`() = runTest {
        val store = store()
        store.put("eclipse-music-cloud", "isrc:fake1", "555000001")
        store.put("eclipse-music-cloud", "meta:mario", null)

        assertThat(store.get("eclipse-music-cloud", "isrc:fake1")?.appleId).isEqualTo("555000001")
        val negative = store.get("eclipse-music-cloud", "meta:mario")
        assertThat(negative).isNotNull()
        assertThat(negative!!.appleId).isNull()
        assertThat(store.get("eclipse-music-cloud", "unknown")).isNull()
    }

    @Test
    fun `a positive id never expires and a stored negative lives an hour`() = runTest {
        val store = store()
        store.put("eclipse-music-cloud", "hit", "1")
        store.put("eclipse-music-cloud", "miss", null)

        // Positives are stable: a decade is still a hit.
        now += 3650L * 24 * 60 * 60_000L
        assertThat(store.get("eclipse-music-cloud", "hit")?.appleId).isEqualTo("1")

        now = 1_000_000_000_000L
        store.put("eclipse-music-cloud", "miss2", null)
        now += 60L * 60_000L - 1
        assertThat(store.get("eclipse-music-cloud", "miss2")).isNotNull()
        now += 2
        assertThat(store.get("eclipse-music-cloud", "miss2")).isNull()
    }

    @Test
    fun `the cache is bounded, dropping the oldest entries`() = runTest {
        val store = store(maxEntries = 3)
        repeat(4) { index ->
            now += 1
            store.put("cloud", "track-$index", index.toString())
        }

        assertThat(store.get("cloud", "track-0")).isNull()
        assertThat(store.get("cloud", "track-3")?.appleId).isEqualTo("3")
    }
}
