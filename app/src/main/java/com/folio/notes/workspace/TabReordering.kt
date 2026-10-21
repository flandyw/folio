package com.folio.notes.workspace

/** Unanimated slot geometry, in the tab strip's scrollable content coordinates. */
internal data class TabSlotBounds(val left: Float, val width: Float) {
    val middle: Float get() = left + width / 2f
}

/** Neighbours make room only when the held tab crosses their resting midpoints. */
internal fun reorderedTabs(
    order: List<String>,
    held: String,
    middle: Float,
    bounds: Map<String, TabSlotBounds>,
    rightToLeft: Boolean = false,
): List<String> {
    val from = order.indexOf(held)
    if (from < 0 || !middle.isFinite()) return order
    val direction = if (rightToLeft) -1f else 1f
    var to = from
    while (to < order.lastIndex) {
        val next = bounds[order[to + 1]] ?: break
        if ((middle - next.middle) * direction <= 0f) break
        to++
    }
    if (to == from) while (to > 0) {
        val previous = bounds[order[to - 1]] ?: break
        if ((middle - previous.middle) * direction >= 0f) break
        to--
    }
    if (to == from) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}
