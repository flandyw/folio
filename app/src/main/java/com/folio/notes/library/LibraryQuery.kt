package com.folio.notes.library

import com.folio.notes.model.Notebook

/** Case-insensitive, all-terms match over notebook/page titles and exam tags. */
private val libraryQueryWhitespace = Regex("[\\s\\p{Z}]+")
fun matchesQuery(note: Notebook, rawQuery: String): Boolean {
    val query = rawQuery.trim().lowercase()
    if (query.isEmpty()) return true
    val terms = query.split(libraryQueryWhitespace).filter { it.isNotBlank() }
    if (terms.isEmpty()) return true
    if (terms.size == 1) return haystackContains(note, terms[0])
    val haystack = buildHaystack(note)
    return terms.all { haystack.contains(it) }
}

private fun buildHaystack(note: Notebook): String = buildString {
    append(note.title.lowercase())
    note.tags.forEach { append(' '); append(it.lowercase()) }
    note.longResponse?.let { append(' '); append(it.prompt.lowercase()); append(' '); append(it.topic.lowercase()) }
    note.pages.forEach { append(' '); append(it.title.lowercase()) }
    append(' '); append(note.exam.subjectLabel.lowercase())
    append(' '); append(note.exam.company.lowercase())
    note.exam.year?.let { append(' '); append(it) }
    note.exam.type?.let { append(' '); append(it.label.lowercase()) }
    note.exam.unit?.let { append(' '); append("unit "); append(it) }
    note.exam.tags.forEach { append(' '); append(it.label.lowercase()) }
}

private fun haystackContains(note: Notebook, term: String): Boolean {
    if (note.title.lowercase().contains(term)) return true
    if (note.tags.any { it.lowercase().contains(term) }) return true
    note.longResponse?.let { if (it.prompt.lowercase().contains(term) || it.topic.lowercase().contains(term)) return true }
    if (note.exam.subjectLabel.lowercase().contains(term)) return true
    if (note.exam.company.lowercase().contains(term)) return true
    note.exam.type?.let { if (it.label.lowercase().contains(term)) return true }
    note.exam.tags.forEach { if (it.label.lowercase().contains(term)) return true }
    note.exam.year?.let { if (it.toString().contains(term)) return true }
    note.exam.unit?.let { if (("unit $it").contains(term)) return true }
    return note.pages.any { it.title.lowercase().contains(term) }
}
