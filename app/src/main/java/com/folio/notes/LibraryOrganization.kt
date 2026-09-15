package com.folio.notes

import java.util.Locale

enum class LibrarySort(val label: String) {
    RECENT("Last edited"), OLDEST("Oldest edited"), NAME("Name A–Z"), NAME_DESC("Name Z–A"), PAGES("Most pages"), BOOKMARKS("Most bookmarks")
}

enum class LibraryKind(val label: String) { ALL("All types"), NOTEBOOKS("Notebooks"), PDFS("PDFs") }

fun organizeNotebooks(
    notes: List<Notebook>, folderId: String? = null, starred: Boolean = false,
    unfiled: Boolean = false, query: String = "", kind: LibraryKind = LibraryKind.ALL,
    sort: LibrarySort = LibrarySort.RECENT
): List<Notebook> {
    val filtered = notes.filter { note ->
        (folderId == null || note.folderId == folderId) && (!starred || note.starred) &&
            (!unfiled || note.folderId == null) && matchesQuery(note, query) &&
            when (kind) {
                LibraryKind.ALL -> true
                LibraryKind.NOTEBOOKS -> note.pages.none { it.pdfIndex != null }
                LibraryKind.PDFS -> note.pages.any { it.pdfIndex != null }
            }
    }
    val byName = compareBy<Notebook> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    return filtered.sortedWith(when (sort) {
        LibrarySort.RECENT -> compareByDescending<Notebook> { it.updated }.then(byName)
        LibrarySort.OLDEST -> compareBy<Notebook> { it.updated }.then(byName)
        LibrarySort.NAME -> byName
        LibrarySort.NAME_DESC -> byName.reversed()
        LibrarySort.BOOKMARKS -> compareByDescending<Notebook> { it.pages.count { page -> page.bookmarked } }.then(byName)
        LibrarySort.PAGES -> compareByDescending<Notebook> { it.pages.size }.then(byName)
    })
}
