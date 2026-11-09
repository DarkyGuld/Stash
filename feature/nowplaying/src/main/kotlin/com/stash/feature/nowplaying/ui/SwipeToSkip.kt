package com.stash.feature.nowplaying.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign

/** How long the content takes to leave toward the side it was pulled once a swipe commits a skip. */
private const val SKIP_OUT_MS = 160

/**
 * How long the content waits off to the side for the player to report the new song before it comes
 * back anyway (a skip that went nowhere: a Listen Together host's Previous that restarted the song,
 * a single-song repeat-all queue). Normally the new song is in within a frame or two.
 */
private const val SKIP_SETTLE_TIMEOUT_MS = 900L

/** Where the new song's content starts, as a fraction of the width on the far side, before it eases in. */
private const val SKIP_ENTER_FRACTION = 0.35f

/**
 * Swipe-to-skip's two halves: [gesture] goes on the surface that takes the drag, [content] on what
 * moves with the finger. [content] must sit inside [gesture]: a drag read in coordinates that move
 * with the dragged content would see almost no movement.
 *
 * [moving] is true from the first drag until the settle animation (including the new song's entry)
 * finishes. Now Playing's Canvas reads it to let an incoming cover/Canvas take over with the same
 * swipe instead of popping in; the album and mini player ignore it.
 */
internal class SwipeToSkip(
    val gesture: Modifier,
    val content: Modifier,
    val moving: State<Boolean>,
)

/**
 * A horizontal swipe that skips to the next / previous song: shared by the Now Playing art and the
 * mini player, so both commit on the same travel or flick ([swipeSkipOutcome]).
 *
 * The content follows the finger, rubber-banding toward a side with nothing there ([canNext] /
 * [canPrevious]), and fades as it goes. Short of the threshold it springs back. A committed swipe
 * sends it off the side it was pulled toward, calls [onNext] / [onPrevious] (the transport's own
 * ViewModel calls, so the skip logic, the offline guard and Listen Together's routing all apply),
 * waits for the player's new song ([trackKey] changing), and eases the new content in from the far
 * side. With the system's animations removed nothing slides: the skip happens and the content
 * returns to place. Not [enabled] (a Listen Together listener), [gesture] only measures.
 *
 * Callers give the same actions to TalkBack (a button or a custom action): this is touch only.
 */
@Composable
internal fun rememberSwipeToSkip(
    trackKey: Any?,
    enabled: Boolean,
    canNext: Boolean,
    canPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): SwipeToSkip {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reducedMotion = rememberNpReducedMotion()
    val flingPxPerSec = with(LocalDensity.current) { SWIPE_SKIP_FLING_DP_PER_SEC.dp.toPx() }
    val scope = rememberCoroutineScope()

    var widthPx by remember { mutableIntStateOf(0) }
    // The finger's raw travel; drawn through swipeVisualOffset (rubber band toward nothing).
    var dragX by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // A drag is live, or its settle (out, wait, entry) is still running. A State, not a plain
    // Boolean: the caller reads it outside this composable and must be invalidated when it flips.
    val moving = remember { mutableStateOf(false) }

    // The drag's callbacks outlive recompositions, so what they read that can change goes through
    // updated state.
    val currentTrackKey by rememberUpdatedState(trackKey)
    val currentCanNext by rememberUpdatedState(canNext)
    val currentCanPrevious by rememberUpdatedState(canPrevious)
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)

    val springBack = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    val release: (Float) -> Unit = { velocity ->
        val width = widthPx.toFloat()
        val outcome = swipeSkipOutcome(
            offsetPx = dragX,
            velocityPxPerSec = velocity,
            widthPx = width,
            flingThresholdPxPerSec = flingPxPerSec,
            canNext = currentCanNext,
            canPrevious = currentCanPrevious,
            rtl = rtl,
        )
        settleJob?.cancel()
        settleJob = scope.launch {
            if (outcome == SwipeSkip.NONE) {
                if (reducedMotion) {
                    dragX = 0f
                } else {
                    animate(dragX, 0f, initialVelocity = velocity, animationSpec = springBack) { v, _ -> dragX = v }
                }
                moving.value = false
                return@launch
            }
            val keyBefore = currentTrackKey
            val outTo = sign(dragX) * width
            if (!reducedMotion) {
                animate(dragX, outTo, animationSpec = tween(SKIP_OUT_MS)) { v, _ -> dragX = v }
            }
            if (outcome == SwipeSkip.NEXT) currentOnNext() else currentOnPrevious()
            if (reducedMotion) {
                dragX = 0f
                moving.value = false
                return@launch
            }
            // Held off to the side until the next song is in, so the old song doesn't slide back
            // into place first and then flip.
            val changed = withTimeoutOrNull(SKIP_SETTLE_TIMEOUT_MS) {
                snapshotFlow { currentTrackKey }.first { it != keyBefore }
                true
            } ?: false
            // The new song comes in from the far side, the way a pager's next page does.
            if (changed) dragX = -sign(outTo) * width * SKIP_ENTER_FRACTION
            animate(dragX, 0f, animationSpec = springBack) { v, _ -> dragX = v }
            moving.value = false
        }
    }

    val measured = Modifier.onSizeChanged { widthPx = it.width }
    val gesture = if (!enabled) {
        measured
    } else {
        measured.draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> dragX += delta },
            onDragStarted = { _ ->
                // A new swipe takes over from a settling one. If the content is still off to the
                // side waiting for the song, it starts again from its place.
                moving.value = true
                settleJob?.cancel()
                if (abs(dragX) >= widthPx * 0.9f) dragX = 0f
            },
            onDragStopped = { velocity -> release(velocity) },
        )
    }
    val content = Modifier.graphicsLayer {
        val x = swipeVisualOffset(dragX, currentCanNext, currentCanPrevious, rtl)
        translationX = x
        // Fades as it leaves, gone at a full width away. ModulateAlpha, not an offscreen layer:
        // that would clip anything drawn past the bounds (the art's glow).
        alpha = if (widthPx > 0) 1f - (abs(x) / widthPx).coerceIn(0f, 1f) else 1f
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    return SwipeToSkip(gesture, content, moving)
}
