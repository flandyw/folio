@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Grading
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.OutlinedFlag
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Everything for marking an exam or giving feedback on a long response, in one sheet: reusable
 * comments, mark stamps, numbered flags that point at the feedback sheet, room to write between
 * lines, and a running total of the marks awarded.
 *
 * Comments and marks arm a tap-to-place mode (or drop straight into free space when [autoPlace] is
 * on); the sheet itself never edits a page, it only reports what the marker chose.
 */
@Composable internal fun MarkingPanel(
    note: Notebook,
    page: NotePage,
    color: Int, onColor: (Int) -> Unit,
    bank: List<String>, onBank: (List<String>) -> Unit,
    markAssist: Boolean, onMarkAssist: (Boolean) -> Unit, zoneCount: Int, zoneTotal: Int,
    scanStatus: String?, scanComplete: Boolean,
    autoPlace: Boolean, onAutoPlace: (Boolean) -> Unit,
    onArm: (MarkingAction) -> Unit,
    onPlaceNow: (MarkingAction) -> Boolean,
    loadPages: suspend () -> List<NotePage>,
    onOpenSheet: ((Int) -> Unit),
    onRecord: (ExamAttempt) -> Unit,
    onFeedbackActions: () -> Unit,
    onDismiss: () -> Unit
) {
    var removingSpace by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val sheetIndex = remember(note.pages) { Marking.findSheet(note.pages)?.let { sheet -> note.pages.indexOfFirst { it.id == sheet.id } } ?: -1 }
    val canGrow = page.infinite || page.pdfIndex == null

    /** Places straight away when asked to, falling back to tap-to-place if there is no clear spot. */
    fun use(action: MarkingAction) {
        if (autoPlace && action !is MarkingAction.Flag && action !is MarkingAction.MakeRoom) {
            if (onPlaceNow(action)) { notice = "Placed in free space — drag it where it belongs."; return }
            notice = "No clear spot on this page, so tap where it should go instead."
        }
        onArm(action)
    }

    FolioPanel(title = "Marking & feedback", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                Text("Marking ink", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Marking.COLORS.forEach { swatch ->
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Color(swatch))
                            .clickable { onColor(swatch) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (swatch == color) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Place in free space", style = MaterialTheme.typography.bodyMedium)
                    Text("Drops comments and marks into the clearest margin instead of waiting for a tap.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(autoPlace, onAutoPlace)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tick printed marks", style = MaterialTheme.typography.bodyMedium)
                    Text(if (!markAssist) "Off. Turn on to tap or hover a printed “[4 marks]” and award it."
                    else if (scanStatus != null) scanStatus
                    else if (zoneCount == 0) "No printed mark allocations found. Faint or handwritten labels may need manual stamps."
                    else "On. $zoneCount allocations found ($zoneTotal marks). Tap or hover one: tick awards it in full, cross lets you adjust.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(markAssist, onMarkAssist)
            }
            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            FilledTonalButton(onFeedbackActions, modifier = Modifier.fillMaxWidth()) {
                Text("Feedback actions · ${note.feedbackActions.count { !it.done }} open")
            }
            Text("Turn a page comment into a paragraph rewrite, question retry or planning task.", style = MaterialTheme.typography.bodySmall)

            SectionLabel("Note at a point")
            Text("Tap the exact spot in the response. A box opens in the nearest clear space, tied back to it with a line.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Button({ onArm(MarkingAction.Note(handwritten = true)) }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Draw, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Handwritten")
                }
                FilledTonalButton({ onArm(MarkingAction.Note(handwritten = false)) }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.TextFields, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Typed")
                }
            }

            SectionLabel("Marks — tap, then tap the page; tap more to keep stamping")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Marking.MARK_LABELS.forEach { label ->
                    FilledTonalButton({ use(MarkingAction.Mark(label)) }, shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.filledTonalButtonColors(contentColor = Color(color))) { Text(label, style = MaterialTheme.typography.titleMedium) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Quick comments", Modifier.weight(1f))
                TextButton({ editing = !editing }, shapes = ButtonDefaults.shapes()) { Text(if (editing) "Done" else "Edit") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                bank.forEach { text ->
                    if (editing) InputChip(false, { onBank(bank - text) }, { Text(text) },
                        trailingIcon = { Icon(Icons.Rounded.Close, "Remove “$text”", Modifier.size(16.dp)) })
                    else AssistChip({ use(MarkingAction.Comment(text)) }, { Text(text) })
                }
                if (bank.isEmpty()) Text("No saved comments yet — type one below.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                draft, { draft = it }, Modifier.fillMaxWidth(),
                label = { Text("Write a comment") }, placeholder = { Text("e.g. Compare both sources here") },
                singleLine = true, shape = FolioShapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val text = Marking.cleanComment(draft)
                    if (text.isNotEmpty()) { onBank(Marking.withComment(bank, text)); use(MarkingAction.Comment(text)); draft = "" }
                }),
                trailingIcon = {
                    if (draft.isNotBlank()) IconButton({
                        val text = Marking.cleanComment(draft)
                        onBank(Marking.withComment(bank, text)); use(MarkingAction.Comment(text)); draft = ""
                    }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Save and place comment") }
                }
            )

            SectionLabel("Flags and the feedback sheet")
            Text("A flag drops a numbered ring where you tap and adds a matching line to the Feedback page, " +
                "so long feedback gets a whole page instead of a cramped margin.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Button({ onArm(MarkingAction.Flag) }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.OutlinedFlag, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Flag a spot")
                }
                OutlinedButton({ onOpenSheet(sheetIndex) }, enabled = sheetIndex >= 0, shapes = ButtonDefaults.shapes()) { Text("Open feedback sheet") }
            }

            SectionLabel("Page space")
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                FilterChip(!removingSpace, { removingSpace = false }, { Text("Make room") })
                FilterChip(removingSpace, { removingSpace = true }, { Text("Remove space") })
            }
            Text(if (canGrow && removingSpace) "Tap the top of an empty gap to close it and shorten the page. Content below moves up; occupied gaps are kept."
            else if (canGrow) "Tap a line on the page: everything below it moves down so there is space to write."
            else "An imported PDF page can't grow. Use margin comments or the feedback sheet here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Marking.ROOM_AMOUNTS.forEach { (label, amount) ->
                    FilledTonalButton({ onArm(if (removingSpace) MarkingAction.RemoveRoom(amount) else MarkingAction.MakeRoom(amount)) }, enabled = canGrow, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp6)); Text(label)
                    }
                }
            }

            val responseAttempt = note.longResponse?.attemptFor(page.id)
            val tallyNote = if (responseAttempt == null) note else note.copy(
                pages = note.pages.filter { it.id in responseAttempt.pageIds },
                exam = note.exam.copy(marksTotal = note.longResponse?.marks ?: note.exam.marksTotal))
            if (responseAttempt != null) Text("Marks for ${responseAttempt.title}", style = MaterialTheme.typography.labelLarge)
            MarksTally(tallyNote, zoneTotal.takeIf { it > 0 && scanComplete },
                { loadPages().filter { responseAttempt == null || it.id in responseAttempt.pageIds } }, onRecord,
                responseAttempt != null)
        }
    }
}

@Composable private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = FolioSpacing.dp4), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Totals every "+2", tick and ½ in the notebook, and can file the result as a marked attempt. */
@Composable private fun MarksTally(note: Notebook, printedTotal: Int?, loadPages: suspend () -> List<NotePage>, onRecord: (ExamAttempt) -> Unit, responseAttempt: Boolean = false) {
    var pages by remember { mutableStateOf<List<NotePage>?>(null) }
    var recorded by remember { mutableStateOf(false) }
    LaunchedEffect(note.id, note.pages.size) { pages = loadPages() }
    val rows = remember(pages) { pages?.let(Marking::tally).orEmpty() }
    val total = rows.sumOf { it.marks.toDouble() }.toFloat()
    val unread = pages?.count { !it.loaded } ?: 0
    SectionLabel("Marks so far")
    when {
        pages == null -> Text("Adding up…", style = MaterialTheme.typography.bodySmall)
        rows.isEmpty() -> Text("Stamp marks like +2 or ✓ on the paper and they are totalled here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
            Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                // The total typed into exam details wins; otherwise the paper's own printed allocations.
                val available = note.exam.marksTotal ?: printedTotal
                Text(Marking.format(total) + (available?.let { " of $it" } ?: "") + " marks",
                    style = MaterialTheme.typography.titleLarge)
                rows.forEach { row ->
                    Row {
                        Text("${row.title} · ${row.items} ${if (row.items == 1) "mark" else "marks"} stamped", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        Text(Marking.format(row.marks), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (unread > 0) Text("$unread ${if (unread == 1) "page" else "pages"} couldn't be read, so this total may be short.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (total != total.roundToInt().toFloat()) Text("Recording rounds ${Marking.format(total)} to ${total.roundToInt()}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton({
                    onRecord(ExamAttempt(score = total.roundToInt(), total = available)); recorded = true
                }, enabled = !recorded && unread == 0, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Rounded.Grading, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8))
                    Text(if (recorded) "Recorded" else if (responseAttempt) "Record this attempt’s mark" else "Record as this paper's mark")
                }
            }
        }
    }
}

/**
 * The marking dock: a compact bar that stays over the page while marking, so stamping a paper is
 * one tap per mark instead of a sheet opened and closed each time. The armed tool stays lit, comments
 * scroll sideways, and the running total updates as marks go on. The full sheet is one tap away
 * ([onMore]) for the comment bank, printed-mark settings, page space and recording the result.
 */
@Composable internal fun MarkingDock(
    armed: MarkingAction?, color: Int, onColor: (Int) -> Unit,
    bank: List<String>, total: Float?, available: Int?,
    onArm: (MarkingAction?) -> Unit, onMore: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = Color(color)
    val litColor = MaterialTheme.colorScheme.secondaryContainer
    fun lit(match: Boolean) = if (match) litColor else Color.Transparent
    fun toggle(action: MarkingAction) = onArm(if (armed == action) null else action)
    Surface(
        modifier.widthIn(max = 720.dp).padding(horizontal = FolioSpacing.dp8),
        shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(vertical = FolioSpacing.dp6), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Row(Modifier.padding(horizontal = FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text(when (armed) {
                    null -> "Pick a mark or comment, then tap the page"
                    is MarkingAction.Comment -> "Tap to place “${armed.text.take(28)}” — keep tapping for more"
                    is MarkingAction.Mark -> "Tap to stamp ${armed.label} — keep tapping for more"
                    is MarkingAction.Note -> "Tap the spot this feedback is about"
                    MarkingAction.Flag -> "Tap to drop a numbered flag"
                    is MarkingAction.MakeRoom -> "Tap the line to open space below it"
                    is MarkingAction.RemoveRoom -> "Tap the top of an empty gap to remove it"
                }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = if (armed == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 1)
                if (total != null) Text(Marking.format(total) + (available?.let { " / $it" } ?: "") + " marks",
                    style = MaterialTheme.typography.labelLarge)
                IconButton(onMore, Modifier.size(32.dp)) { Icon(Icons.Rounded.Tune, "More marking options", Modifier.size(20.dp)) }
                IconButton(onClose, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Close marking bar", Modifier.size(20.dp)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp12),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(tint).clickable {
                    val next = Marking.COLORS.indexOf(color).let { Marking.COLORS[(it + 1) % Marking.COLORS.size] }
                    onColor(next)
                }.semantics { contentDescription = "Marking ink colour ${Marking.colorName(color)}, tap to change" })
                Marking.MARK_LABELS.forEach { label ->
                    val action = MarkingAction.Mark(label)
                    Box(Modifier.height(36.dp).widthIn(min = 40.dp).clip(FolioShapes.medium).background(lit(armed == action))
                        .clickable { toggle(action) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = tint)
                    }
                }
                VerticalDivider(Modifier.height(24.dp))
                DockButton(Icons.Rounded.Draw, "Note", armed == MarkingAction.Note(true)) { toggle(MarkingAction.Note(true)) }
                DockButton(Icons.Rounded.TextFields, "Typed", armed == MarkingAction.Note(false)) { toggle(MarkingAction.Note(false)) }
                DockButton(Icons.Rounded.OutlinedFlag, "Flag", armed == MarkingAction.Flag) { toggle(MarkingAction.Flag) }
                VerticalDivider(Modifier.height(24.dp))
                bank.forEach { text ->
                    val action = MarkingAction.Comment(text)
                    AssistChip({ toggle(action) }, { Text(text, maxLines = 1) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = lit(armed == action)))
                }
            }
        }
    }
}

@Composable private fun DockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, lit: Boolean, onClick: () -> Unit) {
    Row(Modifier.height(36.dp).clip(FolioShapes.medium)
        .background(if (lit) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
        Icon(icon, null, Modifier.size(18.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
