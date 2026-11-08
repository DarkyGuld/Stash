package com.stash.core.media.streaming

/**
 * The listener-facing name of the service a stream came from, keyed on
 * [StreamUrl.origin] (carried to Now Playing as `Track.streamOrigin`).
 *
 * Several resolvers reach the same catalog: qbdlx (relay), kennyy and squid
 * (parked) all serve Qobuz files, so they share one name. Tidal FLAC and
 * Tidal's AAC both resolve with [TidalStreamResolver.ORIGIN].
 *
 * Returns null for no origin (a downloaded or local file has none: the source
 * is not stored) and for an origin with no service a listener would know.
 */
object StreamService {
    const val QOBUZ = "Qobuz"
    const val TIDAL = "Tidal"
    const val JIOSAAVN = "JioSaavn"
    const val YOUTUBE = "YouTube"
    const val ADDON = "Addon"

    fun displayName(origin: String?): String? = when {
        // The ORIGIN constants of QbdlxStreamResolver / KennyyStreamResolver /
        // QobuzStreamResolver (private companions), pinned by StreamServiceTest.
        origin == "qbdlx" || origin == "kennyy" || origin == "squid" -> QOBUZ
        origin == TidalStreamResolver.ORIGIN -> TIDAL
        origin == JioSaavnStreamResolver.ORIGIN -> JIOSAAVN
        origin == YouTubeStreamResolver.ORIGIN -> YOUTUBE
        // "addon" for an unnamed addon; "addon:<name>" names the addon that served.
        origin == EclipseAddonStreamResolver.ORIGIN -> ADDON
        origin != null && origin.startsWith("${EclipseAddonStreamResolver.ORIGIN}:") ->
            origin.substringAfter(':').takeIf { it.isNotBlank() } ?: ADDON
        else -> null
    }
}
