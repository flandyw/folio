package com.folio.notes.editor.page

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.input.guardUiTouches

/** The button under the last page. One gesture recognizer owns both actions, so holding never also adds a page. */
@Composable
internal fun AddPageButton(label: String, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = FolioShapes.large,
        modifier = modifier.guardUiTouches().clip(FolioShapes.large).combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClickLabel = "Choose next page paper",
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }
        )
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 40.dp).padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp8),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(FolioSpacing.dp8))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
