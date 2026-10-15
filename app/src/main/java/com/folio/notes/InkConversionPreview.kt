@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class InkConversionDraft(val noteId: String, val pages: Map<String, Pair<NotePage, PageContent>>)

@Composable internal fun InkConversionPreview(draft: InkConversionDraft, model: FolioViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val text = remember(draft) { mutableStateMapOf<String, String>().apply {
        draft.pages.values.forEach { (before, after) -> after.texts.filter { box -> before.texts.none { it == box } }.forEach { put(it.id, it.text) } }
    } }
    var busy by remember { mutableStateOf(false) }
    FolioPanel("Check recognised handwriting", { if (!busy) onDismiss() }) {
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Compare the original ink, correct the text, then replace it in one undoable action. Cancel keeps the original ink.")
            draft.pages.forEach { (id, pair) ->
                val (before, after) = pair
                var preview by remember(id) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(draft, id) {
                    preview = withContext(Dispatchers.Default) {
                        val strokes = before.strokes.filterNot { it in after.strokes }
                        val texts = before.texts.filter { box -> after.texts.any { it.id == box.id && it != box } }
                        val bounds = InkGeometry.contentBounds(strokes, texts, emptyList(), InkRenderer::textHeight, before.width, before.height)
                        val width = (bounds.width + 24f).coerceAtLeast(1f); val height = (bounds.height + 24f).coerceAtLeast(1f)
                        val scale = minOf(2f, 1000f / width, 1000f / height)
                        Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
                            val canvas = Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE); canvas.scale(scale, scale); canvas.translate(12f - bounds.left, 12f - bounds.top)
                            InkRenderer.page(canvas, before.copy(strokes = strokes, texts = texts, images = emptyList(), paper = Paper.PLAIN), null)
                        }
                    }
                }
                DisposableEffect(id) { onDispose { preview?.recycle() } }
                preview?.let { Image(it.asImageBitmap(), "Original selected handwriting", Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
                after.texts.filter { box -> before.texts.none { it == box } }.forEach { box ->
                    OutlinedTextField(text[box.id].orEmpty(), { text[box.id] = it }, Modifier.fillMaxWidth(), label = { Text("Recognised text") }, minLines = 2)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onDismiss, enabled = !busy) { Text("Keep ink") }
                Button({ busy = true; scope.launch {
                    try {
                        val edits = draft.pages.mapNotNull { (id, pair) ->
                            val (before, after) = pair
                            PageJournal.diff(before.content(), after.copy(texts = after.texts.map { box -> text[box.id]?.let { box.copy(text = it) } ?: box }))?.let { id to it }
                        }.toMap()
                        if (model.commitDocumentEdits(draft.noteId, edits, draft.pages.mapValues { it.value.first.revision })) onDone()
                        else { model.reportError("The document changed. Keep the ink and select it again."); onDismiss() }
                    } finally { busy = false }
                } }, enabled = !busy && text.values.all { it.isNotBlank() }) { Text(if (busy) "Applying…" else "Replace ink with text") }
            }
        }
    }
}
