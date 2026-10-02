package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class SubmenuPlacementTests {
    @get:Rule val compose = createComposeRule()

    private fun verifyPlacement(alignment: Alignment, opensRight: Boolean) {
        var parent: Rect? = null
        var child: Rect? = null
        var actionRuns = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(alignment).padding(24.dp)) {
                        var menu by remember { mutableStateOf(false) }
                        var submenu by remember { mutableStateOf(false) }
                        Button({ menu = true }) { Text("Open parent") }
                        DropdownMenu(menu, { menu = false }, modifier = Modifier.width(280.dp)) {
                            DropdownMenuItem({ Text("Another action") }, {})
                            Box(Modifier.onGloballyPositioned {
                                val origin = it.localToScreen(Offset.Zero)
                                parent = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                            }) {
                                SubmenuItem("Options", Icons.Rounded.Tune, submenu, { submenu = it }) {
                                    DropdownMenuItem({ Text("Child action") }, {
                                        actionRuns++; submenu = false; menu = false
                                    }, modifier = Modifier.onGloballyPositioned {
                                        val origin = it.localToScreen(Offset.Zero)
                                        child = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Open parent").performClick()
        compose.onNodeWithText("Options").performClick()
        compose.onNodeWithText("Child action").assertIsDisplayed()
        compose.runOnIdle {
            val row = checkNotNull(parent)
            val item = checkNotNull(child)
            assertTrue("Child should align with parent row: $row / $item", abs(row.top - item.top) < 2f)
            assertTrue("Child should open beside parent: $row / $item",
                if (opensRight) item.left >= row.right else item.right <= row.left)
        }
        compose.onNodeWithText("Child action").performClick()
        compose.runOnIdle { assertTrue(actionRuns == 1) }
        compose.onNodeWithText("Options").assertDoesNotExist()
    }

    @Test fun nestedPopupOpensBesideItsParentRow() = verifyPlacement(Alignment.CenterStart, true)
    @Test fun nestedPopupNearRightEdgeOpensOnTheLeft() = verifyPlacement(Alignment.CenterEnd, false)
}
