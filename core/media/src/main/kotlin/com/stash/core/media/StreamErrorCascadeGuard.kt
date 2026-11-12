package com.stash.core.media

/**
 * Decides how [PlayerRepositoryImpl] reacts to a streamed-track playback error.
 *
 * Two behaviours in one small state machine:
 *
 *  1. **Retry the same track first.** A streamed track that hits a transient
 *     error — a mobile-network stall, a dropped CDN connection, an expired URL —
 *     used to skip straight to the next track on the FIRST error. That is the
 *     "songs randomly cut off and skip" cluster (#420/401/334/266/209): one blip
 *     on a cellular connection = a lost track. The first error for a given item
 *     now returns [Verdict.RetrySameItem] so the player re-prepares it in place
 *     (position preserved); only if it keeps failing do we fall through to skip.
 *     Bounded to ONE retry per item so a genuinely-dead URL cannot loop.
 *
 *  2. **Bound the skip cascade.** Once retries are exhausted, skips accumulate;
 *     after [threshold] of them with no successful playback or user transport in
 *     between, return [Verdict.Halt] instead of silently draining the queue.
 *
 * Not thread-safe by itself — caller must invoke from the single MediaController
 * callback thread (which is what we already do).
 */
class StreamErrorCascadeGuard(
    private val threshold: Int = 3,
    /** Same-item re-prepares granted before the first skip. Two gives a slow
     *  source a real chance to recover without turning a dead URL into a loop. */
    private val maxRetriesPerItem: Int = 2,
) {
    init {
        require(threshold >= 1) { "threshold must be >= 1, got $threshold" }
        require(maxRetriesPerItem >= 1) { "maxRetriesPerItem must be >= 1, got $maxRetriesPerItem" }
    }

    private var consecutiveErrors: Int = 0

    /**
     * The item that has already spent its one same-track retry. Kept across
     * [onPlaybackStarted] on purpose: a track that reaches READY and then errors
     * again immediately must NOT re-retry (that is the infinite-retry trap), only
     * a deliberate [onUserTransport] rearms it.
     */
    private var retryItemKey: String? = null
    private var retriesForItem: Int = 0

    /** Local-file skips since anything last played; see [onLocalSkip]. */
    private var consecutiveLocalSkips: Int = 0

    sealed class Verdict {
        /** Re-prepare the CURRENT item in place before considering a skip. */
        data object RetrySameItem : Verdict()
        /** Skip to the next item. */
        data object Recover : Verdict()
        /** Stop skipping and halt — the backend is cascading. */
        data class Halt(val consecutiveErrors: Int) : Verdict()
    }

    /**
     * @param itemKey a stable identity for the failing item (its mediaId). The
     *   first error for a not-yet-retried item retries it; `null` (no stable id)
     *   skips retry and counts immediately, preserving the pre-retry behaviour.
     */
    fun onError(itemKey: String?): Verdict {
        if (itemKey != null) {
            if (itemKey != retryItemKey) {
                retryItemKey = itemKey
                retriesForItem = 0
            }
            if (retriesForItem < maxRetriesPerItem) {
                retriesForItem += 1
                return Verdict.RetrySameItem
            }
        }
        consecutiveErrors += 1
        return if (consecutiveErrors >= threshold) Verdict.Halt(consecutiveErrors) else Verdict.Recover
    }

    /**
     * A local file failed and is about to be skipped (offline, so no stream
     * fallback). Returns true when that skip should NOT happen: [queueSize]
     * skips in a row with nothing playing in between means every item in the
     * queue has failed. Local failures stay out of the streaming cascade (one
     * bad download is not a backend outage), but uncounted, an unmounted SD
     * card or revoked folder access with repeat-all on cycled error, skip,
     * prepare several times a second forever: hasNextMediaItem() is always
     * true there, and playWhenReady never dropped, so the idle stop never
     * armed either.
     */
    fun onLocalSkip(queueSize: Int): Boolean {
        consecutiveLocalSkips += 1
        return consecutiveLocalSkips >= queueSize.coerceAtLeast(1)
    }

    /** A track actually started playing — backend is alive; reset the skip count. */
    fun onPlaybackStarted() {
        consecutiveErrors = 0
        consecutiveLocalSkips = 0
        // A track that reached playback has had its retry budget. If it errors
        // immediately afterwards, skip rather than re-entering the retry loop.
        retriesForItem = maxRetriesPerItem
    }

    /** User did something deliberate (next/prev/seek/play) — rearm everything. */
    fun onUserTransport() {
        consecutiveErrors = 0
        consecutiveLocalSkips = 0
        retryItemKey = null
        retriesForItem = 0
    }
}
