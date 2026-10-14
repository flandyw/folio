package com.folio.notes

/** Positional replacement preserves overlap order, page content and note-local coordinates. */
internal object ShapeSelectionCleanup {
    data class Plan(val content: PageContent, val selection: CanvasSelection, val changed: Int)

    fun plan(page: NotePage, selected: CanvasSelection, options: ShapeAssistOptions): Plan {
        val picked = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(selected.strokes) }
        val replacements = java.util.IdentityHashMap<Stroke, List<Stroke>>()
        var changed = 0
        val strokes = page.strokes.flatMap { stroke ->
            if (stroke !in picked || !PageLayers.editable(page.layers, stroke.layer)) listOf(stroke)
            else {
                val result = ShapeCleanup.strokes(listOf(stroke), options)
                changed += result.changed
                replacements[stroke] = result.strokes
                result.strokes
            }
        }
        val textIds = selected.texts.mapTo(HashSet()) { it.id }
        val texts = page.texts.map { box ->
            if (box.id !in textIds || !PageLayers.editable(page.layers, box.layer)) box
            else {
                val result = ShapeCleanup.strokes(box.stickyInk, options)
                changed += result.changed
                if (result.changed == 0) box else box.copy(stickyInk = result.strokes)
            }
        }
        val cleanSelection = selected.copy(strokes = selected.strokes.flatMap { replacements[it] ?: listOf(it) },
            texts = texts.filter { it.id in textIds })
        return Plan(page.content().copy(strokes = strokes, texts = texts), cleanSelection, changed)
    }
}
