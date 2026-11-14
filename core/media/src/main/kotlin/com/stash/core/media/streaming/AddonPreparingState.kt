package com.stash.core.media.streaming

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "The addon is preparing this song."
 *
 * A cache-backed addon answers 503 while it installs the release. The wait is
 * short, but it is a wait - and a silent one reads as a hang. The player
 * shows "forbereder…" while this is true, so the user knows something is
 * happening.
 *
 * The wait itself is short on purpose: [EclipseAddonStreamResolver] gives up
 * after a few seconds and lets the next source answer, so a song that never
 * becomes ready does not hold the player.
 */
@Singleton
class AddonPreparingState @Inject constructor() {
    private val _preparing = MutableStateFlow(false)

    /** True while an addon is preparing a release. */
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    internal fun setPreparing(preparing: Boolean) {
        _preparing.value = preparing
    }
}
