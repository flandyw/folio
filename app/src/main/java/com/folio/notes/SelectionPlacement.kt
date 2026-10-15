package com.folio.notes

internal object SelectionPlacement {
    fun apply(page: NotePage, picked: CanvasSelection, action: String, textHeight: (TextBox) -> Float): PageContent {
        val items = picked.strokes.map { CanvasSelection(strokes = listOf(it)) } + picked.texts.map { CanvasSelection(texts = listOf(it)) } + picked.images.map { CanvasSelection(images = listOf(it)) }
        if (items.size < 2) return page.content()
        val bounds = items.map { InkGeometry.contentBounds(it.strokes, it.texts, it.images, textHeight, page.width, page.height) }
        val left = bounds.minOf { it.left }; val right = bounds.maxOf { it.right }; val top = bounds.minOf { it.top }; val bottom = bounds.maxOf { it.bottom }
        val horizontal = action.contains("horizontally")
        val ordered = bounds.indices.sortedBy { if (horizontal) bounds[it].left else bounds[it].top }
        val gap = if (items.size < 3) 0f else ((if (horizontal) right - left else bottom - top) - bounds.sumOf { (if (horizontal) it.width else it.height).toDouble() }.toFloat()) / (items.size - 1)
        var cursor = if (horizontal) left else top
        val offsets = mutableMapOf<Int, Pair<Float, Float>>()
        if (action.startsWith("Distribute")) ordered.forEach { index -> val b = bounds[index]; offsets[index] = if (horizontal) cursor - b.left to 0f else 0f to cursor - b.top; cursor += (if (horizontal) b.width else b.height) + gap }
        else bounds.forEachIndexed { i, b -> offsets[i] = when (action) {
            "Left" -> left - b.left to 0f; "Right" -> right - b.right to 0f; "Top" -> 0f to top - b.top; "Bottom" -> 0f to bottom - b.bottom
            "Centre horizontally" -> (left + right - b.left - b.right) / 2 to 0f
            else -> 0f to (top + bottom - b.top - b.bottom) / 2
        } }
        val moved = items.mapIndexed { i, item -> val (dx, dy) = offsets.getValue(i); CanvasSelection(item.strokes.map { InkGeometry.translate(it, dx, dy) }, item.texts.map { it.moved(dx, dy) }, item.images.map { it.moved(dx, dy) }) }
        val strokes = java.util.IdentityHashMap<Stroke, Stroke>().apply { picked.strokes.zip(moved.flatMap { it.strokes }).forEach { (before, after) -> put(before, after) } }
        val texts = picked.texts.zip(moved.flatMap { it.texts }).associate { it.first.id to it.second }
        val images = picked.images.zip(moved.flatMap { it.images }).associate { it.first.id to it.second }
        return page.content().copy(strokes = page.strokes.map { strokes[it] ?: it }, texts = page.texts.map { texts[it.id] ?: it }, images = page.images.map { images[it.id] ?: it })
    }
}

