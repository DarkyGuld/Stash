package com.stash.feature.nowplaying

import com.stash.core.model.Track

/**
 * Formats a one-line quality summary for the Now Playing screen.
 *
 * Examples:
 *   - All four fields known:  `FLAC · 24-bit/96.0 kHz · 4233 kbps`
 *   - Codec + bitrate only:    `OPUS · 160 kbps`
 *   - Codec only:              `FLAC` (data not yet backfilled)
 *   - Eclipse addon stream:    `FLAC · via Nebula`
 *
 * Returns null when the codec is blank, and for a streaming track that has
 * not resolved yet (see the local-file guard below): in both cases the
 * caller should render no line at all.
 */
internal fun trackQualityText(track: Track): String? {
    // A stream that hasn't resolved yet still carries the DB default
    // fileFormat ("opus"). Showing that would flash "OPUS" and then drop the
    // line when the real format lands. Nothing true is known yet, so render
    // nothing. Downloaded/local files are excluded: their format comes from
    // the file on disk (fileFormat, or the filePath extension below).
    val hasLocalFile = track.isDownloaded || !track.filePath.isNullOrBlank()
    if (!hasLocalFile && track.streamOrigin == null) return null

    // v0.9.13 fix: tracks downloaded before format-tracking was wired (pre-v0.9.11)
    // default to file_format = "opus" regardless of the actual codec — so a FLAC
    // file would render "OPUS · 4233 kbps", which is the source of "every track says
    // Opus" complaints. The Library Health backfill writes correct values from disk
    // but only when the user opens that screen. Cheap interim correction: if the
    // track has a downloaded filePath, prefer the file extension as canonical.
    val extension = track.filePath
        ?.takeIf { it.isNotBlank() }
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    val codec = when (extension) {
        "flac", "alac", "wav", "ape", "tta", "wv", "aiff" -> extension!!.uppercase()
        "opus", "m4a", "mp3", "ogg", "aac" -> extension!!.uppercase()
        else -> track.fileFormat.takeIf { it.isNotBlank() }?.uppercase() ?: return null
    }
    val bitDepth = track.bitsPerSample
    val sampleRateKHz = track.sampleRateHz?.let { it / 1000.0 }
    val bitrate = track.qualityKbps.takeIf { it > 0 }
    val origin = track.streamOrigin
    return buildList {
        add(codec)
        if (bitDepth != null && sampleRateKHz != null) {
            add("${bitDepth}-bit/${"%.1f".format(sampleRateKHz)} kHz")
        }
        if (bitrate != null) add("$bitrate kbps")
        // Flag the lossy fallbacks so the user can tell when a track is playing
        // from yt-dlp/InnerTube extraction rather than Qobuz. The codec ("AAC")
        // alone doesn't convey this — Qobuz also serves AAC at MP3_320 tier.
        // Only the streamOrigin field distinguishes the two. We don't badge
        // "via Kennyy" / "via squid" because those are the expected primary
        // sources; only lossy fallbacks and Eclipse addons get a callout.
        //
        // Addons stamp their origin as `addon:<Addon Name>`, so the line names
        // which one served the track (Cloud included); a bare `addon:` falls
        // back to the generic label.
        //
        // The service a lossless stream comes from (Qobuz, Tidal) is named
        // only in the signal-path sheet's Source row, never on this line.
        when {
            origin == null -> Unit
            origin.startsWith("addon:") -> add(
                "via " + origin.substringAfter(':').ifBlank { "Addon" },
            )
            origin == "jiosaavn" -> add("via JioSaavn")
            origin == "youtube" -> add("via YT")
        }
    }.joinToString(" · ")
}
