package com.folio.notes

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class DocumentMotionTests {
    private class Frames : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test fun releaseContinuesScrollingAndSlowsToRest() = runBlocking {
        var position = 0f
        val motion = DocumentMotion({ position += it; it }, CoroutineScope(coroutineContext + Frames()), 1f)
        motion.drag(-100f)
        motion.release(-1800f)
        coroutineContext[Job]!!.children.toList().joinAll()
        assertTrue(position > 500f)
        assertEquals(0f, motion.stretch, .01f)
    }

    @Test fun edgesResistPullAndReturnToRest() = runBlocking {
        val motion = DocumentMotion({ 0f }, CoroutineScope(coroutineContext + Frames()), 1f)
        motion.drag(100f)
        val firstPull = motion.stretch
        motion.drag(100f)
        assertTrue(firstPull in 0f..100f)
        assertTrue(motion.stretch - firstPull < firstPull)
        repeat(100) { motion.drag(100f) }
        assertTrue(motion.stretch <= 140f)
        motion.release(2000f)
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(0f, motion.stretch, .01f)
    }

    @Test fun flingStopsAtBoundaryAndSpringsBack() = runBlocking {
        var position = 0f
        val motion = DocumentMotion({ delta ->
            val next = (position + delta).coerceIn(0f, 200f)
            (next - position).also { position = next }
        }, CoroutineScope(coroutineContext + Frames()), 1f)
        motion.release(-2500f)
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(200f, position, .01f)
        assertEquals(0f, motion.stretch, .01f)
    }

    @Test fun reversingAtAnEdgeUnwindsStretchBeforeScrolling() = runBlocking {
        var position = 0f
        val motion = DocumentMotion({ delta ->
            val next = (position + delta).coerceAtLeast(0f)
            (next - position).also { position = next }
        }, this, 1f)
        motion.drag(100f)
        motion.drag(-20f)
        assertEquals(0f, position, .01f)
        assertEquals(25f, motion.stretch, .01f)
        motion.drag(-50f)
        assertEquals(25f, position, .01f)
        assertEquals(0f, motion.stretch, .01f)
    }

    @Test fun touchStopsMomentumImmediately() = runBlocking {
        var position = 0f
        val motion = DocumentMotion({ position += it; it }, CoroutineScope(coroutineContext + Frames()), 1f)
        motion.release(-2000f)
        repeat(5) { yield() }
        motion.stop()
        val stoppedAt = position
        coroutineContext[Job]!!.children.toList().joinAll()
        assertTrue(stoppedAt > 0f)
        assertEquals(stoppedAt, position, .01f)
    }
}
