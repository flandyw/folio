@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.ui.EmptyHint
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

/** Chips while there are few subjects (one tap on a tablet), a menu once they'd sprawl. */
@Composable
internal fun FocalSubjectPicker(subjects: List<FocalSubject>, selected: String?, emptyLabel: String = "No subject", onSelect: (String?) -> Unit) {
    if (subjects.size > 8) {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth(), shape = FolioShapes.large) {
                Icon(Icons.Rounded.School, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp10))
                Text(subjects.firstOrNull { it.id == selected }?.name ?: emptyLabel, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, null)
            }
            FolioMenuPopover(open, { open = false }, title = "Subject") {
                FolioMenuItem({ Text(emptyLabel) }, { onSelect(null); open = false }, selected = selected == null)
                subjects.forEach { FolioMenuItem({ Text(it.name) }, { onSelect(it.id); open = false }, selected = selected == it.id) }
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            subjects.forEach { subject ->
                // The subject's colour matches its blocks on the timeline and its dot in history.
                val hue = FocalSubjects.color(subject.id)?.let { Color(it) }
                FilterChip(selected = selected == subject.id, onClick = { onSelect(if (selected == subject.id) null else subject.id) },
                    label = { Text(subject.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (hue != null) { { Box(Modifier.size(8.dp).background(hue, CircleShape)) } } else null)
            }
            if (subjects.isEmpty()) EmptyHint("No subjects yet — sessions are saved without one.")
        }
    }
}

internal val CONFIDENCE_LABELS = listOf("Lost", "Shaky", "Getting there", "Confident", "Nailed it")

@Composable
internal fun ReflectionFields(notes: String, onNotes: (String) -> Unit, confidence: Int, onConfidence: (Int) -> Unit) {
    OutlinedTextField(notes, { onNotes(it.take(500)) }, Modifier.fillMaxWidth(),
        label = { Text("What did you work on? (optional)") }, minLines = 2,
        supportingText = if (notes.length >= 400) { { Text("${notes.length}/500") } } else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
    Text("How confident do you feel? (optional)" + if (confidence > 0) " · ${CONFIDENCE_LABELS[confidence - 1]}" else "",
        style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        (1..5).forEach { score ->
            FilterChip(selected = confidence == score, onClick = { onConfidence(if (confidence == score) 0 else score) },
                label = { Text("$score", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Confidence $score of 5, ${CONFIDENCE_LABELS[score - 1]}" })
        }
    }
}
