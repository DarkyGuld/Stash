package com.stash.core.data.addons

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyIsrcGateTest {
    private val gate = SpotifyIsrcGate(ApplicationProvider.getApplicationContext())
        .also { it.clearBackoff() }

    @Test
    fun `playback and a foreground lookup both pause the backfill`() {
        assertThat(gate.backfillAllowed()).isTrue()

        gate.playbackActive = true
        assertThat(gate.backfillAllowed()).isFalse()
        gate.playbackActive = false

        gate.beginForeground()
        assertThat(gate.backfillAllowed()).isFalse()
        gate.endForeground()
        assertThat(gate.backfillAllowed()).isTrue()
    }

    @Test
    fun `a 429 backoff is persisted and respected`() {
        gate.setBackoff(3_600)

        assertThat(gate.backoffUntilMs()).isGreaterThan(System.currentTimeMillis())
        assertThat(gate.backfillAllowed()).isFalse()

        gate.clearBackoff()
        assertThat(gate.backfillAllowed()).isTrue()
    }
}
