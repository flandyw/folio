package com.folio.notes

/** Named pages keep their physical page numbers when the browser is filtered. */
enum class PageFilter(val label: String) { ALL("All pages"), BOOKMARKED("Bookmarks"), REDO("Redo") }

fun NotePage.displayTitle(index: Int): String = title.ifBlank { "Page ${index + 1}" }

fun organizePages(pages: List<NotePage>, query: String = "", filter: PageFilter = PageFilter.ALL): List<IndexedValue<NotePage>> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return pages.withIndex().filter { (index, page) ->
        (filter != PageFilter.BOOKMARKED || page.bookmarked) &&
            (filter != PageFilter.REDO || page.redoFlag) &&
            words.all { word -> "Page ${index + 1} ${page.title}".contains(word, ignoreCase = true) }
    }
}

/** A disk read supplies content only; organisation may have changed while it was in flight. */
fun NotePage.withLoadedContent(content: NotePage): NotePage = copy(
    strokes = content.strokes, texts = content.texts, images = content.images, loaded = true
)
