@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes.editor.follow

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp

/**
 * Compact icons retain full touch targets and spoken labels inside the floating toolbar.
 *
 * The active (highlighted) container is a circle: the toolbar's own corner is nearly square, so a
 * rounded square inside it read as a mismatched chip. Only the highlight has a visible shape, so
 * the circle keeps every state the same size and the row visually even.
 */
@Composable internal fun WritingFollowControl(
    icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()
    ) {
        IconButton(
            onClick, modifier = Modifier.size(40.dp), enabled = enabled,
            shape = CircleShape,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current
            )
        ) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}
