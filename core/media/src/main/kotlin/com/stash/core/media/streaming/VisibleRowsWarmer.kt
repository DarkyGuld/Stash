package com.stash.core.media.streaming

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * List-screen warmup, debounced: when the visible rows have been settled for
 * ~500 ms, warm the top few that already have DB rows (Apple id, URL, first
 * chunk) through the playback prefetch path. A new row set, scroll, or
 * navigation replaces the pending warmup.
 */
@Singleton
class VisibleRowsWarmer @Inject constructor(
    private val orchestrator: PrefetchOrchestrator,
) {
    private var job: Job? = null

    fun onVisibleRowsChanged(scope: CoroutineScope, trackIds: List<Long>, debounceMs: Long = DEBOUNCE_MS) {
        job?.cancel()
        val ids = trackIds.filter { it > 0 }.distinct().take(MAX_ROWS)
        if (ids.isEmpty()) return
        job = scope.launch {
            delay(debounceMs)
            orchestrator.warmSearchResults(scope, ids)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
        const val MAX_ROWS = 3
    }
}
