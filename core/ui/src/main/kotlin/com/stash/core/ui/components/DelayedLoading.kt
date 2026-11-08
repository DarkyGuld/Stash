package com.stash.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A loading indicator that only appears after [delayMs]: a fast load (an instant
 * cached track) never flashes a spinner. False immediately when [active] drops,
 * and a new load starts a fresh window.
 */
@Composable
fun rememberDelayedLoading(active: Boolean, delayMs: Long = 1_000L): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active, delayMs) {
        if (!active) {
            shown = false
            return@LaunchedEffect
        }
        delay(delayMs)
        shown = true
    }
    return shown && active
}

/** Testable core of [rememberDelayedLoading]. */
class DelayedLoadingSignal(private val delayMs: Long = 1_000L) {
    private var job: Job? = null

    fun update(scope: CoroutineScope, active: Boolean, onChange: (Boolean) -> Unit) {
        job?.cancel()
        job = null
        if (!active) {
            onChange(false)
            return
        }
        job = scope.launch {
            delay(delayMs)
            onChange(true)
        }
    }
}
