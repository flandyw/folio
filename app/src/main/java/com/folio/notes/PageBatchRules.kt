package com.folio.notes

/** Keep selected pages in document order, and preserve the order of everything outside the block. */
internal fun reorderedPageBlock(ids: List<String>, selected: Set<String>, position: Int): List<String> {
    val block = ids.filter { it in selected }
    if (block.isEmpty()) return ids
    val rest = ids.filterNot { it in selected }
    return rest.toMutableList().apply { addAll(position.coerceIn(0, size), block) }
}
