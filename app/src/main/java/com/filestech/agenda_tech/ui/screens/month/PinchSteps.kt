package com.filestech.agenda_tech.ui.screens.month

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Turns a two-finger pinch into one step: spreading the fingers calls [onStep] with `true`, bringing
 * them together with `false`. One step per gesture whatever its amplitude — the pinch chooses between
 * three displays, it does not adjust a zoom level.
 *
 * The events are read on the Initial pass and consumed as soon as a second finger is down: the pager
 * underneath would otherwise take the same movement for a swipe to the next month, and the cell under
 * the first finger for a tap. A single finger is never touched, so swiping, tapping and scrolling
 * behave exactly as before.
 *
 * [onStep] is captured once, when the gesture detector starts: read anything that changes through
 * `rememberUpdatedState`.
 */
fun Modifier.pinchSteps(onStep: (spread: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var zoom = 1f
        var stepped = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
                if (!stepped && (zoom > PINCH_STEP_RATIO || zoom < 1f / PINCH_STEP_RATIO)) {
                    stepped = true
                    onStep(zoom > 1f)
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** How far the fingers must travel, as a ratio of their starting distance, before the display changes. */
private const val PINCH_STEP_RATIO = 1.3f
