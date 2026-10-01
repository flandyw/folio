package com.folio.notes

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

/**
 * Short recency label for a notebook's last-edited moment: Today, Yesterday, "N days ago"
 * within the last week, otherwise "d MMM" ("4 Sep") this year or "d MMM yyyy" ("4 Sep 2024").
 * Future timestamps (clock skew) read as Today. Pure and JVM-testable via [now].
 */
fun libraryLastEditedLabel(updated: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val editedDate = Instant.ofEpochMilli(updated).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    // Calendar days, not 24-hour periods: daylight-saving days can be 23 or 25 hours.
    val days = ChronoUnit.DAYS.between(editedDate, today).coerceAtLeast(0)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        else -> {
            val pattern = if (editedDate.year == today.year) "d MMM" else "d MMM yyyy"
            SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(updated))
        }
    }
}
