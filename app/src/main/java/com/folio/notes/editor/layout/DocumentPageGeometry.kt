package com.folio.notes.editor.layout

/** Explicit, Android-free page/document/window conversions. Seams never enter page coordinates. */
internal object DocumentPageGeometry {
    data class Point(val x: Float, val y: Float)
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val valid get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && width > 0f && height > 0f
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }
    data class Frame(val pageId: String, val width: Float, val height: Float, val paper: Bounds, val workspace: Bounds) {
        val valid get() = width.isFinite() && height.isFinite() && width > 0 && height > 0 && paper.valid && workspace.valid
        fun toPage(x: Float, y: Float): Point? = if (!valid || !x.isFinite() || !y.isFinite()) null
            else Point((x - paper.left) / paper.width * width, (y - paper.top) / paper.height * height)
        fun toWindow(x: Float, y: Float): Point? = if (!valid || !x.isFinite() || !y.isFinite()) null
            else Point(paper.left + x / width * paper.width, paper.top + y / height * paper.height)
    }
    data class Anchor(val pageId: String, val localY: Float)
    data class Sheet(val pageId: String, val width: Float, val height: Float, val workspaceTop: Float = 0f,
                     val workspaceBottom: Float = height)
    data class Placement(val sheet: Sheet, val top: Float, val scale: Float) {
        val paperTop get() = top - sheet.workspaceTop * scale
        val bottom get() = top + (sheet.workspaceBottom - sheet.workspaceTop) * scale
    }

    /** Prefix geometry for mixed-size PDFs, including notes extending past the paper. */
    fun layout(sheets: List<Sheet>, paperWidth: Float, seam: Float): List<Placement> {
        require(paperWidth.isFinite() && paperWidth > 0 && seam.isFinite() && seam >= 0)
        var top = 0f
        return sheets.map { sheet ->
            require(sheet.width.isFinite() && sheet.width > 0 && sheet.height.isFinite() && sheet.height > 0 &&
                sheet.workspaceTop.isFinite() && sheet.workspaceBottom.isFinite() && sheet.workspaceBottom > sheet.workspaceTop)
            Placement(sheet, top, paperWidth / sheet.width).also { top = it.bottom + seam }
        }
    }
    fun anchor(placement: Placement, documentY: Float) = Anchor(placement.sheet.pageId,
        (documentY - placement.paperTop) / placement.scale)
    fun restore(placements: List<Placement>, anchor: Anchor): Float? =
        placements.firstOrNull { it.sheet.pageId == anchor.pageId }?.let { it.paperTop + anchor.localY * it.scale }

    /** Built only when visible geometry changes; no document scan during input sampling. */
    class Index(frames: Collection<Frame>) {
        private val ordered = frames.filter { it.valid }.sortedWith(compareBy<Frame> { it.workspace.top }.thenBy { it.pageId })
        fun nearest(y: Float): Frame? {
            if (!y.isFinite() || ordered.isEmpty()) return null
            var low = 0; var high = ordered.size
            while (low < high) { val middle = (low + high) / 2
                if (ordered[middle].workspace.top <= y) low = middle + 1 else high = middle }
            val before = ordered.getOrNull(low - 1)
            val after = ordered.getOrNull(low)
            if (before != null && y < before.workspace.bottom) return before
            if (before == null) return after
            if (after == null) return before
            return if (y - before.workspace.bottom <= after.workspace.top - y) before else after
        }
        fun hit(x: Float, y: Float, excluding: String): Frame? =
            ordered.firstOrNull { it.pageId != excluding && it.paper.contains(x, y) }
    }
}
