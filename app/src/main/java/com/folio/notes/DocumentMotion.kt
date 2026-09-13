package com.folio.notes

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Shared by native page gestures and the two-finger document gesture. Units are screen pixels. */
internal class DocumentMotion(private val consumeScroll: (Float) -> Float, private val scope: CoroutineScope, private val density: Float) {
    var stretch by mutableFloatStateOf(0f)
        private set
    private var animation: Job? = null

    fun stop() { animation?.cancel(); animation = null }
    fun reset() { stop(); stretch = 0f }

    fun drag(dy: Float): Boolean {
        var delta = dy
        // Unwind an existing stretch before moving back into the document.
        if (stretch * delta < 0f) {
            val recovery = delta.coerceIn(-abs(stretch), abs(stretch))
            stretch += recovery
            delta -= recovery
        }
        val consumed = consumeScroll(-delta)
        val remaining = delta + consumed
        if (abs(remaining) > .1f) {
            val limit = 140f * density
            stretch = (stretch + remaining * .45f / (1f + abs(stretch) / (48f * density))).coerceIn(-limit, limit)
            return true
        }
        return false
    }

    fun release(velocityY: Float) {
        stop()
        animation = scope.launch {
            if (abs(stretch) < .5f && abs(velocityY) > 50f * density) {
                var previous = 0f
                AnimationState(0f, velocityY.coerceIn(-10000f * density, 10000f * density)).animateDecay(
                    exponentialDecay(frictionMultiplier = .65f)
                ) {
                    val hitEdge = drag(value - previous)
                    previous = value
                    if (hitEdge) cancelAnimation()
                }
            }
            AnimationState(stretch).animateTo(0f, spring(dampingRatio = .8f, stiffness = 220f)) { stretch = value }
            stretch = 0f
        }
    }
}
