@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.exam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val compactDateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

internal fun compactDate(time: Long): String = compactDateFormat.get()!!.format(Date(time))

internal fun durationLabel(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "—"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "$hours h ${minutes} min" else if (minutes > 0) "$minutes min" else "${seconds}s"
}

/** The subject's band colour, used by chips and cover badges. */
fun subjectColor(subject: VceSubject?): Color =
    subject?.let { Color(it.color) } ?: Color(0xFF606A60)

/** A small tinted label for a subject, used in filter rows and on cards. */
@Composable
fun SubjectChip(subject: VceSubject?, selected: Boolean, onClick: () -> Unit) {
    val color = subjectColor(subject)
    Surface(
        onClick = onClick,
        shape = FolioShapes.medium,
        color = if (selected) color else color.copy(alpha = .12f),
        contentColor = if (selected) Color.White else color,
        border = if (selected) BorderStroke(2.dp, Color.White.copy(alpha = .6f)) else null
    ) {
        Text(
            subject?.label ?: "Other",
            Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Cover badges: subject, score, year and redo count, kept small enough for a shelf card. */
@Composable
fun ExamBadges(note: Notebook, modifier: Modifier = Modifier, redoCount: Int = 0) {
    val exam = note.exam
    if (!exam.isTagged && note.attempts.isEmpty() && redoCount == 0) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
        exam.subject?.let { subject ->
            BadgePill(subject.label, subjectColor(subject), Color.White)
        }
        note.bestScore?.let { share ->
            val percent = (share * 100).roundToInt()
            val color = when {
                percent >= 80 -> Color(0xFF2F6F4E)
                percent >= 65 -> Color(0xFF8C7326)
                else -> Color(0xFF8C3A2B)
            }
            BadgePill("$percent%", color, Color.White)
        }
        exam.year?.let { BadgePill(it.toString(), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant) }
        exam.company.takeIf { it.isNotBlank() }?.let { BadgePill(it, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant) }
        if (redoCount > 0) BadgePill("$redoCount to redo", Color(0xFF8C3A2B).copy(alpha = .15f), Color(0xFF8C3A2B))
    }
}

@Composable
private fun BadgePill(text: String, background: Color, content: Color) {
    Surface(shape = FolioShapes.small, color = background, contentColor = content) {
        Text(text, Modifier.padding(horizontal = FolioSpacing.dp6, vertical = FolioSpacing.dp2), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

// ---- Exam details editor ---------------------------------------------------------------------------
