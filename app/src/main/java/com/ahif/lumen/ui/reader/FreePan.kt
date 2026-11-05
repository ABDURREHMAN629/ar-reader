package com.ahif.lumen.ui.reader

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One-finger panning that moves [horizontal] and [vertical] together, so a
 * diagonal drag scrolls diagonally (nested scrollers would lock to one axis).
 * Includes fling. Two-finger gestures are ignored so a pinch handler can own them.
 * The wrapped scrollers should have their own user scrolling disabled.
 */
@Composable
fun Modifier.freePan(horizontal: ScrollableState, vertical: ScrollableState): Modifier {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val fling = remember { arrayOfNulls<Job>(1) }

    return this.pointerInput(horizontal, vertical) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fling[0]?.cancel()
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var dragging = false
            var multiTouch = false
            var travelled = Offset.Zero

            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) { multiTouch = true; break }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChange()
                if (!dragging) {
                    travelled += delta
                    if (travelled.getDistance() < viewConfiguration.touchSlop) continue
                    dragging = true
                }
                if (delta != Offset.Zero) {
                    horizontal.dispatchRawDelta(-delta.x)
                    vertical.dispatchRawDelta(-delta.y)
                    change.consume()
                }
            }

            if (dragging && !multiTouch) {
                val v = tracker.calculateVelocity()
                fling[0] = scope.launch {
                    launch { horizontal.flingWith(-v.x, decay) }
                    launch { vertical.flingWith(-v.y, decay) }
                }
            }
        }
    }
}

private suspend fun ScrollableState.flingWith(velocity: Float, decay: DecayAnimationSpec<Float>) {
    if (abs(velocity) < 60f) return
    scroll {
        var last = 0f
        AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(decay) {
            val delta = value - last
            last = value
            val consumed = scrollBy(delta)
            if (abs(delta - consumed) > 0.5f) cancelAnimation()
        }
    }
}
