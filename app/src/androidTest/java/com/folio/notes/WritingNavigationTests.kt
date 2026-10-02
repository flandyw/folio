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

    private fun followView(preferences: FollowPreferences, onStatus: (WritingFollowStatus) -> Unit): InkView {
        lateinit var view: InkView
        compose.setContent { AndroidView(factory = { context -> InkView(context).also {
            view = it; it.bind(NotePage(id = "follow", infinite = true), null)
            it.followEnabled = true; it.followPreferences = preferences
            it.shapeRecognition = false; it.scribbleToErase = false; it.onFollowStatus = onStatus
        } }, modifier = Modifier.fillMaxSize()) }
        compose.runOnIdle { view.restore(ViewportSnapshot("follow", WorkspaceViewport(canvasZoom = 2f))) }
        return view
    }

    private fun write(view: InkView, x: Float, y: Float) {
        event(view, MotionEvent.ACTION_DOWN, x, y - 40f)
        event(view, MotionEvent.ACTION_MOVE, x + 16f, y)
        event(view, MotionEvent.ACTION_UP, x + 16f, y)
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

    @Test fun explicitPausePersistsThroughWritingAndManualNavigationUntilResumed() {
        var status = WritingFollowStatus()
        val view = followView(FollowPreferences(mode = FollowMode.MATH, adaptiveTiming = false,
            returnDelayMs = 300, glideDurationMs = 120)) { status = it }
        lateinit var before: ViewportSnapshot
        compose.runOnIdle {
            before = view.snapshot(); view.pauseWritingFollow()
            write(view, view.width * .8f, view.height * .85f)
            assertTrue(status.paused)
        }
        SystemClock.sleep(600)
        compose.runOnIdle {
            assertEquals(before, view.snapshot())
            view.tool = Tool.HAND
            event(view, MotionEvent.ACTION_DOWN, 100f, 100f)
            event(view, MotionEvent.ACTION_MOVE, 140f, 120f)
            event(view, MotionEvent.ACTION_UP, 140f, 120f)
            assertTrue(status.paused)
            view.tool = Tool.PEN
            write(view, view.width * .8f, view.height * .9f)
            before = view.snapshot()
        }
        SystemClock.sleep(600)
        compose.runOnIdle {
            assertEquals(before, view.snapshot())
            view.resumeWritingFollow()
            write(view, view.width * .8f, view.height * .96f)
            assertFalse(status.paused)
        }
        compose.waitUntil(5000) { status.message == "Following · Back restores the view" }
        compose.runOnIdle { assertTrue(view.snapshot().viewport.canvasY < before.viewport.canvasY) }
    }

    @Test fun touchDownCancelsQueuedMovementAndBackStillRestoresTheLastGlide() {
        var status = WritingFollowStatus()
        val view = followView(FollowPreferences(mode = FollowMode.MATH, adaptiveTiming = false,
            returnDelayMs = 300, glideDurationMs = 120)) { status = it }
        lateinit var original: ViewportSnapshot
        lateinit var followed: ViewportSnapshot
        compose.runOnIdle {
            original = view.snapshot()
            write(view, view.width * .6f, view.height * .9f)
        }
        compose.waitUntil(5000) { status.message == "Following · Back restores the view" }
        compose.runOnIdle {
            assertTrue(status.canGoBack)
            followed = view.snapshot()
            write(view, view.width * .6f, view.height * .96f)
            event(view, MotionEvent.ACTION_DOWN, view.width * .3f, view.height * .4f)
        }
        SystemClock.sleep(650)
        compose.runOnIdle {
            assertEquals(followed, view.snapshot())
            event(view, MotionEvent.ACTION_CANCEL, 0f, 0f)
            view.backWritingView()
            assertEquals(original.viewport.canvasY, view.snapshot().viewport.canvasY, .001f)
            assertEquals(original.viewport.canvasX, view.snapshot().viewport.canvasX, .001f)
            assertFalse(status.canGoBack)
            assertTrue(status.paused)
        }
    }

    @Test fun automaticReturnWaitsForTheLearnedWordGap() {
        var status = WritingFollowStatus()
        val view = followView(FollowPreferences(automaticReturn = true, returnDelayMs = 300,
            glideDurationMs = 120, horizontalFollow = false, verticalFollow = false)) { status = it }
        lateinit var before: ViewportSnapshot
        compose.runOnIdle {
            view.writingRegion = WritingLane(0f, 0f, view.width / 2f, view.height * 2f)
            view.writingFollow.state = WritingFollowState(writingGaps = listOf(900, 900, 900, 900))
            before = view.snapshot()
            write(view, view.width - 140f, view.height * .5f)
            write(view, view.width - 40f, view.height * .5f)
            assertTrue(status.message.startsWith("Next line in 1.1 s"))
        }
        SystemClock.sleep(650)
        compose.runOnIdle { assertEquals(before, view.snapshot()) }
        compose.waitUntil(5000) { status.message == "Next line · Back restores the view" }
        compose.runOnIdle { assertEquals(-64f, view.snapshot().viewport.canvasY, .001f) }
    }

    @Test fun unboundedCanvasFollowsWritingAboveAndLeftOfTheOrigin() {
        var status = WritingFollowStatus()
        val view = followView(FollowPreferences(automaticReturn = true, adaptiveTiming = false,
            returnDelayMs = 300, glideDurationMs = 120)) { status = it }
        lateinit var before: ViewportSnapshot
        compose.runOnIdle {
            view.restore(ViewportSnapshot("follow", WorkspaceViewport(canvasX = view.width.toFloat(),
                canvasY = view.height.toFloat(), canvasZoom = 2f)))
            before = view.snapshot()
            write(view, view.width * .9f, view.height * .9f)
            assertTrue(view.writingFollow.state.baselineY!! < 0f)
            assertTrue(view.writingFollow.state.lineStartX!! < 0f)
        }
        compose.waitUntil(5000) { status.message == "Following · Back restores the view" }
        compose.runOnIdle {
            assertTrue(view.snapshot().viewport.canvasY < before.viewport.canvasY)
            assertTrue(status.canGoBack)
        }
    }

    @Test fun writingInAnotherImplicitGuideAreaUsesTheCurrentStroke() {
        var status = WritingFollowStatus()
        val view = followView(FollowPreferences(horizontalFollow = false, verticalFollow = false)) { status = it }
        compose.runOnIdle {
            view.bind(NotePage(id = "follow", infinite = false), null)
            view.documentFollowZoom = 2f
            view.writingGuides = listOf(WritingGuide(36f, 300f, 100f), WritingGuide(36f, 300f, 128f),
                WritingGuide(400f, 804f, 100f), WritingGuide(400f, 804f, 128f))
            val scale = minOf(view.width / view.page.width, view.height / view.page.height)
            val ox = (view.width - view.page.width * scale) / 2
            val oy = (view.height - view.page.height * scale) / 2
            fun letter(x: Float) {
                event(view, MotionEvent.ACTION_DOWN, ox + x * scale, oy + 80f * scale)
                event(view, MotionEvent.ACTION_MOVE, ox + (x + 10f) * scale, oy + 100f * scale)
                event(view, MotionEvent.ACTION_UP, ox + (x + 10f) * scale, oy + 100f * scale)
            }
            letter(50f)
            letter(410f)
            assertFalse(status.message.startsWith("Outside answer area"))
            assertEquals(410f, view.writingFollow.state.frontierLeft!!, .001f)
            assertEquals(420f, view.writingFollow.state.frontierRight!!, .001f)
            assertFalse(view.writingFollow.readyForReturn())
        }
    }
}
