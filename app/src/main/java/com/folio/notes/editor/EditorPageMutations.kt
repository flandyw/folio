package com.folio.notes.editor

import com.folio.notes.app.FolioRuntime
import com.folio.notes.model.PageImage
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox

/** A page-bound command port; native drawing knows neither navigation nor feature ViewModels. */
internal interface EditorPageMutations {
    fun strokes(values: List<Stroke>)
    fun appendStroke(before: List<Stroke>, stroke: Stroke, after: List<Stroke>)
    fun withdrawStroke(stroke: Stroke)
    fun penActivity(beginsStroke: Boolean)
    fun content(strokes: List<Stroke>, texts: List<TextBox>, images: List<PageImage>)
    fun texts(values: List<TextBox>)
    fun images(values: List<PageImage>)
}

internal class EnginePageMutations(private val runtime: FolioRuntime, private val pageId: String) : EditorPageMutations {
    override fun strokes(values: List<Stroke>) = runtime.documentEngine.strokes(pageId, values)
    override fun appendStroke(before: List<Stroke>, stroke: Stroke, after: List<Stroke>) = runtime.documentEngine.appendStroke(pageId, before, stroke, after)
    override fun withdrawStroke(stroke: Stroke) = runtime.documentEngine.withdrawStroke(pageId, stroke)
    override fun penActivity(beginsStroke: Boolean) = runtime.examSessionManager.onPenActivity(beginsStroke)
    override fun content(strokes: List<Stroke>, texts: List<TextBox>, images: List<PageImage>) = runtime.documentEngine.updateContent(pageId, strokes, texts, images)
    override fun texts(values: List<TextBox>) = runtime.editorTextController.texts(pageId, values)
    override fun images(values: List<PageImage>) = runtime.editorMediaController.images(pageId, values)
}
