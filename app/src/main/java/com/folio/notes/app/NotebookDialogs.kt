@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.folio.notes.exam.ExamTags
import com.folio.notes.exam.NotebookTemplate
import com.folio.notes.input.guardUiTouches
import com.folio.notes.library.CoverPicker
import com.folio.notes.model.Paper
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioSpacing

@Composable fun NameDialog(title: String, subtitle: String, initial: String, action: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var text by rememberSaveable(initial, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val changed = text.text.isNotBlank() && text.text.trim() != initial.trim()
    fun save() { if (changed) submit(text.text.trim()) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Text(subtitle)
            OutlinedTextField(text, { if (it.text.length <= 120) text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true, label = { Text("Name") },
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                trailingIcon = { if (text.text.isNotEmpty()) IconButton({ text = TextFieldValue(""); focusRequester.requestFocus() }) { Icon(Icons.Rounded.Close, "Clear name") } },
                supportingText = { if (text.text.length >= 90) Text("${text.text.length}/120") })
        }
    }, dismissButton = { TextButton(dismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }, confirmButton = { Button({ save() }, enabled = changed, shapes = ButtonDefaults.shapes()) { Text(action) } })
}

@Composable internal fun NewNotebookDialog(onDismiss: () -> Unit, onCreate: (String, Int, Paper, ExamTags, Int, Boolean, Boolean) -> Unit) {
    val context = LocalContext.current
    val dialogPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    var title by rememberSaveable { mutableStateOf("") }
    var cover by rememberSaveable { mutableIntStateOf(AppPrefs.defaultCover(dialogPrefs.getInt(AppPrefs.DEFAULT_COVER, AppPrefs.DEFAULT_COVER_INDEX).takeIf { dialogPrefs.contains(AppPrefs.DEFAULT_COVER) } ?: AppPrefs.DEFAULT_COVER_INDEX)) }
    var paper by rememberSaveable { mutableStateOf(AppPrefs.defaultPaper(dialogPrefs.getString(AppPrefs.DEFAULT_PAPER, null))) }
    var template by rememberSaveable { mutableStateOf<String?>(null) }
    var pageCount by rememberSaveable { mutableIntStateOf(1) }
    var infinite by rememberSaveable { mutableStateOf(false) }
    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }
    var pageCover by rememberSaveable { mutableStateOf(dialogPrefs.getBoolean(AppPrefs.DEFAULT_PAGE_COVER, AppPrefs.DEFAULT_PAGE_COVER_ENABLED)) }
    fun chooseTemplate(item: NotebookTemplate) {
        infinite = false
        template = item.id
        paper = item.paper
        pageCount = item.pages
    }
    fun createNotebook() {
        val chosen = NotebookTemplate.byId(template)
        val name = title.trim().ifBlank { "Notebook · ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))}" }
        onCreate(name, cover, paper, chosen?.tags(null, "") ?: ExamTags(), pageCount, infinite, pageCover)
    }
    FolioPanel(title = "New notebook", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = FolioSpacing.dp24, top = FolioSpacing.dp8, end = FolioSpacing.dp24, bottom = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Text("Choose a starting page and begin writing. You can change the cover and paper later.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Start from", style = MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                FilterChip(template == null && !infinite, { template = null; pageCount = 1; infinite = false }, { Text("Blank") })
                FilterChip(infinite, {
                    template = null; pageCount = 1; infinite = true
                    if (paper == Paper.MC_SHEET) paper = Paper.DOTS
                }, { Text("Infinite canvas") })
                NotebookTemplate.ALL.forEach { item ->
                    FilterChip(template == item.id, { chooseTemplate(item) }, { Text(item.title) })
                }
            }
            if (template == null && !infinite) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pages", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                IconButton({ pageCount = (pageCount - 1).coerceAtLeast(1) }, enabled = pageCount > 1, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Remove, "Fewer pages") }
                Text("$pageCount", Modifier.widthIn(min = 32.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton({ pageCount = (pageCount + 1).coerceAtMost(40) }, enabled = pageCount < 40, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "More pages") }
            }
            if (infinite) Text("An unlimited workspace for handwriting and ideas. Pan in any direction and pinch to zoom.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NotebookTemplate.byId(template)?.let { item -> Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(title, { title = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Name (optional)") }, placeholder = { Text("Name it now or rename it later") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { createNotebook() }))
            TextButton({ appearanceExpanded = !appearanceExpanded }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Palette, null); Spacer(Modifier.width(FolioSpacing.dp8))
                Text(if (appearanceExpanded) "Hide cover options" else "Cover options")
                Icon(if (appearanceExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (appearanceExpanded) {
                CoverPicker(cover, { cover = it }, title)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("First page as cover", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (pageCover) "The shelf shows the first page itself" else "The shelf shows the default cover",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(pageCover, { pageCover = it })
                }
            }
            Text("Paper style", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                // Maths papers first so an exam student sees them without scrolling.
                listOf(Paper.MATH_GRID, Paper.GRAPH, Paper.GRID, Paper.DOTS, Paper.PLAIN, Paper.RULED, Paper.SPLIT_RULED, Paper.MC_SHEET, Paper.TIAN_GRID, Paper.MI_GRID).filter { !infinite || it != Paper.MC_SHEET }.forEach { item ->
                    FilterChip(item == paper, { paper = item }, label = { Text(when (item) {
                        Paper.SPLIT_RULED -> "Split ruled"; Paper.MATH_GRID -> "Maths grid"; Paper.GRAPH -> "Graph"; Paper.MC_SHEET -> "MC sheet"
                        Paper.TIAN_GRID -> "Tian (田字格)"; Paper.MI_GRID -> "Mi (米字格)"
                        else -> item.name.lowercase().replaceFirstChar(Char::uppercase)
                    }) })
                }
            }
            if (paper == Paper.MATH_GRID) Text("Fine 20 px grid — ideal for workings & fractions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paper == Paper.GRAPH) Text("Same grid with centred X/Y axes printed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paper == Paper.MC_SHEET) Text("Exam 2 Section A answer sheet — shade A–E with the pen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paper == Paper.TIAN_GRID) Text("田字格 — one character per square with a dashed cross", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paper == Paper.MI_GRID) Text("米字格 — cross plus diagonals in each square", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button(
                    ::createNotebook,
                    shapes = ButtonDefaults.shapes()
                ) { Text(if (infinite) "Create canvas" else if (pageCount > 1) "Create · $pageCount pages" else "Create notebook"); Spacer(Modifier.width(FolioSpacing.dp8)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
            }
        }
    }
}
