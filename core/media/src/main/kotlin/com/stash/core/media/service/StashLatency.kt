package com.stash.core.media.service

import android.os.SystemClock
import android.util.Log

/**
 * One pending "tap → first audio" measurement, for logcat only.
 *
 * Marked when a track is about to be prepared/played (queue set, media-item
 * transition, explicit play), logged once when the player first reports audio
 * for that track. Pauses, resumes and seeks never start a new measurement; a
 * single pending record is kept, so a skip simply replaces it.
 */
internal class StashLatency(
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private class Pending(
        val trackId: Long,
        val title: String,
        val origin: String?,
        val startedMs: Long,
        var urlAtMs: Long? = null,
        var urlSource: String? = null,
        var readyAtMs: Long? = null,
    )

    private var pending: Pending? = null

    /** Starts measuring [trackId]; a non-positive id can't be measured. */
    @Synchronized
    fun start(trackId: Long, title: String?, origin: String?) {
        if (trackId <= 0) return
        pending = Pending(trackId, title.orEmpty(), origin, clockMs())
    }

    /**
     * Logs the first audio for [trackId] (or the pending track when null) and
     * clears the measurement, so the next ready/isPlaying event does nothing.
     * A different track is ignored: its own start event will follow.
     */
    @Synchronized
    fun firstAudio(trackId: Long?) {
        val current = pending ?: return
        if (trackId != null && trackId > 0 && trackId != current.trackId) return
        pending = null
        val elapsed = (clockMs() - current.startedMs).coerceAtLeast(0)
        val origin = current.origin?.takeIf { it.isNotBlank() }?.let { " origin=$it" } ?: ""
        val url = current.urlAtMs?.let { (it - current.startedMs).coerceAtLeast(0) }
        val ready = current.readyAtMs?.let { (it - current.startedMs).coerceAtLeast(0) }
        log(
            "track ${current.trackId} '${current.title}'$origin first audio in $elapsed ms" +
                " (url=${url ?: "?"} ms from ${current.urlSource ?: "?"}, ready=${ready ?: "?"} ms)",
        )
    }

    /** The (resolved) URL is in hand: memory/disk/local/network, with its origin. */
    @Synchronized
    fun urlAvailable(trackId: Long?, origin: String?, source: String) {
        val current = pending ?: return
        if (trackId != null && trackId > 0 && trackId != current.trackId) return
        current.urlAtMs = clockMs()
        current.urlSource = source
        val elapsed = (clockMs() - current.startedMs).coerceAtLeast(0)
        log("track ${current.trackId} url available from $source in $elapsed ms")
    }

    /** The media source reached STATE_READY. */
    @Synchronized
    fun ready(trackId: Long?) {
        val current = pending ?: return
        if (trackId != null && trackId > 0 && trackId != current.trackId) return
        current.readyAtMs = clockMs()
        val elapsed = (clockMs() - current.startedMs).coerceAtLeast(0)
        log("track ${current.trackId} STATE_READY in $elapsed ms")
    }

    /** Forgets a measurement (e.g. the play request failed). */
    @Synchronized
    fun clear() {
        pending = null
    }

    private companion object {
        const val TAG = "StashLatency"
    }
}
