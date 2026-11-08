package com.stash.core.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelayedLoadingSignalTest {
    @Test
    fun `only becomes true after the one-second window`() = runTest {
        val signal = DelayedLoadingSignal()
        var shown = false

        signal.update(this, true) { shown = it }
        advanceTimeBy(999)
        assertFalse(shown)
        advanceTimeBy(2)
        assertTrue(shown)
    }

    @Test
    fun `a load that finishes early never shows`() = runTest {
        val signal = DelayedLoadingSignal()
        var shown = false

        signal.update(this, true) { shown = it }
        advanceTimeBy(400)
        signal.update(this, false) { shown = it }
        advanceUntilIdle()

        assertFalse(shown)
    }

    @Test
    fun `a new load restarts the window`() = runTest {
        val signal = DelayedLoadingSignal()
        var shown = false

        signal.update(this, true) { shown = it }
        advanceTimeBy(900)
        signal.update(this, true) { shown = it } // new load
        advanceTimeBy(900)
        assertFalse(shown)
        advanceTimeBy(102)
        assertTrue(shown)
    }
}
