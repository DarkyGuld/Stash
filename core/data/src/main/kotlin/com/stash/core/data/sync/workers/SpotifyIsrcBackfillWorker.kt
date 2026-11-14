package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.addons.SpotifyIsrcGate
import com.stash.core.data.db.dao.TrackDao
import com.stash.data.spotify.SpotifyApiClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * One-shot, resumable ISRC backfill for Spotify-backed library rows that were
 * imported before the parser kept `external_ids.isrc`. Unmetered only, 2 req/s,
 * [BATCH_LIMIT] rows per run; if more remain it re-enqueues itself. Any lookup
 * failure just leaves that row null for the next run (no playback impact).
 */
@HiltWorker
class SpotifyIsrcBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val spotify: SpotifyApiClient,
    private val gate: SpotifyIsrcGate,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!gate.backfillAllowed()) {
            Log.i(TAG, "ISRC backfill paused (playback/foreground/backoff); rescheduling")
            runCatching { enqueue(applicationContext, delayMs = RESCHEDULE_MS) }
            return Result.success()
        }
        val rows = runCatching {
            // The songs in a playlist first: those are the ones the user plays.
            val inPlaylists = trackDao.tracksMissingIsrcInPlaylists(BATCH_LIMIT)
            if (inPlaylists.isNotEmpty()) inPlaylists else trackDao.tracksMissingIsrc(BATCH_LIMIT)
        }.getOrElse {
            Log.w(TAG, "ISRC backfill query failed", it)
            return Result.retry()
        }
        if (rows.isEmpty()) {
            Log.i(TAG, "ISRC backfill: nothing to do")
            return Result.success()
        }
        Log.i(TAG, "ISRC backfill: ${rows.size} rows, ${(rows.size + SPOTIFY_BATCH - 1) / SPOTIFY_BATCH} batches")
        var updated = 0
        for (chunk in rows.chunked(SPOTIFY_BATCH)) {
            // Yield immediately if audio starts or a per-play lookup takes the lane.
            if (!gate.backfillAllowed()) {
                Log.i(TAG, "ISRC backfill yielding to foreground; rescheduling")
                runCatching { enqueue(applicationContext, delayMs = RESCHEDULE_MS) }
                return Result.success()
            }
            val idRows = chunk.mapNotNull { row -> spotifyId(row.spotifyUri)?.let { it to row } }
            if (idRows.isEmpty()) continue
            when (val batch = spotify.getTracksIsrc(idRows.map { it.first })) {
                is SpotifyApiClient.IsrcBatch.Success -> {
                    for ((id, row) in idRows) {
                        val isrc = batch.isrcById[id] ?: continue
                        runCatching { trackDao.backfillIsrcIfMissing(row.id, isrc) }.onSuccess { updated++ }
                    }
                }
                is SpotifyApiClient.IsrcBatch.RateLimited -> {
                    // One log per run; stop immediately and come back after the window.
                    gate.setBackoff(batch.retryAfterSeconds)
                    Log.w(TAG, "Spotify 429; stopping the ISRC backfill for ${batch.retryAfterSeconds}s")
                    runCatching { enqueue(applicationContext, delayMs = batch.retryAfterSeconds * 1_000L) }
                    return Result.success()
                }
                SpotifyApiClient.IsrcBatch.Failed -> {
                    Log.w(TAG, "ISRC batch failed; stopping this run")
                    return Result.retry()
                }
            }
            delay(STEP_MS)
        }
        Log.i(TAG, "ISRC backfill complete: updated $updated/${rows.size}")
        if (rows.size >= BATCH_LIMIT) runCatching { enqueue(applicationContext, delayMs = RESCHEDULE_MS) }
        return Result.success()
    }

    private fun spotifyId(uri: String?): String? {
        val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw.substringAfterLast("spotify:track:", raw)
            .substringAfterLast("open.spotify.com/track/")
            .substringBefore('?')
            .takeIf { it.isNotEmpty() && it != raw }
            ?: raw.takeIf { !it.contains(':') && !it.contains('/') }
    }

    companion object {
        private const val TAG = "SpotifyIsrcBackfill"
        /** Rows per run. 500 rows is 10 Spotify calls. */
        private const val BATCH_LIMIT = 500
        private const val SPOTIFY_BATCH = 50
        private const val STEP_MS = 500L
        /** How soon the next run starts. The queue prefetch covers the songs ahead. */
        private const val RESCHEDULE_MS = 5 * 60_000L
        private const val WORK = "spotify-isrc-backfill"

        /** Unmetered + charging + idle, delayed so startup/first play is never competing. */
        fun enqueue(context: Context, delayMs: Long = FIRST_RUN_DELAY_MS) {
            val request = OneTimeWorkRequestBuilder<SpotifyIsrcBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        private const val FIRST_RUN_DELAY_MS = 3 * 60_000L
    }
}
