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
