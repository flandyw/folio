package com.folio.notes

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.viewinterop.AndroidView
import java.io.FileInputStream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelectionContextMenuTests {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowPaneKeepsActionsReachableInOverflow() {
        var copied = 0
        var styled = 0
        var cut = 0
        compose.setContent {
            MaterialTheme {
                SelectionContextMenu(152.dp, true, { copied++ }, { cut++ }, {}, { styled++ }, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("Copy selection").performClick()
        compose.runOnIdle { assertEquals(1, copied) }
        compose.onNodeWithContentDescription("Style selection").assertDoesNotExist()
        compose.onNodeWithContentDescription("More selection options").performClick()
        compose.onNodeWithText("Style").performClick()
        compose.runOnIdle { assertEquals(1, styled) }
        compose.onNodeWithText("Cut").assertDoesNotExist()
        compose.onNodeWithContentDescription("More selection options").performClick()
        compose.onNodeWithText("Cut").performClick()
        compose.runOnIdle { assertEquals(1, cut) }
    }

    @Test fun popupKeepsCanvasInputNonmodal() {
        var canvasTaps = 0
        var copies = 0
        compose.setContent {
            var frame by remember { mutableStateOf<Rect?>(null) }
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("canvas").clickable { canvasTaps++ }
                    .onGloballyPositioned { coordinates ->
                        val origin = coordinates.localToWindow(Offset.Zero)
                        frame = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
                    }) {
                    SelectionContextPopup(Rect(.3f, .5f, .7f, .7f), frame, frame) { width ->
                        SelectionContextMenu(width, true, { copies++ }, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Copy selection").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, copies); assertEquals(0, canvasTaps) }
        compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("screencap -p /data/local/tmp/folio-selection-context.png").use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
        }
        compose.onNodeWithTag("canvas").performTouchInput { click(Offset(width / 2f, height - 10f)) }
        compose.runOnIdle { assertEquals(1, copies); assertEquals(1, canvasTaps) }
    }
    @Test fun manipulationHidesTheMenuAndReleaseOrCancelRestoresItsAnchor() {
        lateinit var view: InkView
        var anchor: Rect? = null
        compose.setContent {
            AndroidView(factory = { context -> InkView(context).also {
                view = it
                it.fingerDrawing = true
                it.onSelectionViewBounds = { bounds -> anchor = bounds }
                it.bind(NotePage(infinite = true, strokes = listOf(
                    Stroke(Tool.LINE, 0xff000000.toInt(), 2f, listOf(InkPoint(100f, 200f), InkPoint(300f, 400f)))
                )), null)
            } }, modifier = Modifier.fillMaxSize())
        }
        fun event(action: Int, x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, x, y, 0).also { view.onTouchEvent(it); it.recycle() }
        }
        compose.runOnIdle {
            view.selectAll()
            val before = checkNotNull(anchor)
            event(MotionEvent.ACTION_DOWN, 180f, 280f)
            assertNull(anchor)
            event(MotionEvent.ACTION_MOVE, 220f, 330f)
            assertNull(anchor)
            event(MotionEvent.ACTION_UP, 220f, 330f)
            val after = checkNotNull(anchor)
            assertTrue(after.left > before.left)
            assertTrue(after.top > before.top)
            event(MotionEvent.ACTION_DOWN, after.right * view.width, after.bottom * view.height)
            assertNull(anchor)
            event(MotionEvent.ACTION_CANCEL, after.right * view.width, after.bottom * view.height)
            assertEquals(after, anchor)
            val rotateX = (after.left + after.right) / 2f * view.width
            val rotateY = after.top * view.height - SelectionChrome.ROTATE_LIFT_DP * view.resources.displayMetrics.density
            event(MotionEvent.ACTION_DOWN, rotateX, rotateY)
            assertNull(anchor)
            event(MotionEvent.ACTION_CANCEL, rotateX, rotateY)
            assertEquals(after, anchor)
        }
    }

}
