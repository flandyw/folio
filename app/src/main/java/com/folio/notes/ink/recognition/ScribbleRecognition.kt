package com.folio.notes.ink.recognition

import com.folio.notes.document.DocumentToolEdits
import com.folio.notes.model.NotePage
import com.folio.notes.model.Stroke
import com.folio.notes.storage.PageContent
import com.folio.notes.storage.content

/**
 * A candidate is inert until Google's classifier approves it. Geometry only narrows the targets.
 * Hold-to-erase reads [contents] directly: the deliberate end hold is its confirmation.
 */
internal class ScribbleRecognition private constructor(val contents: Map<String, PageContent>) {
    fun confirmed(label: String?): Map<String, PageContent> = if (label == "scribble") contents else emptyMap()

    companion object {
        fun prepare(before: Map<String, NotePage>, paths: Map<String, List<Stroke>>,
            notes: Map<String, Map<String, List<Stroke>>>, sensitivity: Float, held: Boolean = false): ScribbleRecognition? {
            val after = before.mapValues { (id, page) -> DocumentToolEdits.content(page, paths[id].orEmpty(), notes[id].orEmpty(),
                DocumentToolEdits.Options(scribble = true, scribbleSensitivity = sensitivity, scribbleInk = !held)) }
            val removedInk = before.any { (id, page) ->
                val content = after.getValue(id)
                content.strokes.size < page.strokes.size || page.texts.any { box ->
                    content.texts.find { it.id == box.id }?.stickyInk?.size?.let { it < box.stickyInk.size } == true
                }
            }
            return if (removedInk) ScribbleRecognition(after) else null
        }
    }
}

/** Allows the optimistic drawing and its acknowledgement, but rejects later edits/Undo/Redo. */
internal class InkRecognitionSnapshot(page: NotePage, private val maximumRevision: Int = page.revision) {
    private val id = page.id
    private val minimumRevision = page.revision
    private val content = page.content()
    fun matches(page: NotePage?): Boolean = page != null && page.id == id &&
        page.revision in minimumRevision..maximumRevision && page.content() == content
}
