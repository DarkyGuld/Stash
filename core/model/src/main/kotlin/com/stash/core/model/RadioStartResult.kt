package com.stash.core.model

/**
 * Outcome of [com.stash.core.media.PlayerRepository.startRadio]. Replaces a
 * Boolean that collapsed distinct failures into one misleading toast (issue:
 * silent radio button).
 */
sealed interface RadioStartResult {
    /** Station built and spliced/queued; the seed label is live. */
    data object Started : RadioStartResult

    /** MediaController unavailable (player still starting / connection lost). */
    data object PlayerNotReady : RadioStartResult

    /** Generator produced an empty first batch — no similar tracks found. */
    data object NoStation : RadioStartResult

    /** Download mode: a station streams, and Download mode plays only what is on the phone. */
    data object DownloadMode : RadioStartResult

    /** No Last.fm key and no proxy: there is no taste graph to build a station from. */
    data object NotConfigured : RadioStartResult

    /** The generator did not finish in time (a hung or crawling upstream). */
    data object Timeout : RadioStartResult
}
