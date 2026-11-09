package com.stash.feature.nowplaying.ui

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * The Canvas video cache: a small, separate disk cache for Spotify's MP4s.
 *
 * It is deliberately NOT the player's streaming `SimpleCache`: Canvas files are short, thrown away
 * when the listener stops caring, and must never evict audio a song is about to need. 75 MB is
 * enough for a long run of Canvases and small enough to leave the library's storage alone. One
 * instance per process (Media3 locks a cache directory), lived for the process like the other
 * caches, and released only with the app.
 */
private object CanvasVideoCacheStore {
    private const val DIR = "canvas_video_cache"
    private const val MAX_BYTES = 75L * 1024 * 1024
    private val lock = Any()

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(lock) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, DIR).also { it.mkdirs() },
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }
}

/**
 * Spotify Canvas: the song's own looping video, muted and video-only, filling Now Playing behind
 * its controls.
 *
 * The video is a TextureView, not a SurfaceView, so it composes under the app's own text and
 * controls instead of punching a hole behind the window. It is cropped to cover the screen, and a
 * dark scrim (the same vocabulary as full art's) keeps the white type readable over any frame.
 *
 * Playback failures are silent: [onFailed] lets the caller bring the ordinary cover (or full art)
 * back, and nothing here can throw into the player that owns the music.
 *
 * @param url The resolved MP4 URL. Non-null only when the setting is on and this song has a Canvas.
 * @param playing The song is playing (not paused, not buffering): the Canvas follows it.
 * @param onFailed The video could not be built, prepared or decoded: fall back, do not retry.
 */
@Composable
internal fun CanvasVideoLayer(
    url: String,
    playing: Boolean,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnFailed by rememberUpdatedState(onFailed)
    val reducedMotion = rememberNpReducedMotion()
    val cache = remember(context) { runCatching { CanvasVideoCacheStore.get(context) }.getOrNull() }
    val player = remember(context) {
        cache?.let { runCatching { buildCanvasPlayer(context, it) }.getOrNull() }
    }
    val readyState = remember(url) { mutableStateOf(false) }
    val latestReady by rememberUpdatedState(readyState)
    val textureRef = remember { arrayOfNulls<TextureView>(1) }
    val videoSizePx = remember { intArrayOf(0, 0) }

    if (player == null) {
        LaunchedEffect(url) { currentOnFailed() }
        return
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                latestReady.value = true
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnFailed()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoSizePx[0] = videoSize.width
                videoSizePx[1] = videoSize.height
                textureRef[0]?.let { applyCenterCrop(it, videoSize.width, videoSize.height) }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.clearVideoSurface()
            player.release()
        }
    }

    LaunchedEffect(player, url) {
        val videoCache = cache ?: run {
            currentOnFailed()
            return@LaunchedEffect
        }
        try {
            player.stop()
            player.clearMediaItems()
            val source = ProgressiveMediaSource.Factory(cacheFactory(videoCache))
                .createMediaSource(MediaItem.fromUri(url))
            player.setMediaSource(source)
            player.prepare()
        } catch (e: Exception) {
            currentOnFailed()
        }
    }
    // The Canvas follows the music. Paused, the last frame stays (the view is still painted), so a
    // pause does not flash the cover back; buffering holds it still until the stream catches up.
    LaunchedEffect(player, playing) {
        player.playWhenReady = playing
    }
    // A URL that never paints a frame (a decoder that cannot show it, an audio-only answer, a CDN
    // that stalls) is a fallback too, not an invisible hole where the cover used to be.
    LaunchedEffect(player, url) {
        delay(12_000)
        if (!readyState.value) currentOnFailed()
    }

    val ready = readyState.value
    val alpha by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = tween(320),
        label = "canvas-video",
    )
    val shownAlpha = if (reducedMotion) (if (ready) 1f else 0f) else alpha

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { view ->
                    textureRef[0] = view
                    view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        if (v is TextureView) {
                            applyCenterCrop(v, videoSizePx[0], videoSizePx[1])
                        }
                    }
                    player.setVideoTextureView(view)
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = shownAlpha },
        )
        CanvasScrim(Modifier.fillMaxSize())
    }
}

/** The scrim under Now Playing's white type: darker at the top row and under the controls. */
@Composable
internal fun CanvasScrim(modifier: Modifier = Modifier) {
    LightStatusBarIcons(on = true)
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.10f),
                        0.45f to Color.Black.copy(alpha = 0.34f),
                        0.62f to Color.Black.copy(alpha = 0.54f),
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
    }
}

/** A muted, video-only, looping player over the Canvas cache; no audio focus is ever requested. */
private fun buildCanvasPlayer(context: Context, cache: SimpleCache): ExoPlayer {
    val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true))
    }
    return ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setHandleAudioBecomingNoisy(false)
        .build()
        .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
            setWakeMode(C.WAKE_MODE_NONE)
        }
}

private fun cacheFactory(cache: SimpleCache): CacheDataSource.Factory =
    CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent("Stash"))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

/**
 * Centre-crops [view]'s video, the way full art crops a page: fill the screen, hide the overflow,
 * never letterbox. Called again on every layout or video-size change.
 */
private fun applyCenterCrop(view: TextureView, videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0) return
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return
    val scale = max(viewWidth / videoWidth, viewHeight / videoHeight)
    val dx = (viewWidth - videoWidth * scale) / 2f
    val dy = (viewHeight - videoHeight * scale) / 2f
    view.setTransform(
        Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        },
    )
}
