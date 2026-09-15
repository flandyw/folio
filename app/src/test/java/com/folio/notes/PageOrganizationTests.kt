package com.folio.notes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageOrganizationTests {
    private val pages = listOf(
        NotePage(id = "a", title = "Integration rules", bookmarked = true),
        NotePage(id = "b", title = "Revision", redoFlag = true),
        NotePage(id = "c", title = "Integration practice", bookmarked = true, redoFlag = true)
    )

    @Test fun filtersPreserveOriginalNumbersAndCombineWithSearch() {
        assertEquals(listOf(0, 2), organizePages(pages, filter = PageFilter.BOOKMARKED).map { it.index })
        assertEquals(listOf(2), organizePages(pages, "  INTEGRATION practice ", PageFilter.REDO).map { it.index })
        assertEquals(listOf(2), organizePages(pages, "3", PageFilter.BOOKMARKED).map { it.index })
        assertTrue(organizePages(pages, "missing").isEmpty())
        assertEquals("Page 2", NotePage().displayTitle(1))
    }

    @Test fun namesAndBookmarksSurvivePortableAndLazyStorage() {
        val note = Notebook(title = "Calculus", pages = pages)
        assertEquals(pages, NoteCodec.decode(NoteCodec.encode(note)).pages)
        val summaries = NoteMetaCodec.decode(NoteMetaCodec.encode(note)).pages
        assertEquals(pages.map { it.asSummary() }, summaries)
        val loaded = NotePageCodec.decode(NotePageCodec.encode(pages[0]), summaries[0])
        assertEquals(pages[0], loaded)
    }

    @Test fun oldNotebooksDefaultToUnnamedUnbookmarkedPages() {
        val note = Notebook(title = "Legacy", pages = pages)
        fun withoutFields(raw: String): String = JSONObject(raw).apply {
            val array = getJSONArray("pages")
            for (i in 0 until array.length()) { array.getJSONObject(i).remove("title"); array.getJSONObject(i).remove("bookmarked") }
        }.toString()
        val backup = NoteCodec.decode(withoutFields(NoteCodec.encode(note)))
        val index = NoteMetaCodec.decode(withoutFields(NoteMetaCodec.encode(note)))
        for (page in backup.pages + index.pages) { assertEquals("", page.title); assertFalse(page.bookmarked) }
    }

    @Test fun duplicateAndMoveRetainOrganisationWithIndependentIdentity() {
        val note = Notebook(title = "Calculus", pages = pages)
        val duplicate = note.withDuplicatedPage(0).pages[1]
        assertNotEquals(pages[0].id, duplicate.id)
        assertEquals(pages[0].title, duplicate.title)
        assertTrue(duplicate.bookmarked)
        assertEquals(pages[0], note.withMovedPage(0, 2).pages[2])
    }

    @Test fun inFlightReadKeepsLatestMetadata() {
        val old = pages[0].copy(texts = listOf(TextBox(text = "Notes", x = 0f, y = 0f)))
        val current = old.asSummary().copy(title = "New name", bookmarked = false, redoFlag = true)
        val loaded = current.withLoadedContent(old)
        assertEquals("New name", loaded.title)
        assertFalse(loaded.bookmarked)
        assertTrue(loaded.redoFlag)
        assertTrue(loaded.loaded)
        assertEquals(old.texts, loaded.texts)
    }

    @Test fun librarySearchIncludesPageNamesAndExamTagsAndRespectsFolders() {
        val note = Notebook(id = "a", title = "Calculus", folderId = "study", pages = pages,
            exam = ExamTags(company = "VCAA", year = 2024))
        assertEquals(listOf(note), organizeNotebooks(listOf(note), query = "VCAA 2024 integration"))
        assertTrue(organizeNotebooks(listOf(note), folderId = "other", query = "integration").isEmpty())
        val other = Notebook(id = "b", title = "Zoology")
        assertEquals(listOf(note, other), organizeNotebooks(listOf(other, note), sort = LibrarySort.BOOKMARKS))
        assertEquals(listOf(other, note), organizeNotebooks(listOf(note, other), sort = LibrarySort.NAME_DESC))
    }
}
