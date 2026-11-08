package com.stash.core.media.streaming

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleRowsWarmerTest {
    @Test
    fun `settles for half a second, then warms the new top rows only`() = runTest {
        val orchestrator: PrefetchOrchestrator = mockk(relaxed = true)
        val warmer = VisibleRowsWarmer(orchestrator)

        warmer.onVisibleRowsChanged(this, listOf(1, 2, 3))
        advanceTimeBy(300)
        // Rows changed before the debounce elapsed: the first warmup never runs.
        warmer.onVisibleRowsChanged(this, listOf(4, 5, 6))
        advanceUntilIdle()

        verify(exactly = 1) { orchestrator.warmSearchResults(any(), listOf(4L, 5L, 6L)) }
    }

    @Test
    fun `empty rows and a cancel drop the pending warmup`() = runTest {
        val orchestrator: PrefetchOrchestrator = mockk(relaxed = true)
        val warmer = VisibleRowsWarmer(orchestrator)

        warmer.onVisibleRowsChanged(this, listOf(1))
        warmer.cancel()
        advanceUntilIdle()
        warmer.onVisibleRowsChanged(this, emptyList())
        advanceUntilIdle()

        verify(exactly = 0) { orchestrator.warmSearchResults(any(), any()) }
    }
}
