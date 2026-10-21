@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folio.notes.model.Notebook
import com.folio.notes.ui.EmptyHint
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlin.math.roundToInt

/** Per-subject score roll-up, or the empty state when nothing has been marked. */
@Composable
fun ExamProgressPanel(notes: List<Notebook>, onDismiss: () -> Unit) {
    FolioPanel(title = "Progress", onDismissRequest = onDismiss) {
        ExamProgressContent(notes, Modifier.fillMaxWidth())
    }
}

/**
 * The same roll-up without the dialog wrapper, so the Library's Progress
 * destination can show it inline instead of behind another button. The Library
 * page already scrolls, so its inline use must not add a second vertical
 * scroll — nested scrolling containers crash on measure.
 */
@Composable
fun ExamProgressContent(notes: List<Notebook>, modifier: Modifier = Modifier, scrollEnabled: Boolean = true) {
    val progress = remember(notes) { subjectProgress(notes) }
    Column(
        modifier.fillMaxWidth().then(if (scrollEnabled) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
    ) {
            if (progress.isEmpty()) {
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        "Tag a notebook with a subject and record a mark to see progress here.",
                        Modifier.fillMaxWidth().padding(FolioSpacing.dp16), color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            progress.forEach { row ->
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(subjectColor(row.subject), CircleShape))
                            Spacer(Modifier.width(FolioSpacing.dp8))
                            Text(row.subject?.label ?: "Other subjects", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${row.paperCount} paper${if (row.paperCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (row.averageShare == null) {
                            EmptyHint("No marks recorded yet.")
                        } else {
                            ProgressRow("Average", row.averageShare)
                            row.recentShare?.let { ProgressRow("Recent attempts", it) }
                            row.bestShare?.let { ProgressRow("Best", it) }
                        }
                    }
                }
            }
            Text("Averages come from every recorded attempt; recent attempts average each paper's latest mark.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProgressRow(label: String, share: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${(share * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { share.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(FolioShapes.bar),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

// ---- Timer ---------------------------------------------------------------------------------------------
