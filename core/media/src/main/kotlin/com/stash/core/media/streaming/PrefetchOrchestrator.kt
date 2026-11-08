package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether the *next* track in the queue should have its stream
 * URL resolved and cached ahead of auto-advance, and (if so) fires the
 * resolve in [scope] without blocking the caller.
 *
 * Extracted out of [com.stash.core.media.service.StashPlaybackService] so
 * the logic is unit-testable without booting a [androidx.media3.session
 * .MediaSessionService] / [androidx.media3.exoplayer.ExoPlayer]. The
 * service holds a single [PrefetchOrchestrator] and calls
 * [onPlaybackProgress] from a periodic position-poll while playback is
 * active.
 *
 * Trigger conditions (all must hold):
 *  1. `positionMs / durationMs > 0.60` — the user is past 60 % and an
 *     auto-advance is imminent.
 *  2. A next track exists in the queue.
 *  3. The next track is **streamable** and **not already downloaded** —
 *     local files have nothing to resolve; non-streamable rows will fail
 *     the resolve and waste a Kennyy roundtrip. (Streaming is always
 *     allowed; the Download switch only decides what sync writes to disk,
 *     so it is not consulted here.)
 *  4. The next track's URL is not already in [StreamUrlCache] within
 *     TTL — duplicate resolves are wasteful.
 *  5. We have not already attempted a prefetch for this `nextTrackId` in
 *     the current play session — idempotency guard so a failing resolve
 *     doesn't spam Kennyy as the position-poll keeps firing.
 *  6. Data saver does not apply (the switch off, or an unmetered network).
 *
 * The "attempted" set is cleared by [resetSession] which the service is
 * expected to invoke on every `onMediaItemTransition` — once playback
 * moves to the next track, that track id may legitimately need a re-
 * attempt if its predecessor is repeated (REPEAT_ONE) or skipped back to.
 *
 * Failures are logged at WARN and swallowed — the user finds out about
 * an unresolvable next track when auto-advance hits [StreamRoutingResult
 * .NotAvailable] in the routing decision tree, not from a prefetch
 * snackbar.
 */
@Singleton
class PrefetchOrchestrator @Inject constructor(
    private val streamResolver: StreamSourceRegistry,
    private val streamUrlCache: StreamUrlCache,
    private val trackDao: TrackDao,
    /** Data saver ([StreamQualityPolicy.dataSaverApplies]); null in hand-built tests (never applies). */
    private val qualityPolicy: StreamQualityPolicy? = null,
    /**
     * Addon state for the mid-flight guard: null in hand-built tests (no guard,
     * as no addon change can race them). Hilt injects the real repository.
     */
    private val eclipseAddons: com.stash.core.data.addons.EclipseAddonRepository? = null,
    /** First-chunk file warm into the shared cache; null in hand-built tests. */
    private val firstChunk: EclipseFirstChunkPrefetcher? = null,
) {
    /**
     * Track ids for which a prefetch resolve has been attempted in the
     * current play session. Cleared by [resetSession]. ConcurrentHashMap-
     * backed for safety against the periodic-poll scheduling races.
     */
    private val attempted: MutableSet<Long> = Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap()
    )

    /** The in-flight "URL for the next tracks" warm; a queue change cancels it. */
    private var urlPrefetchJob: Job? = null

    /**
     * Warms the resolved stream URL for the next [trackIds] — as soon as they are
     * known, not at the 60 % auto-advance threshold. Sequential (concurrency 1),
     * and replaced on every call so a queue change cancels stale work. Black
     * cloud addons answer this by persisting the URL too (the resolver's disk
     * layer), so a real play hits memory.
     */
    fun prefetchUrlsAhead(scope: CoroutineScope, trackIds: List<Long>) {
        urlPrefetchJob?.cancel()
        if (trackIds.isEmpty()) return
        urlPrefetchJob = scope.launch {
            // Data saver: no look-ahead streams on a metered network.
            if (qualityPolicy?.dataSaverApplies() == true) return@launch
            val generationBefore = eclipseAddons?.addonGeneration?.value
            for (id in trackIds.distinct()) {
                if (streamUrlCache.get(id) != null) continue
                val track = trackDao.getById(id) ?: continue
                if (track.skipForUrlPrefetch()) continue
                val resolved = streamResolver.resolve(track, speculative = true) ?: continue
                // An addon was toggled/removed while resolving: leave the cache alone.
                if (generationBefore != null && eclipseAddons?.addonGeneration?.value != generationBefore) {
                    return@launch
                }
                streamUrlCache.put(id, resolved)
                // Progressive addon file (Cloud's direct Qobuz FLAC): warm its first
                // ~2 MB into the same cache the player will read. HLS/DASH skip this.
                if (resolved.origin?.startsWith("${EclipseAddonStreamResolver.ORIGIN}") == true) {
                    firstChunk?.warm(id, resolved)
                }
                Log.d(TAG, "Prefetched stream URL for upcoming track $id")
            }
        }
    }

    /** The same rows the next-up prefetch skips: a file, a cloud song, or a known-unplayable row. */
    private fun com.stash.core.data.db.entity.TrackEntity.skipForUrlPrefetch(): Boolean =
        isDownloaded ||
            source == com.stash.core.model.MusicSource.CLOUD ||
            (!isStreamable && isStreamableCheckedAt != null)

    /**
     * Called by the host (service) from its periodic position poll. All
     * arguments are read off the player on the main thread; the actual
     * DAO + resolver work runs inside [scope].
     *
     * @param scope coroutine scope tied to the host's lifecycle — the
     *  service's `serviceScope` in production. Cancelling it cancels
     *  any in-flight prefetch.
     * @param nextTrackId mediaId of the next queue item parsed to Long,
     *  or null if no next item or unparseable.
     * @param positionMs current track playback position.
     * @param durationMs current track total duration; treated as
     *  "unknown" (no prefetch) when ≤ 0.
     */
    fun onPlaybackProgress(
        scope: CoroutineScope,
        nextTrackId: Long?,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (nextTrackId == null) return
        if (durationMs <= 0L) return
        val progress = positionMs.toDouble() / durationMs.toDouble()
        if (progress <= PREFETCH_THRESHOLD) return
        if (!attempted.add(nextTrackId)) return // already attempted this session

        scope.launch {
            try {
                // Data saver on mobile data: no look-ahead. The song is looked up when the player
                // reaches it (its own buffering asks, ahead of the transition); a skip costs nothing.
                // Asked first: the policy re-takes its decision before the cache below is read.
                if (qualityPolicy?.dataSaverApplies() == true) return@launch
                if (streamUrlCache.get(nextTrackId) != null) return@launch

                val track = trackDao.getById(nextTrackId) ?: return@launch
                if (track.isDownloaded) return@launch
                // Cloud songs resolve their own link when opened; never the online chain.
                if (track.source == com.stash.core.model.MusicSource.CLOUD) return@launch
                // Skip only rows CONFIRMED unstreamable (checked and false).
                // Synced-library rows sit at isStreamable=false with
                // isStreamableCheckedAt=null ("never checked" — the
                // AvailabilityCheckWorker that set the flag is gone);
                // gating on the bare flag silently disabled next-track
                // prefetch for the whole synced library.
                if (!track.isStreamable && track.isStreamableCheckedAt != null) return@launch

                // speculative: never makes the shared Tidal relay search for a look-ahead.
                val generationBefore = eclipseAddons?.addonGeneration?.value
                val startedNs = System.nanoTime()
                val resolved = streamResolver.resolve(track, speculative = true)
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                if (resolved != null) {
                    // An addon toggled/removed/settings-changed while this resolve was
                    // running: the URL may belong to a source the user just turned off.
                    // The ViewModel clears StreamUrlCache on those actions; a write here
                    // after that clear would undo it, so drop it.
                    if (generationBefore != null && eclipseAddons?.addonGeneration?.value != generationBefore) {
                        Log.d(TAG, "Prefetch for track $nextTrackId discarded: addons changed during resolve ($elapsedMs ms)")
                    } else {
                        streamUrlCache.put(nextTrackId, resolved)
                        Log.d(TAG, "Prefetched stream URL for track $nextTrackId in $elapsedMs ms")
                    }
                } else {
                    Log.w(TAG, "Prefetch resolve returned null for track $nextTrackId in $elapsedMs ms")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed for track $nextTrackId", e)
            }
        }
    }

    /**
     * Clears the per-session "already attempted" guard. Hosts should
     * call this on every track transition so a fresh prefetch attempt
     * is allowed for the new "next" track.
     */
    fun resetSession() {
        attempted.clear()
        urlPrefetchJob?.cancel()
    }

    private companion object {
        const val TAG = "StashPrefetch"

        /**
         * Pre-fetch fires once playback crosses this fraction of the
         * current track's duration. 60 % leaves ~40 % of runway to
         * absorb a slow Kennyy resolve before the user hits auto-
         * advance.
         */
        const val PREFETCH_THRESHOLD = 0.60
    }
}
