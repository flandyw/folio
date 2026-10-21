package com.folio.notes.model

/** User page names and embedded PDF labels refer back to notebook page ids; no index format change. */
internal fun resolvePageLabel(pages: List<NotePage>, pdfLabels: Map<Int, String>, query: String): Int? {
    val text = query.trim()
    if (text.isEmpty()) return null
    val labelOnly = text.startsWith("label:", true)
    val nameOnly = text.startsWith("name:", true)
    val wanted = if (labelOnly || nameOnly) text.substringAfter(':').trim() else text
    if (wanted.isEmpty()) return null
    if (!labelOnly && !nameOnly) text.toIntOrNull()?.takeIf { it in 1..pages.size }?.let { return it - 1 }
    return pages.indices.filter { index -> (!labelOnly && pages[index].title.trim().equals(wanted, true)) || (!nameOnly && pages[index].pdfIndex?.let { pdfLabels[it]?.equals(wanted, true) } == true) }.singleOrNull()
}

