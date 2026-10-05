@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Material 3e button group with the standard overflow affordance: connected buttons that grow on
 * press, and anything that no longer fits moves into a trailing menu. [menuItems] are shown there
 * when the row runs out of room, so callers can offer the full choice on narrow shelves.
 */
@Composable internal fun FolioButtonGroup(
    modifier: Modifier = Modifier,
    menuItems: List<Pair<String, () -> Unit>> = emptyList(),
    content: ButtonGroupScope.() -> Unit
) {
    ButtonGroup(
        overflowIndicator = { overflow ->
            Box {
                FilledIconButton({ overflow.show() }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.MoreVert, "More options", Modifier.size(18.dp))
                }
                DropdownMenu(overflow.isExpanded, overflow::dismiss, modifier = Modifier.guardUiTouches()) {
                    if (menuItems.isEmpty()) {
                        DropdownMenuItem({ Text("Nothing else") }, {}, enabled = false)
                    }
                    menuItems.forEach { (label, action) ->
                        DropdownMenuItem({ Text(label) }, { overflow.dismiss(); action() })
                    }
                }
            }
        },
        modifier = modifier,
        content = content
    )
}

/**
 * Keep one toggle mounted so press and selection changes morph between Expressive shapes.
 * A long-press runs [onLongClick] (the tool's secondary action) without stealing the tap.
 */
@Composable internal fun FolioToolToggle(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    /** Current ink colour shown as a dot, so the pen reads its colour without opening options. */
    indicatorColor: Color? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val hold = rememberLongPressGuard()
    Box(Modifier.size(40.dp).then(if (onLongClick != null) Modifier.longPressAction(hold, onLongClick) else Modifier), contentAlignment = Alignment.Center) {
        FilledTonalIconToggleButton(
            checked = selected,
            // Tapping the active pen still opens its options rather than deselecting the tool.
            onCheckedChange = { hold.click(onClick)() },
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.size(40.dp).folioSelected(selected),
            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) { Icon(icon, label, Modifier.size(20.dp)) }
        if (indicatorColor != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = FolioSpacing.dp4, bottom = FolioSpacing.dp4).size(9.dp)
                    .background(indicatorColor, CircleShape)
                    .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            )
        }
    }
}

@Preview(name = "Expressive light", showBackground = true)
@Preview(name = "Expressive large text", showBackground = true, fontScale = 1.5f)
@Composable private fun ExpressiveLightPreview() { ExpressivePreview(dark = false) }

@Preview(name = "Expressive dark", showBackground = true)
@Composable private fun ExpressiveDarkPreview() { ExpressivePreview(dark = true) }

@Composable private fun ExpressivePreview(dark: Boolean) {
    FolioTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
        Surface {
            Column(Modifier.padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                Text("Your notebooks", style = MaterialTheme.typography.headlineMedium)
                FilledTonalButton(onClick = {}, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Text("New notebook")
                }
                var penSelected by remember { mutableStateOf(true) }
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(FolioSpacing.dp6), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                        FolioToolToggle(penSelected, { penSelected = true }, Icons.Rounded.Edit, "Pen")
                        FolioToolToggle(!penSelected, { penSelected = false }, Icons.Rounded.Gesture, "Lasso select")
                    }
                }
                LoadingIndicator()
                FolioButtonGroup {
                    toggleableItem(checked = true, onCheckedChange = {}, label = "Solid",
                        icon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) })
                    toggleableItem(checked = false, onCheckedChange = {}, label = "Dashed")
                    toggleableItem(checked = false, onCheckedChange = {}, label = "Dotted")
                }
                ContainedLoadingIndicator(Modifier.size(56.dp))
            }
        }
    }
}
