package com.stash.core.data.addons

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackgroundCatalogPrefetchWorkerTest {

    @Test
    fun `requests use connected network, battery guard and a four-hour period`() {
        val onStop = BackgroundCatalogPrefetchWorker.onStopRequest()
        val periodic = BackgroundCatalogPrefetchWorker.periodicRequest()
        for (constraints in listOf(onStop.workSpec.constraints, periodic.workSpec.constraints)) {
            assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
            assertThat(constraints.requiresBatteryNotLow()).isTrue()
        }
        assertThat(periodic.workSpec.intervalDuration).isEqualTo(TimeUnit.HOURS.toMillis(4))
        assertThat(BackgroundCatalogPrefetchWorker.ON_STOP_WORK_NAME).isNotEqualTo(
            BackgroundCatalogPrefetchWorker.PERIODIC_WORK_NAME,
        )
    }

    @Test
    fun `worker sends one deduplicated batch and no stream or fallback call`() = runTest {
        val dao = mockk<TrackDao>()
        val repository = mockk<EclipseAddonRepository>()
        val addon = EclipseAddon(
            id = "nullbeat-cache",
            name = "NullBeat",
            version = "2",
            manifestUrl = "https://addon.test/u/session/manifest.json",
            resources = listOf("resolve", "prefetch"),
            settings = mapOf("quality" to "lossless"),
        )
        val first = mockk<TrackEntity> { every { isrc } returns "NOAAA1010101" }
        val duplicate = mockk<TrackEntity> { every { isrc } returns "noaaa1010101" }
        coEvery { dao.catalogPrefetchCandidates(100) } returns listOf(first, duplicate)
        coEvery { repository.enabledNow() } returns listOf(addon)
        coEvery { repository.prefetchCatalog(any(), any()) } returns 1

        val worker = BackgroundCatalogPrefetchWorker(mockk<Context>(), mockk<WorkerParameters>(), dao, repository)
        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())

        coVerify(exactly = 1) {
            repository.prefetchCatalog(addon, match { tracks -> tracks.size == 1 })
        }
        coVerify(exactly = 0) { repository.stream(any(), any()) }
    }

    @Test
    fun `ordinary addons without the batch resource are left alone`() = runTest {
        val dao = mockk<TrackDao>()
        val repository = mockk<EclipseAddonRepository>()
        val track = mockk<TrackEntity> { every { isrc } returns "NOAAA1010101" }
        coEvery { dao.catalogPrefetchCandidates(100) } returns listOf(track)
        coEvery { repository.enabledNow() } returns emptyList()

        val worker = BackgroundCatalogPrefetchWorker(mockk<Context>(), mockk<WorkerParameters>(), dao, repository)
        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { repository.prefetchCatalog(any(), any()) }
    }
}
