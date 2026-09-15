package com.folio.notes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LazyStoreTests {
    private val marked = NotePage(
        width = 840f, height = 1188f, paper = Paper.GRID, revision = 3,
        strokes = listOf(Stroke(Tool.PEN, -16777216, 2.4f, listOf(InkPoint(1f, 2f, .5f), InkPoint(30f, 40f, 1f)))),
        texts = listOf(TextBox(x = 10f, y = 20f, text = "V = IR", size = 30f, bold = true))
    )
    private val imported = NotePage(width = 600f, height = 800f, paper = Paper.PLAIN, pdfIndex = 4, revision = 1)
    private val note = Notebook(id = "note-1", title = "Exam practice", folderId = "folder-1", cover = 2,
        starred = true, updated = 1_700_000_000_000, pages = listOf(marked, imported))

    @Test fun theIndexCarriesEveryPageButNoneOfTheirInk() {
        val encoded = NoteMetaCodec.encode(note)
        assertTrue(NoteMetaCodec.isCurrent(encoded))
        // The whole point of the split: listing a library must never drag ink off the disk.
        assertFalse(encoded.contains("strokes"))
        assertFalse(encoded.contains("points"))
        assertFalse(encoded.contains("texts"))
        assertFalse(encoded.contains("V = IR"))

        val index = NoteMetaCodec.decode(encoded)
        assertEquals(note.id, index.id)
        assertEquals(note.title, index.title)
        assertEquals(note.folderId, index.folderId)
        assertEquals(note.cover, index.cover)
        assertEquals(note.starred, index.starred)
        assertEquals(note.updated, index.updated)
        assertEquals(2, index.pages.size)
        assertEquals(listOf(marked.id, imported.id), index.pages.map { it.id })
        assertTrue(index.pages.none { it.loaded })
        assertTrue(index.pages.all { it.strokes.isEmpty() && it.texts.isEmpty() })
        // Size, paper, PDF reference and revision are part of the shape, so they survive the split.
        assertEquals(marked.width, index.pages[0].width, .001f)
        assertEquals(Paper.GRID, index.pages[0].paper)
        assertEquals(3, index.pages[0].revision)
        assertEquals(4, index.pages[1].pdfIndex)
        assertEquals(1, index.pages[1].revision)
    }

    @Test fun aPageSurvivesItsOwnFileAndTakesItsRevisionFromTheIndex() {
        val summary = marked.asSummary().copy(revision = 9)
        val restored = NotePageCodec.decode(NotePageCodec.encode(marked), summary)
        assertEquals(marked.strokes, restored.strokes)
        assertEquals(marked.texts, restored.texts)
        // The summary is authoritative for everything outside the page's own content.
        assertEquals(9, restored.revision)
        assertEquals(marked.width, restored.width, .001f)
        assertEquals(Paper.GRID, restored.paper)
        assertTrue(restored.loaded)
    }

    @Test fun aPageFileWithoutTextStillReadsAsAnInkOnlyPage() {
        val json = JSONObject(NotePageCodec.encode(marked))
        json.remove("texts")
        val restored = NotePageCodec.decode(json.toString(), marked.asSummary())
        assertEquals(marked.strokes, restored.strokes)
        assertTrue(restored.texts.isEmpty())
    }

    @Test fun splittingAnOldNotebookAndRebuildingItReturnsExactlyWhatWasThere() {
        // This is the migration: the version-1 file is split into an index plus one file per page.
        val index = NoteMetaCodec.encode(note)
        val pageFiles = note.pages.associate { it.id to NotePageCodec.encode(it) }

        val rebuilt = NoteMetaCodec.decode(index).let { meta ->
            meta.copy(pages = meta.pages.map { NotePageCodec.decode(pageFiles.getValue(it.id), it) })
        }
        assertEquals(note, rebuilt)
        assertTrue(rebuilt.pages.all { it.loaded })
    }

    @Test fun anUnknownIndexVersionIsRejectedRatherThanHalfRead() {
        val future = JSONObject(NoteMetaCodec.encode(note)).apply { put("version", 99) }.toString()
        assertFalse(NoteMetaCodec.isCurrent(future))
        assertFalse(NoteMetaCodec.isSplitIndex(future))
        assertFalse(NoteMetaCodec.isVersion3(future))
        assertFalse(NoteMetaCodec.isVersion4(future))
        assertThrows(IllegalArgumentException::class.java) { NoteMetaCodec.decode(future) }
        assertThrows(IllegalArgumentException::class.java) { NotePageCodec.decode("{\"version\":99}", marked.asSummary()) }
    }

    @Test fun theIndexCarriesExamTagsSetLinkAttemptsAndRedoFlagsWithoutInk() {
        val examNote = note.copy(
            exam = ExamTags(subject = VceSubject.MATHS_METHODS, year = 2022, company = "VCAA"),
            setId = "set-1",
            attempts = listOf(ExamAttempt(id = "a1", score = 31, total = 40, secondsTaken = 4800, timed = true)),
            pages = note.pages.map { it.copy(redoFlag = it.pdfIndex != null) }
        )
        val encoded = NoteMetaCodec.encode(examNote)
        // Still an index: no ink reaches the file even with exam metadata present.
        assertFalse(encoded.contains("strokes"))
        assertFalse(encoded.contains("points"))
        assertFalse(encoded.contains("V = IR"))
        val decoded = NoteMetaCodec.decode(encoded)
        assertEquals(examNote.exam, decoded.exam)
        assertEquals("set-1", decoded.setId)
        assertEquals(examNote.attempts, decoded.attempts)
        assertEquals(listOf(false, true), decoded.pages.map { it.redoFlag })
    }

    @Test fun aVersion2SplitIndexMigratesWithDefaultExamFields() {
        val v2 = JSONObject(NoteMetaCodec.encode(note)).apply {
            put("version", 2)
            // Strip the exam block, set link and attempts a v2 writer never emitted.
            remove("exam"); remove("set"); remove("attempts"); remove("pageCover")
        }.toString()
        assertTrue(NoteMetaCodec.isSplitIndex(v2))
        assertFalse(NoteMetaCodec.isCurrent(v2))
        val migrated = NoteMetaCodec.decodeSplit(v2)
        assertEquals(note.title, migrated.title)
        assertEquals(ExamTags(), migrated.exam)
        assertNull(migrated.setId)
        assertTrue(migrated.attempts.isEmpty())
        assertEquals(2, migrated.pages.size)
        assertTrue(migrated.pages.none { it.loaded })
        // A v2 file never chose a cover, so it opens on the first page like a new notebook.
        assertTrue(migrated.pageCover)
    }

    @Test fun aVersion3IndexMigratesWithFirstPageCover() {
        val v3 = JSONObject(NoteMetaCodec.encode(note.copy(pageCover = false))).apply {
            put("version", 3)
            remove("pageCover")
        }.toString()
        assertTrue(NoteMetaCodec.isVersion3(v3))
        assertFalse(NoteMetaCodec.isCurrent(v3))
        assertTrue(NoteMetaCodec.decodeVersion3(v3).pageCover)
        // An explicit choice survives the round trip on the current version.
        assertFalse(NoteMetaCodec.decode(NoteMetaCodec.encode(note.copy(pageCover = false))).pageCover)
        assertTrue(NoteMetaCodec.decode(NoteMetaCodec.encode(note.copy(pageCover = true))).pageCover)
    }

    @Test fun thePortableCodecKeepsTheCoverChoice() {
        assertFalse(NoteCodec.decode(NoteCodec.encode(note.copy(pageCover = false))).pageCover)
        assertTrue(NoteCodec.decode(NoteCodec.encode(note.copy(pageCover = true))).pageCover)
        // Backups written before the cover choice open on the first page.
        val legacy = JSONObject(NoteCodec.encode(note)).apply { remove("pageCover") }.toString()
        assertTrue(NoteCodec.decode(legacy).pageCover)
    }

    @Test fun thePortableCodecStillKeepsEveryPageAndItsRevision() {
        val restored = NoteCodec.decode(NoteCodec.encode(note))
        assertEquals(note, restored)
        assertEquals(3, restored.pages[0].revision)
        assertTrue(NoteCodec.decode(NoteCodec.encode(note)).pages.all { it.loaded })
    }
}

class PageShapeTests {
    private val page = NotePage(width = 500f, height = 700f, paper = Paper.RULED, revision = 2,
        strokes = listOf(Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(1f, 1f)))),
        texts = listOf(TextBox(x = 0f, y = 0f, text = "hi")))

    @Test fun aSummaryKeepsTheShapeAndDropsTheContent() {
        val summary = page.asSummary()
        assertEquals(page.id, summary.id)
        assertEquals(page.width, summary.width, .001f)
        assertEquals(page.paper, summary.paper)
        assertEquals(page.revision, summary.revision)
        assertFalse(summary.loaded)
        assertTrue(summary.strokes.isEmpty())
        assertTrue(summary.texts.isEmpty())
    }

    @Test fun revisingABumpsItsRevisionWithoutTouchingTheInk() {
        val revised = page.revised()
        assertEquals(3, revised.revision)
        assertEquals(page.strokes, revised.strokes)
        assertEquals(page.texts, revised.texts)
    }

    @Test fun aLoadedPageTakesItsPlaceInTheNotebook() {
        val second = NotePage(paper = Paper.PLAIN)
        val notebook = Notebook(title = "Two", pages = listOf(page.asSummary(), second))
        val loaded = page.revised().copy(texts = listOf(TextBox(x = 1f, y = 1f, text = "edited")))
        val merged = notebook.withPage(loaded)
        assertEquals(2, merged.pages.size)
        assertEquals(loaded, merged.pages[0])
        assertEquals(second, merged.pages[1])
        // A page that is not in the notebook leaves it untouched.
        assertEquals(notebook, notebook.withPage(NotePage()))
    }
}

class ThumbnailKeyTests {
    @Test fun aPreviewIsNamedAfterItsSizeAndTheRevisionThatProducedIt() {
        assertEquals("page-1-420-4.jpg", ThumbnailKeys.name("page-1", 4, 420))
        assertTrue(ThumbnailKeys.isStale("page-1-420-3.jpg", "page-1", 4, 420))
        // Legacy PNG previews are stale once the cache moves to JPEG.
        assertTrue(ThumbnailKeys.isStale("page-1-420-3.png", "page-1", 4, 420))
        assertFalse(ThumbnailKeys.isStale("page-1-420-4.jpg", "page-1", 4, 420))
        // The library card's large preview and the page browser's small one never prune each other.
        assertFalse(ThumbnailKeys.isStale("page-1-80-4.jpg", "page-1", 4, 420))
        assertFalse(ThumbnailKeys.isStale("page-1-420-4.jpg", "page-1", 4, 80))
        // A different page's preview is not this page's stale one, even with a shared prefix.
        assertFalse(ThumbnailKeys.isStale("page-10-420-1.jpg", "page-1", 4, 420))
    }
}
