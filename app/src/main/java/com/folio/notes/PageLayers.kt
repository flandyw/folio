package com.folio.notes

/**
 * Layer rules for one page, free of Android imports. A page's [NotePage.layers] is bottom first and
 * every ink stroke, text box and picture names the layer it sits on, so this object only ever
 * rewrites that small list and the `layer` field of the items it moves; ink is never re-recorded.
 *
 * A page with no layers has one implicit base layer ([PageLayer.BASE]), which keeps every page
 * written before layers existed — and every page that never uses them — byte-for-byte unchanged.
 * An item that names a layer the page no longer has resolves to the lowest layer instead of
 * vanishing, so a damaged or hand-edited file cannot hide ink.
 */
object PageLayers {
    const val MAX_LAYERS = 12
    const val MAX_NAME = 40

    /** The layer list with the implicit base layer made explicit. */
    fun effective(layers: List<PageLayer>): List<PageLayer> = layers.ifEmpty { listOf(PageLayer.BASE) }

    /** The layer an item stored with [id] really belongs to. */
    fun resolve(layers: List<PageLayer>, id: Int): Int {
        val list = effective(layers)
        return if (list.any { it.id == id }) id else list.first().id
    }

    fun find(layers: List<PageLayer>, id: Int): PageLayer? = effective(layers).firstOrNull { it.id == resolve(layers, id) }

    /** True when ink on [id] may be drawn, erased, selected or moved: shown and not locked. */
    fun editable(layers: List<PageLayer>, id: Int): Boolean =
        layers.isEmpty() || find(layers, id)?.let { it.visible && !it.locked } != false

    /** True when this page needs no layer work at all — the common case, kept off every hot path. */
    private fun plain(layers: List<PageLayer>) = layers.size <= 1 && layers.all { it.visible }

    /** Defaults the active layer to the topmost one that can take ink, or the top layer if none can. */
    fun initialActive(layers: List<PageLayer>): Int {
        val list = effective(layers)
        return (list.lastOrNull { it.visible && !it.locked } ?: list.last()).id
    }

    fun add(layers: List<PageLayer>): List<PageLayer> {
        val base = effective(layers)
        if (base.size >= MAX_LAYERS) return layers
        val id = (base.maxOf { it.id }) + 1
        return base + PageLayer(id, "Layer ${base.size + 1}")
    }

    fun rename(layers: List<PageLayer>, id: Int, name: String): List<PageLayer> =
        effective(layers).map { if (it.id == id) it.copy(name = name.trim().take(MAX_NAME).ifEmpty { it.name }) else it }

    fun update(layers: List<PageLayer>, id: Int, change: (PageLayer) -> PageLayer): List<PageLayer> =
        effective(layers).map { if (it.id == id) change(it) else it }

    /** Moves a layer one step; [up] draws it later, so it lands over the layer above. */
    fun move(layers: List<PageLayer>, id: Int, up: Boolean): List<PageLayer> {
        val list = effective(layers).toMutableList()
        val at = list.indexOfFirst { it.id == id }
        val to = if (up) at + 1 else at - 1
        if (at < 0 || to !in list.indices) return layers
        val item = list.removeAt(at); list.add(to, item)
        return list
    }

    /** Where the items of a removed layer go: the layer beneath it, or the one above when it was the lowest. */
    fun mergeTarget(layers: List<PageLayer>, id: Int): Int? {
        val list = effective(layers)
        val at = list.indexOfFirst { it.id == id }
        if (at < 0 || list.size < 2) return null
        return list[if (at > 0) at - 1 else 1].id
    }

    /** The list without [id]; callers move the layer's items first (see [reassign]). */
    fun remove(layers: List<PageLayer>, id: Int): List<PageLayer> {
        val list = effective(layers).filterNot { it.id == id }
        return if (list.size == 1 && list[0] == PageLayer.BASE) emptyList() else list
    }

    /** Every item on [from] moved to [to]; untouched items keep their instance. */
    fun reassign(content: PageContent, from: Int, to: Int): PageContent {
        val layers = content.layers
        fun onFrom(id: Int) = resolve(layers, id) == from
        return content.copy(
            strokes = content.strokes.map { if (onFrom(it.layer)) it.copy(layer = to) else it },
            texts = content.texts.map { if (onFrom(it.layer)) it.copy(layer = to) else it },
            images = content.images.map { if (onFrom(it.layer)) it.copy(layer = to) else it }
        )
    }

    fun count(content: PageContent, id: Int): Int {
        val layers = content.layers
        return content.strokes.count { resolve(layers, it.layer) == id } +
            content.texts.count { resolve(layers, it.layer) == id } +
            content.images.count { resolve(layers, it.layer) == id }
    }

    /** What to draw: hidden layers dropped and the rest in layer order. Items keep their instances. */
    fun view(page: NotePage): NotePage {
        val layers = page.layers
        if (plain(layers)) return page
        val order = HashMap<Int, Int>().apply { layers.forEachIndexed { i, l -> put(l.id, i) } }
        val shown = layers.filter { it.visible }.map { it.id }.toHashSet()
        fun rank(id: Int) = order[resolve(layers, id)] ?: 0
        fun shown(id: Int) = resolve(layers, id) in shown
        return page.copy(
            strokes = page.strokes.filter { shown(it.layer) }.sortedBy { rank(it.layer) },
            texts = page.texts.filter { shown(it.layer) }.sortedBy { rank(it.layer) },
            images = page.images.filter { shown(it.layer) }.sortedBy { rank(it.layer) },
            // Applied once: a view is already filtered and ordered, and a second pass is a no-op.
            layers = emptyList()
        )
    }

    /** [view] for a bare stroke list — what the live eraser draws while it is still cutting. */
    fun viewStrokes(strokes: List<Stroke>, layers: List<PageLayer>): List<Stroke> =
        if (plain(layers)) strokes else view(NotePage(strokes = strokes, layers = layers)).strokes
}
