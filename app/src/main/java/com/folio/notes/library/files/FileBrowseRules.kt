package com.folio.notes.library.files

private val fileQueryWhitespace = Regex("[\\s\\p{Z}]+")

internal fun matchesFileName(name: String, query: String): Boolean =
    query.trim().split(fileQueryWhitespace).filter { it.isNotBlank() }.all { name.contains(it, ignoreCase = true) }
