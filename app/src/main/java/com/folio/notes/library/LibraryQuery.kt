package com.folio.notes.library

import com.folio.notes.model.Notebook

private val libraryQueryToken = Regex("""-?"([^"]*)(?:"|$)|[^"\s\p{Z}]+""")
private data class QueryTerm(val text: String, val excluded: Boolean)

/** Require every term and reject any excluded term. A quoted phrase must occur within one field. */
fun matchesQuery(note: Notebook, rawQuery: String): Boolean {
    val terms = libraryQueryToken.findAll(rawQuery).mapNotNull { token ->
        val phrase = token.groups[1]?.value
        val excluded = token.value.startsWith('-')
        val text = phrase?.trim() ?: token.value.removePrefix("-")
        if (text.isBlank()) null else QueryTerm(text.lowercase(), excluded)
    }.toList()
    if (terms.isEmpty()) return true
    val fields = searchableFields(note).map { it.lowercase() }
    if (terms.size == 1) {
        val term = terms.single()
        return fields.any { it.contains(term.text) } != term.excluded
    }
    val haystack = fields.toList()
    return terms.all { term -> haystack.any { it.contains(term.text) } != term.excluded }
}

private fun searchableFields(note: Notebook): Sequence<String> = sequence {
    yield(note.title)
    yieldAll(note.tags)
    note.longResponse?.let { yield(it.prompt); yield(it.topic) }
    yield(note.exam.subjectLabel)
    yield(note.exam.company)
    note.exam.type?.let { yield(it.label) }
    note.exam.tags.forEach { yield(it.label) }
    note.exam.year?.let { yield(it.toString()) }
    note.exam.unit?.let { yield("unit $it") }
    note.pages.forEach { yield(it.title) }
}
