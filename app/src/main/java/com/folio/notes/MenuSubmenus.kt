package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Align with the parent row, flipping sides before clamping at a window edge. */
internal class SubmenuPositionProvider(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val right = anchorBounds.right + gap
        val left = anchorBounds.left - gap - popupContentSize.width
        val first = if (layoutDirection == LayoutDirection.Ltr) right else left
        val second = if (layoutDirection == LayoutDirection.Ltr) left else right
        val maxX = (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
        val maxY = (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin)
        val x = when {
            first in margin..maxX -> first
            second in margin..maxX -> second
            else -> first.coerceIn(margin, maxX)
        }
        return IntOffset(x, (anchorBounds.top - margin).coerceIn(margin, maxY))
    }
}

/**
 * A popover row that opens a second-level popover beside itself. The child
 * menu is anchored to this row's measured bounds; the caller dismisses its own
 * menu, so any action run from the submenu should close both.
 */
@Composable
internal fun SubmenuItem(
    label: String, icon: ImageVector, expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean = true, content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val positionProvider = remember(density, view) {
        val delegate = with(density) { SubmenuPositionProvider(FolioSpacing.dp8.roundToPx(), FolioSpacing.dp4.roundToPx()) }
        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                // Compose 1.8 supplies bounds inside the parent popup's window, but Android
                // positions nested popup windows in screen coordinates. Convert before
                // choosing a side and clamping against the display edges.
                view.getLocationOnScreen(screenLocation)
                view.getLocationInWindow(windowLocation)
                val windowOffset = IntOffset(
                    screenLocation[0] - windowLocation[0],
                    screenLocation[1] - windowLocation[1]
                )
                return delegate.calculatePosition(
                    anchorBounds.translate(windowOffset), windowSize, layoutDirection, popupContentSize
                )
            }
        }
    }
    Box {
        FolioMenuItem(
            text = { Text(label) },
            onClick = { onExpandedChange(!expanded) },
            leadingIcon = { Icon(icon, null) },
            trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open submenu") },
            enabled = enabled
        )
        if (expanded) Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { onExpandedChange(false) },
            properties = PopupProperties(focusable = true)
        ) {
            BoxWithConstraints {
                FolioPopoverSurface(Modifier.width(minOf(300.dp, (maxWidth - 24.dp).coerceAtLeast(0.dp)))
                    .heightIn(max = (maxHeight - FolioSpacing.dp24).coerceAtLeast(0.dp))) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(FolioSpacing.dp8),
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) { content() }
                }
            }
        }
    }
}

/** Section heading inside a menu or submenu. */
@Composable
internal fun MenuSectionHeader(label: String) {
    Text(label, Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}
