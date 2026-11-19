package com.folio.notes.library

import com.folio.notes.storage.TrashedNotebook

internal enum class TrashSort(val label: String) {
    NEWEST("Deleted: newest first"),
    OLDEST("Deleted: oldest first"),
    NAME_ASC("Name: A–Z"),
    NAME_DESC("Name: Z–A")
}

internal fun queryTrash(
    entries: List<TrashedNotebook>,
    query: String = "",
    sort: TrashSort = TrashSort.NEWEST
): List<TrashedNotebook> {
    val matching = entries.filter { it.notebook.title.contains(query.trim(), ignoreCase = true) }
    return when (sort) {
        TrashSort.NEWEST -> matching.sortedByDescending { it.deletedAt }
        TrashSort.OLDEST -> matching.sortedBy { it.deletedAt }
        TrashSort.NAME_ASC -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.notebook.title })
        TrashSort.NAME_DESC -> matching.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.notebook.title })
    }
}
