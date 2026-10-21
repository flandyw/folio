@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes.editor.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.EditorPaperState
import com.folio.notes.editor.toolbar.FolioPopover
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.Paper
import com.folio.notes.ui.FolioPopover
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

@Composable internal fun EditorPaperDialog(note: Notebook, page: NotePage, model: FolioViewModel, paper: EditorPaperState) {
    var paperMenu by paper.open
    var nextPaperMenu by paper.next
    var nextPagePaper by paper.nextPaper
    var chosenPaper by paper.chosen
    var savePaperDefault by paper.saveDefault
    if (paperMenu) FolioPopover(if (nextPaperMenu) "Next page paper" else "Change paper", Icons.Rounded.GridOn, onDismiss = { paperMenu = false }, actions = {
        TextButton({ paperMenu = false }) { Text("Cancel") }
        TextButton({
            if (nextPaperMenu) nextPagePaper = chosenPaper else model.setPaper(chosenPaper)
            if (savePaperDefault) model.setDefaultPaper(chosenPaper)
            paperMenu = false
        }, shapes = ButtonDefaults.shapes()) { Text("Apply") }
    }) {
        Column {
            Text(if (nextPaperMenu) "Choose the style for the next blank page." else "Choose the style for this page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = FolioSpacing.dp8))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use as notebook default", style = MaterialTheme.typography.bodyMedium)
                    Text("For new blank pages", style = MaterialTheme.typography.bodySmall)
                }
                Switch(savePaperDefault, { savePaperDefault = it })
            }
            listOf(Paper.MATH_GRID, Paper.GRAPH, Paper.GRID, Paper.DOTS, Paper.PLAIN, Paper.RULED, Paper.SPLIT_RULED, Paper.MC_SHEET, Paper.TIAN_GRID, Paper.MI_GRID).forEach { p ->
                val selectedPaper = chosenPaper == p
                Surface(
                    onClick = { chosenPaper = p },
                    shape = FolioShapes.large,
                    color = if (selectedPaper) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp2)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selectedPaper, { chosenPaper = p })
                        Column(Modifier.padding(start = FolioSpacing.dp8).weight(1f)) {
                            Text(editorPaperLabel(p), style = MaterialTheme.typography.bodyMedium)
                            val hint = when (p) {
                                Paper.SPLIT_RULED -> "Ruled lines with a centre divider"
                                Paper.MATH_GRID -> "Fine 20 px grid, bold every 5"
                                Paper.GRAPH -> "Same grid + centred axes"
                                Paper.MC_SHEET -> "Exam Section A answer sheet, 25 questions A–E"
                                Paper.TIAN_GRID -> "田字格 — one character per square, dashed cross"
                                Paper.MI_GRID -> "米字格 — cross plus diagonals per square"
                                else -> null
                            }
                            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selectedPaper) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

}

internal fun editorPaperLabel(p: Paper): String = when (p) {
    Paper.PLAIN -> "Plain"
    Paper.RULED -> "Ruled"
    Paper.SPLIT_RULED -> "Split ruled"
    Paper.DOTS -> "Dots"
    Paper.GRID -> "Grid"
    Paper.MATH_GRID -> "Maths grid"
    Paper.GRAPH -> "Graph (with axes)"
    Paper.MC_SHEET -> "Multiple choice"
    Paper.TIAN_GRID -> "Tian grid (田字格)"
    Paper.MI_GRID -> "Mi grid (米字格)"
}
