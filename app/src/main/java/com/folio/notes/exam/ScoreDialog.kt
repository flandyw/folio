@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.exam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folio.notes.input.guardUiTouches
import com.folio.notes.marking.Marking
import com.folio.notes.model.NotePage
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlin.math.roundToInt

/**
 * One mark entry: score out of a total, optional time taken and a timed flag. In the editor, printed mark
 * detection feeds it: the marks already stamped on the paper ([loadPages], totalled by [Marking.tally])
 * prefill the score and the printed allocations ([markZoneTotal]) prefill the total, until either field is
 * typed into. A page that cannot be read is reported rather than counted as zero.
 */
@Composable
fun ScoreDialog(
    total: Int?, defaultSeconds: Int?, onDismiss: () -> Unit,
    markAssist: Boolean = false, markScanBusy: Boolean = false, markScanStatus: String? = null,
    markZoneCount: Int = 0, markZoneTotal: Int = 0, onMarkAssist: (Boolean) -> Unit = {},
    markColor: Int = Marking.DEFAULT_COLOR, onMarkColor: (Int) -> Unit = {},
    showDetection: Boolean = false, loadPages: (suspend () -> List<NotePage>)? = null,
    onRecord: (score: Int, total: Int?, seconds: Int?, timed: Boolean) -> Unit,
    /** Records the mark, then asks the caller to confirm and save it to Focal. Null hides the button. */
    onRecordAndSave: ((score: Int, total: Int?, seconds: Int?, timed: Boolean) -> Unit)? = null
) {
    var score by rememberSaveable { mutableStateOf("") }
    var scoreEdited by rememberSaveable { mutableStateOf(false) }
    var totalText by rememberSaveable(total) { mutableStateOf(total?.toString() ?: "") }
    var totalEdited by rememberSaveable(total) { mutableStateOf(total != null) }
    var minutes by rememberSaveable { mutableStateOf(defaultSeconds?.let { (it / 60).coerceAtLeast(1) }?.toString() ?: "") }
    var timed by rememberSaveable { mutableStateOf(defaultSeconds != null) }
    var minutesEdited by rememberSaveable { mutableStateOf(false) }
    val seconds = recordedSeconds(timed, minutes, defaultSeconds, minutesEdited)
    val validTime = !timed || minutes.isBlank() || seconds != null

    // Marks stamped so far, re-read whenever detection finishes so a freshly awarded label is counted.
    var stamped by remember { mutableStateOf<Float?>(null) }
    var unread by remember { mutableIntStateOf(0) }
    LaunchedEffect(loadPages, markScanBusy) {
        if (loadPages == null) return@LaunchedEffect
        val pages = loadPages()
        stamped = Marking.tally(pages).sumOf { it.marks.toDouble() }.toFloat()
        unread = pages.count { !it.loaded }
    }
    val scanComplete = markAssist && !markScanBusy && markScanStatus == null
    val printed = markZoneTotal.takeIf { it > 0 && scanComplete }
    val stampedScore = stamped?.takeIf { unread == 0 && it > 0f }?.roundToInt()
    LaunchedEffect(stampedScore) { if (!scoreEdited && stampedScore != null) score = stampedScore.toString() }
    LaunchedEffect(printed) { if (!totalEdited && printed != null) totalText = printed.toString() }

    val parsedScore = score.toIntOrNull()
    val parsedTotal = totalText.toIntOrNull()
    val scoreFocus = remember { FocusRequester() }
    val totalFocus = remember { FocusRequester() }
    val minutesFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { scoreFocus.requestFocus() }
    val scoreTooHigh = parsedScore != null && parsedTotal != null && parsedTotal > 0 && parsedScore > parsedTotal
    val canRecord = parsedScore != null && parsedTotal != null && parsedTotal > 0 && parsedScore in 0..parsedTotal && validTime
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.Grading, null) },
        title = { Text("Record a mark") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                if (showDetection) {
                    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Printed mark detection", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        when {
                                            !markAssist -> "Off. Turn on to tap a printed “[4 marks]” on the page and award it."
                                            markScanBusy -> markScanStatus ?: "Looking for printed marks…"
                                            markScanStatus != null -> markScanStatus
                                            markZoneCount == 0 -> "No printed allocations found. Use manual stamps instead."
                                            else -> "$markZoneCount allocations found, $markZoneTotal marks in all."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (markScanStatus != null && !markScanBusy) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (markScanBusy) CircularProgressIndicator(Modifier.padding(end = FolioSpacing.dp4).size(20.dp), strokeWidth = 2.dp)
                                Switch(markAssist, onMarkAssist)
                            }
                            if (markAssist) {
                                Text("Tick awards the full value, cross lets you adjust. Awards are totalled below.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    Text("Award colour", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Marking.COLORS.forEach { swatch ->
                                        Surface(onClick = { onMarkColor(swatch) }, shape = CircleShape,
                                            color = Color(swatch), modifier = Modifier.size(26.dp),
                                            border = if (swatch == markColor) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null) {}
                                    }
                                }
                            }
                        }
                    }
                    if (loadPages != null && stamped != null) {
                        val s = stamped ?: 0f
                        Text(
                            when {
                                unread > 0 -> "$unread ${if (unread == 1) "page" else "pages"} couldn't be read, so the stamped total may be short."
                                s <= 0f -> "No marks stamped on the paper yet."
                                else -> "Stamped on the paper: ${Marking.format(s)}" + (printed?.let { " of $it printed" } ?: "") + "."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (unread > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                    OutlinedTextField(
                        score, { score = it.filter(Char::isDigit).take(4); scoreEdited = true },
                        Modifier.weight(1f).focusRequester(scoreFocus),
                        label = { Text("Score") }, placeholder = { Text("32") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { totalFocus.requestFocus() }),
                        isError = scoreTooHigh,
                        supportingText = {
                            if (scoreTooHigh) Text("Can't exceed the total.")
                            else if (!scoreEdited && stampedScore != null) Text("From stamped marks")
                            else if (parsedTotal != null && parsedTotal > 0) Text("0–$parsedTotal")
                        }
                    )
                    OutlinedTextField(
                        totalText, { totalText = it.filter(Char::isDigit).take(4); totalEdited = true },
                        Modifier.weight(1f).focusRequester(totalFocus), label = { Text("Out of") }, placeholder = { Text("40") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (timed) ImeAction.Next else ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onNext = { minutesFocus.requestFocus() },
                            onDone = { if (canRecord) onRecord(parsedScore ?: 0, parsedTotal, seconds, timed) }
                        ),
                        isError = totalText.isNotEmpty() && (parsedTotal == null || parsedTotal <= 0),
                        supportingText = {
                            if (totalText.isNotEmpty() && (parsedTotal == null || parsedTotal <= 0)) Text("Enter a total greater than zero.")
                            else if (total != null) Text("From exam details")
                            else if (!totalEdited && printed != null) Text("From printed marks")
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Timed sitting", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (defaultSeconds != null) "Timer measured ${durationLabel(defaultSeconds)}."
                            else "Records the time taken against this mark.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(timed, { timed = it })
                }
                if (timed) {
                    OutlinedTextField(
                        minutes, { minutes = it.filter(Char::isDigit).take(3); minutesEdited = true },
                        Modifier.fillMaxWidth().focusRequester(minutesFocus), label = { Text("Minutes taken (optional)") }, placeholder = { Text("82") },
                        isError = !validTime,
                        supportingText = { if (!validTime) Text("Enter 1–999 minutes.") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (canRecord) onRecord(parsedScore ?: 0, parsedTotal, seconds, timed)
                        })
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                        listOf("60", "90", "120").forEach { suggestion ->
                            FilterChip(minutes == suggestion, { minutes = suggestion; minutesEdited = true }, { Text("${suggestion}m") })
                        }
                    }
                }
                if (parsedScore != null && parsedTotal != null && parsedTotal > 0 && parsedScore in 0..parsedTotal) {
                    Text(
                        "${(parsedScore.toFloat() / parsedTotal * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        dismissButton = { TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                if (onRecordAndSave != null) OutlinedButton(
                    { onRecordAndSave(parsedScore ?: 0, parsedTotal, seconds, timed) },
                    enabled = canRecord,
                    shapes = ButtonDefaults.shapes()
                ) { Text("Record and save to Focal") }
                Button(
                    { onRecord(parsedScore ?: 0, parsedTotal, seconds, timed) },
                    enabled = canRecord,
                    shapes = ButtonDefaults.shapes()
                ) { Text("Record") }
            }
        }
    )
}

// ---- Progress ---------------------------------------------------------------------------------------
