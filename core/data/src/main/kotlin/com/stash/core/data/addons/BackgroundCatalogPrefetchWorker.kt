package com.stash.core.data.addons

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Warms the installed Eclipse catalogues with bounded ISRC batches. Playback
 * never takes part in this worker: no stream URL is requested or cached here,
 * so preparing/miss cannot become a YouTube fallback for a later queue item.
 * The server remains the single authority for cold-job concurrency.
 */
@HiltWorker
class BackgroundCatalogPrefetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val repository: EclipseAddonRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val candidates = trackDao.catalogPrefetchCandidates(MAX_TRACKS_PER_RUN)
            .distinctBy { it.isrc?.trim()?.uppercase() }
        if (candidates.isEmpty()) return Result.success()
        val addons = repository.enabledNow().filter { it.supportsCatalogPrefetch }
        if (addons.isEmpty()) return Result.success()

        var retry = false
        for (batch in candidates.chunked(BATCH_SIZE)) {
            for (addon in addons) {
                currentCoroutineContext().ensureActive()
                try {
                    repository.prefetchCatalog(addon, batch)
                } catch (cancelled: CancellationException) {
                    // A cancelled run writes nothing and must never be treated
                    // as a new fallback decision for any track in the batch.
                    throw cancelled
                } catch (error: Exception) {
                    retry = true
                    Log.w(TAG, "catalog batch failed: ${error::class.java.simpleName}")
                }
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val ON_STOP_WORK_NAME = "eclipse_catalog_prefetch_on_stop"
        const val PERIODIC_WORK_NAME = "eclipse_catalog_prefetch_periodic"
        const val BATCH_SIZE = EclipseAddonClient.MAX_CATALOG_PREFETCH_BATCH
        const val MAX_TRACKS_PER_RUN = 100
        internal const val PERIOD_HOURS = 4L
        private const val BACKOFF_SECONDS = 60L
        private const val TAG = "EclipseCatalog"

        internal val CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        internal fun onStopRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BackgroundCatalogPrefetchWorker>()
                .setConstraints(CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

        internal fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<BackgroundCatalogPrefetchWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

        /** Installed once; repeated lifecycle stops never duplicate this job. */
        fun scheduleOnStop(workManager: WorkManager) {
            runCatching {
                workManager.enqueueUniqueWork(ON_STOP_WORK_NAME, ExistingWorkPolicy.KEEP, onStopRequest())
            }.onFailure { Log.w(TAG, "could not schedule catalog prefetch", it) }
        }

        /** Every four hours, on a connected device whose battery is not low. */
        fun schedulePeriodic(workManager: WorkManager) {
            runCatching {
                workManager.enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest(),
                )
            }.onFailure { Log.w(TAG, "could not schedule periodic catalog prefetch", it) }
        }
    }
}
