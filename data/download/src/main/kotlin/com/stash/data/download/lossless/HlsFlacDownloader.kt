package com.stash.data.download.lossless

import android.util.Log
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okio.buffer
import okio.sink

/**
 * Downloads an HLS-served FLAC (Tidal through an Eclipse addon): fetches the
 * media playlist, then its `#EXT-X-MAP` init and every segment in order,
 * byte-concatenating them into one fragmented MP4. That file is what Tidal's
 * HLS serves; no re-encode and no ffmpeg remux.
 *
 * Deliberately strict: encrypted, live and master playlists are unsupported and
 * say so through the failure message (the download watchdog keeps it), never a
 * half-file on disk. Segment URLs are signed and get no extra headers; the
 * playlist fetch is the only one that may carry [headers].
 */
@Singleton
class HlsFlacDownloader @Inject constructor() {
    suspend fun download(
        client: OkHttpClient,
        playlistUrl: String,
        headers: Map<String, String>,
        destination: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val temp = File(destination.parentFile, "${destination.nameWithoutExtension}.hls.m4a")
        try {
            val playlist = fetchText(client, playlistUrl, headers)
            val plan = HlsPlaylistParser.parse(playlist, playlistUrl)
                ?: return@withContext Result.failure(
                    IOException("HLS download isn't supported for this track (encrypted, live or master playlist)"),
                )
            val urls = listOfNotNull(plan.initUrl) + plan.segmentUrls
            destination.parentFile?.mkdirs()
            var written = 0L
            temp.sink().buffer().use { sink ->
                urls.forEachIndexed { index, url ->
                    currentCoroutineContext().ensureActive()
                    written += fetchInto(client, url, sink)
                    if (written > MAX_BYTES) {
                        throw IOException("HLS download exceeded ${MAX_BYTES / (1024 * 1024)} MB")
                    }
                    onProgress(index + 1L, urls.size.toLong())
                }
                sink.flush()
            }
            if (temp.length() == 0L) throw IOException("HLS download produced an empty file")
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) throw IOException("couldn't move the HLS download into place")
            Log.d(TAG, "hls download complete: $written bytes, ${urls.size} parts")
            Result.success(destination)
        } catch (e: CancellationException) {
            runCatching { if (temp.exists()) temp.delete() }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "hls download failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { if (temp.exists()) temp.delete() }
            Result.failure(e)
        }
    }

    private suspend fun fetchText(client: OkHttpClient, url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.build())
        return coroutineScope {
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HLS playlist HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Streams one segment into [sink]; signed segment URLs carry no extra headers. */
    private suspend fun fetchInto(client: OkHttpClient, url: String, sink: BufferedSink): Long {
        val call = client.newCall(Request.Builder().url(url).get().build())
        return coroutineScope {
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HLS segment HTTP ${response.code}")
                    val body = response.body ?: throw IOException("HLS segment empty body")
                    val source = body.source()
                    var read = 0L
                    val buffer = okio.Buffer()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer, 64 * 1024)
                        if (n == -1L) break
                        sink.write(buffer, n)
                        read += n
                        if (read > MAX_BYTES) break
                    }
                    read
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "HlsFlacDownloader"
        const val MAX_BYTES = 150L * 1024 * 1024
    }
}
