package com.folio.notes

/** Applies [HandwritingTidy] to lasso-selected pen ink as one positional replacement, like shape cleanup. */
internal object HandwritingSelectionTidy {
    /** [moved] counts strokes that slid; [lines] counts lines of writing found. */
    data class Plan(val content: PageContent, val selection: CanvasSelection, val moved: Int, val lines: Int)

    fun plan(page: NotePage, selected: CanvasSelection, options: HandwritingTidy.Options): Plan {
        val picked = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(selected.strokes) }
        // Only handwriting: pen ink on a layer that can be edited. Highlighter, shapes and lines stay put.
        val chosen = page.strokes.filter { it in picked && it.tool == Tool.PEN && PageLayers.editable(page.layers, it.layer) }
        val tidied = HandwritingTidy.tidy(chosen.map { it.points }, options)
        val replacements = java.util.IdentityHashMap<Stroke, Stroke>()
        chosen.forEachIndexed { i, stroke ->
            val points = tidied.strokes[i]
            if (points !== stroke.points) replacements[stroke] = stroke.copy(points = points)
        }
        val strokes = page.strokes.map { replacements[it] ?: it }
        val selection = selected.copy(strokes = selected.strokes.map { replacements[it] ?: it })
        return Plan(page.content().copy(strokes = strokes), selection, replacements.size, tidied.lines)
    }
}
