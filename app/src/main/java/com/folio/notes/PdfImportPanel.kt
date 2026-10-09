@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One review surface; edits belong to the view model while the remaining files are inspected. */
@Composable
internal fun PdfImportDialog(
    state: FolioState,
    onDismiss: () -> Unit,
    onUpdate: (PendingPdfImport) -> Unit,
    onRemove: (Uri) -> Unit,
    onImport: (String?, List<PendingPdfImport>) -> Unit
) {
    val files = state.pendingPdfImports
    if (files.isEmpty()) return
    var destination by rememberSaveable { mutableStateOf(state.folderId) }
    var selectedUri by rememberSaveable { mutableStateOf(files.first().uri.toString()) }
    val selected = files.firstOrNull { it.uri.toString() == selectedUri } ?: files.first()
    val folder = destination?.takeIf { id -> state.folders.any { it.id == id } }
    val remaining = files.count { !it.detected }
    val valid = files.all { it.detected && it.inspectionError == null && it.title.isNotBlank() &&
        (it.exam.year == null || it.exam.year in 1900..2099) &&
        (it.exam.marksTotal == null || it.exam.marksTotal in 1..300) }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 700.dp
            Surface(Modifier.widthIn(max = 880.dp).fillMaxWidth().height(maxHeight.coerceAtMost(760.dp)).guardUiTouches(),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(Modifier.size(48.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FileDownload, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Import PDFs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                            Text(if (files.size == 1) "Review your document" else "${files.size} documents · review and import together",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onDismiss, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Cancel import") }
                    }
                    ImportDestination(folder, state.folders, { destination = it }, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                    if (wide && files.size > 1) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(Modifier.width(264.dp).fillMaxHeight(), contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(files, key = { it.uri.toString() }) { file ->
                                    ImportFileTile(file, file.uri == selected.uri, { selectedUri = file.uri.toString() })
                                }
                            }
                            VerticalDivider()
                            ImportDetails(selected, onUpdate, { onRemove(selected.uri) }, Modifier.weight(1f))
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            if (files.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(files, key = { it.uri.toString() }) { file ->
                                    ImportFileTile(file, file.uri == selected.uri, { selectedUri = file.uri.toString() }, Modifier.width(220.dp))
                                }
                            }
                            ImportDetails(selected, onUpdate, { onRemove(selected.uri) }, Modifier.weight(1f))
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(when {
                                remaining > 0 -> "Reading ${files.size - remaining + 1} of ${files.size}…"
                                files.any { it.inspectionError != null } -> "Remove unreadable files to continue"
                                !valid -> "Check the highlighted details"
                                else -> "${files.size} PDF${if (files.size == 1) "" else "s"} ready"
                            }, style = MaterialTheme.typography.labelLarge)
                            Text("Original files stay where they are", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button({ onImport(folder, files) }, enabled = valid, shapes = ButtonDefaults.shapes(),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
                            Icon(Icons.Rounded.FileDownload, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (files.size == 1) "Import" else "Import ${files.size}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportDestination(folder: String?, folders: List<Folder>, onSelect: (String?) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface({ expanded = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Save to", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(LibraryFolders.label(folders, folder).ifBlank { "Library · no folder" },
                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ExpandMore, "Choose destination")
            }
        }
        FolioMenuPopover(expanded, { expanded = false }, Modifier.heightIn(max = 320.dp).guardUiTouches(), title = "Save to") {
            (listOf(null to "Library · no folder") + folders.map { it.id to LibraryFolders.label(folders, it.id) }).forEach { (id, name) ->
                FolioMenuItem({ Text(name) }, { onSelect(id); expanded = false },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null) }, selected = folder == id)
            }
        }
    }
}

@Composable
private fun ImportFileTile(item: PendingPdfImport, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(headlineContent = { Text(if (item.detected) item.title else "Reading PDF…", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(when {
                item.inspectionError != null -> "Couldn’t read this file"
                !item.detected -> "Finding document details"
                item.plainPdf || !item.exam.isTagged -> "PDF document"
                else -> item.exam.summaryLine()
            }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                if (!item.detected) LoadingIndicator(Modifier.size(28.dp))
                else Icon(if (item.inspectionError != null) Icons.Rounded.ErrorOutline else Icons.Rounded.PictureAsPdf, null)
            }, colors = ListItemDefaults.colors(containerColor = Color.Transparent,
                headlineColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
private fun ImportDetails(item: PendingPdfImport, onUpdate: (PendingPdfImport) -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    var editDetails by rememberSaveable(item.uri.toString()) { mutableStateOf(false) }
    var showEvidence by rememberSaveable(item.uri.toString()) { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxHeight(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.filename.ifBlank { "Selected PDF" }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.pageCount?.let { Text("$it page${if (it == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onRemove, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove this PDF from import") }
            }
        }
        if (!item.detected) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ContainedLoadingIndicator(Modifier.size(56.dp))
                    Column {
                        Text("Finding the details", style = MaterialTheme.typography.titleMedium)
                        Text("Reading the cover and checking scanned pages…", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (item.inspectionError != null) {
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn’t read this PDF", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(item.inspectionError, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onRemove, shapes = ButtonDefaults.shapes()) { Text("Remove from import") }
                    }
                }
            }
        } else {
            item {
                OutlinedTextField(item.title, { onUpdate(item.copy(title = it.take(120), useSmartTitle = false)) },
                    Modifier.fillMaxWidth(), label = { Text("Notebook name") }, singleLine = true,
                    isError = item.title.isBlank(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.large)
                if (!item.useSmartTitle) TextButton({ onUpdate(item.copy(useSmartTitle = true).withExam(item.exam)) }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Use suggested name")
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    listOf(false to "Exam details", true to "Plain PDF").forEachIndexed { index, (plain, label) ->
                        ToggleButton(item.plainPdf == plain, { onUpdate(item.asPlainPdf(plain)) }, Modifier.weight(1f).semantics { role = Role.RadioButton },
                            shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) }
                    }
                }
            }
            if (!item.plainPdf) item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(when {
                                item.detection.reviewFields.isNotEmpty() -> "Check the suggestions"
                                item.detection.fieldConfidence.isNotEmpty() -> "Details found on the paper"
                                else -> "Add exam details if you need them"
                            }, style = MaterialTheme.typography.titleSmall)
                        }
                        val summary = listOfNotNull(item.exam.subjectLabel.takeIf { it.isNotBlank() }, item.exam.year?.toString(),
                            item.exam.company.takeIf { it.isNotBlank() }, item.exam.type?.label, item.exam.marksTotal?.let { "$it marks" })
                        Text(summary.joinToString(" · ").ifBlank { "No exam details found. You can import this document as it is." },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.detection.reviewFields.isNotEmpty()) Text("Some details are tentative or disagree. Check them before importing.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ editDetails = !editDetails }, shapes = ButtonDefaults.shapes()) {
                            Icon(if (editDetails) Icons.Rounded.ExpandLess else Icons.Rounded.Edit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text(if (editDetails) "Hide details" else "Edit details")
                        }
                        AnimatedVisibility(editDetails) {
                            ImportExamFields(item, onUpdate)
                        }
                        if (item.detection.evidence.isNotEmpty()) {
                            TextButton({ showEvidence = !showEvidence }, shapes = ButtonDefaults.shapes()) {
                                Text(if (showEvidence) "Hide detection sources" else "Why these suggestions?")
                            }
                            AnimatedVisibility(showEvidence) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item.detection.evidence.filter { it.strength >= ExamClassifier.PREFILL_THRESHOLD }
                                        .distinctBy { Triple(it.field, it.value, it.source) }.take(12).forEach {
                                            Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportExamFields(item: PendingPdfImport, onUpdate: (PendingPdfImport) -> Unit) {
    val tags = item.exam
    var subjectInput by rememberSaveable(item.uri.toString()) { mutableStateOf(tags.subjectLabel) }
    fun update(transform: (ExamTags) -> ExamTags) { onUpdate(item.withExam(transform(tags))) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(subjectInput, { value ->
            subjectInput = value.take(60)
            val subject = VceSubject.match(value)
            update { it.copy(subject = subject, subjectText = if (subject == null) value.take(60) else "") }
        }, Modifier.fillMaxWidth(), label = { Text("Subject") }, singleLine = true, shape = MaterialTheme.shapes.large)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(tags.year?.toString().orEmpty(), { value -> update { it.copy(year = value.filter(Char::isDigit).take(4).toIntOrNull()) } },
                Modifier.weight(1f), label = { Text("Year") }, singleLine = true, shape = MaterialTheme.shapes.large,
                isError = tags.year != null && tags.year !in 1900..2099,
                supportingText = if (tags.year != null && tags.year !in 1900..2099) {{ Text("Use a four-digit year") }} else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(tags.marksTotal?.toString().orEmpty(), { value -> update { it.copy(marksTotal = value.filter(Char::isDigit).take(3).toIntOrNull()) } },
                Modifier.weight(1f), label = { Text("Total marks") }, singleLine = true, shape = MaterialTheme.shapes.large,
                isError = tags.marksTotal != null && tags.marksTotal !in 1..300,
                supportingText = if (tags.marksTotal != null && tags.marksTotal !in 1..300) {{ Text("Use 1–300 marks") }} else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(tags.company, { value -> update { it.copy(company = value.take(40)) } }, Modifier.fillMaxWidth(),
            label = { Text("Company / source") }, placeholder = { Text("e.g. VCAA, NEAP") }, singleLine = true, shape = MaterialTheme.shapes.large)
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                Text(tags.type?.label ?: "Assessment type · optional", Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            FolioMenuPopover(expanded, { expanded = false }, modifier = Modifier.guardUiTouches(), title = "Assessment type") {
                (listOf(null) + ExamType.entries).forEach { type ->
                    FolioMenuItem({ Text(type?.label ?: "No assessment type") }, { update { it.copy(type = type) }; expanded = false },
                        selected = type == tags.type)
                }
            }
        }
    }
}
