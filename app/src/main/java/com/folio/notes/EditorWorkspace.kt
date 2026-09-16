package com.folio.notes

/** Navigation only: page content continues to belong to the repository's notebooks. */
data class EditorTab(
    val id: String,
    val notebookId: String,
    val currentPageId: String,
    val title: String,
    val viewport: WorkspaceViewport = WorkspaceViewport(),
    val tool: Tool = Tool.PEN,
    val search: PdfSearchState = PdfSearchState()
)

data class WorkspaceViewport(
    val zoom: Float = 1f,
    val pan: Float = 0f,
    val scrollOffset: Int = 0,
    val canvasX: Float = 0f,
    val canvasY: Float = 0f,
    val canvasZoom: Float = 1f
)

enum class CompanionMode { SPLIT, REFERENCE }

/**
 * Draggable split proportions, free of Compose types so snapping stays
 * JVM-testable. The editor owns [FolioState.splitFraction]; the divider maps
 * drag distance onto it and settles on a snap point.
 */
object SplitPanes {
    const val MIN_FRACTION = 0.2f
    const val MAX_FRACTION = 0.8f
    const val EQUAL = 0.5f
    /** Roughly 30/70, 50/50 and 70/30 from the editor's side. */
    val SNAPS = listOf(0.3f, 0.5f, 0.7f)
    /** Magnet radius around a snap point on release; outside it the value stands. */
    const val SNAP_THRESHOLD = 0.08f

    fun coerce(fraction: Float): Float =
        if (fraction.isFinite()) fraction.coerceIn(MIN_FRACTION, MAX_FRACTION) else EQUAL

    /** Where a released drag settles: the nearest snap within threshold, else the value itself. */
    fun snap(fraction: Float): Float {
        val value = coerce(fraction)
        val nearest = SNAPS.minByOrNull { kotlin.math.abs(it - value) } ?: return value
        return if (kotlin.math.abs(nearest - value) <= SNAP_THRESHOLD) nearest else value
    }

    /** Applies a drag of [deltaPx] over [totalPx] to [fraction], for one axis. */
    fun dragged(fraction: Float, deltaPx: Float, totalPx: Float, invert: Boolean = false): Float {
        if (!totalPx.isFinite() || totalPx <= 0f || !deltaPx.isFinite()) return coerce(fraction)
        val delta = if (invert) -deltaPx else deltaPx
        return coerce(fraction + delta / totalPx)
    }
}

/**
 * Which companion page a linked page turn lands on. Same notebook: the same
 * page. Different notebooks (questions beside solutions): the same index,
 * clamped, so a 12-page paper beside a 4-page solution set never runs off.
 */
fun linkedCompanionTarget(active: Notebook, companionNote: Notebook, newPageIndex: Int): String? {
    if (active.pages.isEmpty() || companionNote.pages.isEmpty()) return null
    val clamped = newPageIndex.coerceIn(0, active.pages.lastIndex)
    if (active.id == companionNote.id) return active.pages[clamped].id
    val index = clamped.coerceIn(0, companionNote.pages.lastIndex)
    return companionNote.pages[index].id
}

/** Stable ordering, even when an existing document is reopened through a deep link. */
fun List<EditorTab>.withTab(tab: EditorTab): List<EditorTab> =
    if (any { it.id == tab.id }) map { if (it.id == tab.id) tab else it } else this + tab

fun List<EditorTab>.adjacentAfterClosing(id: String): EditorTab? {
    val index = indexOfFirst { it.id == id }
    if (index < 0) return null
    val remaining = filterNot { it.id == id }
    return remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))
}

/** Small saved-instance-state payload; never serializes notebook content or search results. */
object WorkspaceSessionCodec {
    fun encode(tabs: List<EditorTab>): String = org.json.JSONArray().apply {
        tabs.forEach { tab -> put(org.json.JSONObject().apply {
            put("id", tab.id); put("notebook", tab.notebookId); put("page", tab.currentPageId)
            put("title", tab.title); put("tool", tab.tool.name); put("query", tab.search.query)
            put("zoom", tab.viewport.zoom); put("pan", tab.viewport.pan); put("scroll", tab.viewport.scrollOffset)
            put("x", tab.viewport.canvasX); put("y", tab.viewport.canvasY); put("scale", tab.viewport.canvasZoom)
        }) }
    }.toString()

    fun decode(raw: String?): List<EditorTab> = runCatching {
        val array = org.json.JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            fun finite(key: String, default: Float) = item.optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() } ?: default
            EditorTab(item.getString("id"), item.getString("notebook"), item.getString("page"), item.optString("title"),
                WorkspaceViewport(finite("zoom", 1f).coerceIn(.1f, 8f), finite("pan", 0f), item.optInt("scroll").coerceAtLeast(0),
                    finite("x", 0f), finite("y", 0f), finite("scale", 1f).coerceIn(.1f, 8f)),
                runCatching { Tool.valueOf(item.optString("tool")) }.getOrDefault(Tool.PEN),
                PdfSearchState(query = item.optString("query")))
        }.getOrNull() }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
