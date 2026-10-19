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
    val canvasZoom: Float = 1f,
    /** Finite-column anchor; navigation session only, never stored in notebook content. */
    val documentPageId: String? = null,
    val documentPageY: Float? = null
)

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

    /**
     * How far past the resize limits a drag may still travel. The pane keeps shrinking under the
     * finger, so pulling it towards the edge reads as closing it, as in Android's own split screen.
     */
    const val LIVE_MIN = 0.06f
    const val LIVE_MAX = 1f - LIVE_MIN
    /** Released this close to an edge, the squeezed pane closes instead of settling at the limit. */
    const val CLOSE_EDGE = 0.14f

    /** Applies a drag of [deltaPx] over [totalPx] to [fraction], for one axis, while the finger is down. */
    fun dragged(fraction: Float, deltaPx: Float, totalPx: Float, invert: Boolean = false): Float {
        if (!fraction.isFinite()) return EQUAL
        if (!totalPx.isFinite() || totalPx <= 0f || !deltaPx.isFinite()) return fraction.coerceIn(LIVE_MIN, LIVE_MAX)
        val delta = if (invert) -deltaPx else deltaPx
        return (fraction + delta / totalPx).coerceIn(LIVE_MIN, LIVE_MAX)
    }

    /** What letting go of the divider at the editor's share [fraction] does. */
    fun release(fraction: Float): SplitRelease = when {
        !fraction.isFinite() -> SplitRelease.Settle(EQUAL)
        fraction <= CLOSE_EDGE -> SplitRelease.CloseEditor
        fraction >= 1f - CLOSE_EDGE -> SplitRelease.CloseCompanion
        else -> SplitRelease.Settle(snap(fraction))
    }
}

/** The outcome of releasing the split divider. */
sealed interface SplitRelease {
    /** Both panes stay, at this editor share. */
    data class Settle(val fraction: Float) : SplitRelease
    /** The editor's pane was pushed off: the other document takes the whole screen as the editor. */
    data object CloseEditor : SplitRelease
    /** The other pane was pushed off: the editor carries on alone. */
    data object CloseCompanion : SplitRelease
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
            tab.viewport.documentPageId?.let { put("documentPage", it) }
            tab.viewport.documentPageY?.takeIf { it.isFinite() }?.let { put("documentY", it) }
            put("x", tab.viewport.canvasX); put("y", tab.viewport.canvasY); put("scale", tab.viewport.canvasZoom)
        }) }
    }.toString()

    fun decode(raw: String?): List<EditorTab> = runCatching {
        val array = org.json.JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            fun finite(key: String, default: Float) = item.optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() } ?: default
            EditorTab(item.getString("id"), item.getString("notebook"), item.getString("page"), item.optString("title"),
                WorkspaceViewport(finite("zoom", 1f).coerceIn(.1f, 8f), finite("pan", 0f), item.optInt("scroll"),
                    finite("x", 0f), finite("y", 0f), finite("scale", 1f).coerceIn(.1f, 8f),
                    item.optString("documentPage").takeIf { it.isNotBlank() },
                    if (item.has("documentY")) item.optDouble("documentY").toFloat().takeIf(Float::isFinite) else null),
                restoredTool(item.optString("tool")),
                PdfSearchState(query = item.optString("query")))
        }.getOrNull() }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
