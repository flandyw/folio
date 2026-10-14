package com.folio.notes

/** Positional replacement preserves overlap order, page content and note-local coordinates. */
internal object ShapeSelectionCleanup {
    /** [changed] counts source strokes replaced; [shapes] counts what replaces them. */
    data class Plan(val content: PageContent, val selection: CanvasSelection, val changed: Int, val shapes: Int)

    fun plan(page: NotePage, selected: CanvasSelection, options: ShapeAssistOptions): Plan {
        val picked = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(selected.strokes) }
        // The selected ink is cleaned together, so a shape drawn in several strokes is one shape.
        val chosen = page.strokes.filter { it in picked && PageLayers.editable(page.layers, it.layer) }
        val cleaned = ShapeCleanup.strokes(chosen, options)
        val replacements = java.util.IdentityHashMap<Stroke, List<Stroke>>()
        cleaned.replaced.forEach { (index, shape) -> replacements[chosen[index]] = shape }
        var changed = cleaned.changed
        var shapes = cleaned.shapes
        val strokes = page.strokes.flatMap { replacements[it] ?: listOf(it) }
        val textIds = selected.texts.mapTo(HashSet()) { it.id }
        val texts = page.texts.map { box ->
            if (box.id !in textIds || !PageLayers.editable(page.layers, box.layer)) box
            else {
                val result = ShapeCleanup.strokes(box.stickyInk, options)
                changed += result.changed; shapes += result.shapes
                if (result.changed == 0) box else box.copy(stickyInk = result.strokes)
            }
        }
        val cleanSelection = selected.copy(strokes = selected.strokes.flatMap { replacements[it] ?: listOf(it) },
            texts = texts.filter { it.id in textIds })
        return Plan(page.content().copy(strokes = strokes, texts = texts), cleanSelection, changed, shapes)
    }
}
