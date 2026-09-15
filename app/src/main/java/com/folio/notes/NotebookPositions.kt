package com.folio.notes

/**
 * Where the user left off in one notebook: which page was open, how it was framed
 * (zoom, pan, list offset, infinite-canvas camera), which tool was in hand and what
 * PDF search was asked. Stored per notebook in SharedPreferences so reopening a
 * notebook — even after the app restarts and its tab is gone — lands back where
 * the user was instead of on the first page.
 */
data class NotebookPosition(
    val pageId: String,
    val viewport: WorkspaceViewport = WorkspaceViewport(),
    val tool: Tool = Tool.PEN,
    val query: String = ""
)

/** Small JSON payload; never serializes notebook content or search results. */
object NotebookPositionCodec {
    fun encode(position: NotebookPosition): String = org.json.JSONObject().apply {
        put("page", position.pageId)
        put("tool", position.tool.name)
        put("query", position.query)
        put("zoom", position.viewport.zoom.toDouble())
        put("pan", position.viewport.pan.toDouble())
        put("scroll", position.viewport.scrollOffset)
        put("x", position.viewport.canvasX.toDouble())
        put("y", position.viewport.canvasY.toDouble())
        put("scale", position.viewport.canvasZoom.toDouble())
    }.toString()

    fun decode(raw: String?): NotebookPosition? = runCatching {
        if (raw.isNullOrBlank()) return null
        val item = org.json.JSONObject(raw)
        val pageId = item.optString("page")
        if (pageId.isBlank()) return null
        fun finite(key: String, default: Float) =
            item.optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() } ?: default
        NotebookPosition(
            pageId = pageId,
            viewport = WorkspaceViewport(
                finite("zoom", 1f).coerceIn(.1f, 8f),
                finite("pan", 0f),
                item.optInt("scroll").coerceAtLeast(0),
                finite("x", 0f),
                finite("y", 0f),
                finite("scale", 1f).coerceIn(.1f, 8f)
            ),
            tool = runCatching { Tool.valueOf(item.optString("tool")) }.getOrDefault(Tool.PEN),
            query = item.optString("query")
        )
    }.getOrNull()
}

/** Maps between the persisted position and the in-memory tab that carries it. */
fun NotebookPosition.toTab(notebookId: String, title: String): EditorTab =
    EditorTab(notebookId, notebookId, pageId, title, viewport, tool, PdfSearchState(query = query))

fun EditorTab.toPosition(): NotebookPosition =
    NotebookPosition(currentPageId, viewport, tool, search.query)
