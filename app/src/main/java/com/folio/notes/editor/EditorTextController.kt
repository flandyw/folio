package com.folio.notes.editor

import com.folio.notes.app.FolioRuntime
import com.folio.notes.app.FolioRuntime.Companion.CHATGPT_NOTE_WIDTH
import com.folio.notes.chatgpt.ChatGptFeedback
import com.folio.notes.chatgpt.ChatGptReview
import com.folio.notes.editor.sticky.StickyNotes
import com.folio.notes.ink.InkGeometry
import com.folio.notes.ink.InkRenderer
import com.folio.notes.model.PageLayers
import com.folio.notes.model.TextBox
import com.folio.notes.model.replacePage
import kotlinx.coroutines.*
import java.util.UUID

/** Editor commands over the shared document engine; no independent notebook content. */
internal class EditorTextController(private val runtime: FolioRuntime) {
    fun texts(texts: List<TextBox>) {
        with(runtime) {
            val page = _state.value.page ?: return
            texts(page.id, texts)
        }
    }

    internal suspend fun awaitChatGptMath(comments: List<String>): Unit = with(runtime) { comments.forEach {
        InkRenderer.awaitMath(TextBox(x = 0f, y = 0f, width = CHATGPT_NOTE_WIDTH - StickyNotes.PADDING * 2, size = StickyNotes.TEXT_SIZE, text = it))
    } }

    internal fun applyChatGptFeedback(feedback: ChatGptFeedback): Boolean {
        with(runtime) {
            val note = _state.value.active ?: return false
            val page = _state.value.page ?: return false
            if (feedback.applied || note.id != feedback.notebook || page != feedback.page || !page.loaded) return false
            if (feedback.comments.size !in 1..ChatGptReview.MAX_NOTES || feedback.comments.any { it.isBlank() || it.length > 800 }) return false
            val layer = activeLayerOf(page)
            if (!PageLayers.editable(page.layers, layer)) { reportError("Choose an unlocked, visible layer for the feedback."); return false }
            val bounds = InkGeometry.contentBounds(page.strokes, page.texts, page.images, InkRenderer::textHeight, page.width, page.height)
            val width = CHATGPT_NOTE_WIDTH
            var x = maxOf(if (page.infinite) feedback.bounds.right else page.width, bounds.right) + 24f
            val top = if (page.infinite) feedback.bounds.top else 24f
            var y = top
            val bottom = if (page.infinite) Float.MAX_VALUE else page.height - 24f
            val notes = feedback.comments.map { text ->
                val box = TextBox(x = x, y = y, width = width, size = StickyNotes.TEXT_SIZE, text = text, layer = layer)
                val height = (InkRenderer.textLayout(box.copy(width = width - StickyNotes.PADDING * 2)).height + StickyNotes.PADDING * 2)
                if (height > bottom - top) { reportError("This page is too short for these notes."); return false }
                if (y + height > bottom) { x += width + 24f; y = top }
                box.copy(id = "chatgpt-${feedback.request}-${y}-${x}", x = x, y = y, stickyHeight = height).also { y += height + 16f }
            }
            texts(page.id, page.texts + notes)
            return true
        }
    }

    fun texts(pageId: String, texts: List<TextBox>) {
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            if (!page.loaded || page.texts === texts || page.texts == texts) return
            replacePage(page.copy(texts = texts))
        }
    }

    fun addText(box: TextBox) {
        with(runtime) { val page = _state.value.page ?: return; texts(page.id, page.texts + box.copy(layer = activeLayerOf(page))) }
    }

    fun updateText(box: TextBox) {
        with(runtime) { val page = _state.value.page ?: return; texts(page.id, page.texts.map { if (it.id == box.id) box else it }) }
    }

    fun commitText(pageId: String, box: TextBox) {
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            if (!page.loaded || !PageLayers.editable(page.layers, box.layer)) return
            val existing = page.texts.any { it.id == box.id }
            val next = when {
                box.text.isBlank() -> page.texts.filterNot { it.id == box.id }
                existing -> page.texts.map { if (it.id == box.id) box else it }
                else -> page.texts + box
            }
            texts(pageId, next)
        }
    }

    fun removeText(id: String) {
        with(runtime) { val page = _state.value.page ?: return; texts(page.id, page.texts.filterNot { it.id == id }) }
    }

    fun duplicateText(id: String, draft: TextBox? = null) {
        with(runtime) {
            val page = _state.value.page ?: return
            val original = page.texts.find { it.id == id } ?: return
            val source = draft?.takeIf { it.id == id } ?: original
            val copy = source.copy(id = java.util.UUID.randomUUID().toString()).moved(18f, 18f)
            texts(page.id, page.texts + copy)
        }
    }
}
