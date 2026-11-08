package com.stash.data.download.search

import com.stash.data.download.isLosslessDurationDegraded
import com.stash.data.download.lossless.SourceResult

/**
 * Checks for the Search tab's lossless download, which copies a source's file out
 * of the preview [androidx.media3.datasource.cache.SimpleCache].
 *
 * That download used to read the cache under the preview's own key,
 * `lossless:<videoId>`, which names the song but not the file. After a preview at
 * one quality (or of a catalog match that later changed), the cache held another
 * file's byte spans and content length under that key, so a download at another
 * quality spliced the two together and stopped at the preview's length: a valid CD
 * STREAMINFO followed by hi-res frames, committed as complete. The path also had
 * none of the codec or length checks DownloadManager applies.
 */
internal object SearchLosslessFile {

    /**
     * Cache key naming the exact file [match] fetches: source, the source's own track
     * id and the format, so bytes cached under it always come from one file. Without
     * a source track id the signed URL stands in, which reuses nothing but can never
     * mix files. Never equal to the preview's `lossless:<videoId>` key.
     */
    fun cacheKey(match: SourceResult): String {
        // Never the signed URL: its query carries exp/sig/token. Host+path only,
        // and the same file with a fresh signature keeps the same key.
        val file = match.sourceTrackId?.takeIf { it.isNotBlank() }
            ?: "url:${match.downloadUrl.substringBefore('?').substringBefore('#')}"
        val f = match.format
        return "lossless-dl:${match.sourceId}:$file:${f.codec}/${f.bitsPerSample}/${f.sampleRateHz}"
    }

    /**
     * Why the fetched file must not be committed, or null when it may be.
     *
     * Missing data never rejects (an unreadable probe, an unknown codec or length),
     * matching DownloadManager's backstop: only a contradiction does.
     *
     * @param bytesWritten bytes copied into the temp file.
     * @param expectedBytes the length the data source reported on open, or a
     *   negative value (`C.LENGTH_UNSET`) when unknown.
     * @param probedFormat [com.stash.core.data.audio.AudioMetadata.format].
     * @param probedMs the probed duration.
     * @param expectedMs the track's duration, 0 when unknown.
     */
    fun problem(
        expectedCodec: String,
        bytesWritten: Long,
        expectedBytes: Long,
        probedFormat: String?,
        probedMs: Long?,
        expectedMs: Long,
    ): String? {
        if (expectedBytes >= 0 && bytesWritten != expectedBytes) {
            return "copied $bytesWritten of $expectedBytes bytes"
        }
        if (expectedCodec.equals("flac", ignoreCase = true) &&
            probedFormat != null && probedFormat != "unknown" && probedFormat != "flac"
        ) {
            return "expected FLAC, file probes as $probedFormat"
        }
        if (isLosslessDurationDegraded(probedMs, expectedMs)) {
            return "file runs ${probedMs}ms against the track's ${expectedMs}ms"
        }
        return null
    }
}
