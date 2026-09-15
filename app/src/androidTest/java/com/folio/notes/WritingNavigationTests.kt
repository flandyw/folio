package com.folio.notes

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Device coverage for the real hold control and the production input-to-page transform. */
class WritingNavigationTests {
    @get:Rule val compose = createComposeRule()
    private fun event(view: InkView, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also { view.onTouchEvent(it); it.recycle() }
    }

    @Test fun holdReleaseAndCancelPreserveCrossPageCameraToolAndLane() {
        lateinit var writing: InkView
        var held by mutableStateOf(false)
        val anchor = PeekAnchor("reference", 0f, 0f, 400f, 200f)
        compose.setContent {
            MaterialTheme {
                Box {
                    AndroidView(factory = { context -> InkView(context).also {
                        writing = it; it.bind(NotePage(id = "writing", infinite = true), null)
                        it.restore(ViewportSnapshot("writing", WorkspaceViewport(canvasX = -320.125f, canvasY = -870.5f, canvasZoom = 2.5375f)))
                        it.writingFollow.completed(listOf(InkPoint(0f, 100f), InkPoint(10f, 110f)), 0)
                    } }, update = { it.inputBlocked = held }, modifier = Modifier.fillMaxSize())
                    if (held) AndroidView(factory = { context -> InkView(context).apply {
                        bind(NotePage(id = "reference"), null); readOnly = true; inputBlocked = true; peekRegion = anchor
                    } }, modifier = Modifier.fillMaxSize())
                    PeekHoldButton(anchor) { held = it }
                }
            }
        }
        lateinit var before: ViewportSnapshot
        lateinit var lane: WritingFollowState
        compose.runOnIdle { before = writing.snapshot(); lane = writing.writingFollow.state }
        val button = compose.onNodeWithContentDescription("Hold to peek; release to return")
        button.performTouchInput { down(center) }
        compose.runOnIdle {
            assertTrue(held); assertTrue(writing.inputBlocked)
            event(writing, MotionEvent.ACTION_DOWN, 100f, 100f)
            event(writing, MotionEvent.ACTION_UP, 110f, 110f)
            assertTrue(writing.page.strokes.isEmpty())
        }
        button.performTouchInput { up() }
        compose.runOnIdle {
            assertFalse(held); assertEquals(before, writing.snapshot())
            assertEquals(lane, writing.writingFollow.state); assertEquals(Tool.PEN, writing.tool)
        }
        button.performTouchInput { down(center) }
        button.performTouchInput { cancel() }
        compose.runOnIdle { assertFalse(held); assertEquals(before, writing.snapshot()) }
    }

    @Test fun manualHandPanImmediatelySuspendsFollow() {
        lateinit var view: InkView
        compose.setContent { AndroidView(factory = { context -> InkView(context).also {
            view = it; it.bind(NotePage(infinite = true), null); it.followEnabled = true; it.tool = Tool.HAND
        } }, modifier = Modifier.fillMaxSize()) }
        compose.runOnIdle {
            view.writingFollow.completed(listOf(InkPoint(0f, 100f), InkPoint(10f, 110f)), 0)
            event(view, MotionEvent.ACTION_DOWN, 100f, 100f)
            event(view, MotionEvent.ACTION_MOVE, 150f, 110f)
            assertTrue(view.writingFollow.state.suspendedUntil > SystemClock.uptimeMillis())
            assertNull(view.writingFollow.state.baselineY)
            event(view, MotionEvent.ACTION_UP, 150f, 110f)
        }
    }

    @Test fun cameraMovementKeepsExistingSamplesAndMapsNewTipToPage() {
        lateinit var view: InkView
        compose.setContent { AndroidView(factory = { context -> InkView(context).also {
            view = it; it.bind(NotePage(id = "ink", infinite = true), null)
            it.fingerDrawing = true; it.shapeRecognition = false; it.scribbleToErase = false
        } }, modifier = Modifier.fillMaxSize()) }
        compose.runOnIdle {
            view.restore(ViewportSnapshot("ink", WorkspaceViewport(canvasZoom = 2f)))
            event(view, MotionEvent.ACTION_DOWN, 100f, 200f)
            // Same camera translation used by follow. Previously committed samples must stay put.
            view.restore(ViewportSnapshot("ink", WorkspaceViewport(canvasX = -10f, canvasZoom = 2f)))
            event(view, MotionEvent.ACTION_MOVE, 120f, 200f)
            event(view, MotionEvent.ACTION_UP, 120f, 200f)
            val points = view.page.strokes.single().points
            assertEquals(50f, points.first().x, 0f)
            assertEquals(65f, points.last().x, 0f)
            assertEquals(100f, points.last().y, 0f)
        }
    }
}
