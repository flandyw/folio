package com.folio.notes

import com.folio.notes.app.AppPrefs
import com.folio.notes.editor.EditorKeyBindings
import com.folio.notes.editor.PickedImage
import com.folio.notes.editor.selection.ObjectSnapping
import com.folio.notes.editor.selection.SelectionPlacement
import com.folio.notes.exam.ExamFilter
import com.folio.notes.exam.ExamStatus
import com.folio.notes.exam.ExamTags
import com.folio.notes.exam.ExamType
import com.folio.notes.exam.VceSubject
import com.folio.notes.library.LibraryBrowse
import com.folio.notes.library.LibraryPlace
import com.folio.notes.library.toUxJson
import com.folio.notes.library.uxExamFilter
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.Folder
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.Paper
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import com.folio.notes.model.blankLike
import com.folio.notes.model.countOf
import com.folio.notes.model.reorderedPageBlock
import com.folio.notes.model.resolvePageLabel
import org.junit.Assert.*
import org.junit.Test

class UxWorkflowRulesTest {
    @Test fun invalidKeyboardConfigurationCannotDisableOrAliasTools() {
        for (raw in listOf("", "P:PEN,P:ERASER,H:HIGHLIGHTER,L:LASSO,V:HAND,T:TEXT", "1:PEN,E:ERASER,H:HIGHLIGHTER,L:LASSO,V:HAND,T:TEXT", "P:LASER,E:ERASER,H:HIGHLIGHTER,L:LASSO,V:HAND,T:TEXT")) {
            assertEquals(EditorKeyBindings.defaults, EditorKeyBindings.decode(raw))
        }
        val remapped = linkedMapOf('B' to Tool.PEN, 'R' to Tool.ERASER, 'M' to Tool.HIGHLIGHTER, 'S' to Tool.LASSO, 'G' to Tool.HAND, 'X' to Tool.TEXT)
        assertEquals(remapped, EditorKeyBindings.decode(EditorKeyBindings.encode(remapped).lowercase().split(',').joinToString(",") { token -> token.substringBefore(':') + ":" + token.substringAfter(':').uppercase() }))
    }

    @Test fun namedAndPrintedPagesResolveAfterReorderingAndRejectAmbiguity() {
        val cover = NotePage(id = "cover", title = "Cover", pdfIndex = 0)
        val algebra = NotePage(id = "algebra", title = "Algebra", pdfIndex = 2)
        val numbered = NotePage(id = "numbered", title = "12")
        val pages = listOf(cover, numbered, algebra)
        val labels = mapOf(0 to "i", 2 to "1")
        assertEquals(2, resolvePageLabel(pages, labels, "algebra"))
        assertEquals(0, resolvePageLabel(pages, labels, "1"))
        assertEquals(2, resolvePageLabel(pages, labels, "label:1"))
        assertEquals(1, resolvePageLabel(pages, labels, "name:12"))
        assertEquals(0, resolvePageLabel(pages.reversed(), labels, "Algebra"))
        assertNull(resolvePageLabel(pages + algebra.copy(id = "another"), labels, "Algebra"))
        assertNull(resolvePageLabel(pages, labels, "0"))
        assertNull(resolvePageLabel(pages, labels, "label:"))
    }

    @Test fun alignmentPreservesStackingLayersAndUntouchedObjects() {
        val a = TextBox(id = "a", x = 80f, y = 30f, width = 40f, text = "a", layer = 3)
        val untouched = TextBox(id = "untouched", x = 12f, y = 12f, text = "Keep me", layer = 1)
        val b = TextBox(id = "b", x = 140f, y = 90f, width = 60f, text = "b", layer = 3)
        val page = NotePage(texts = listOf(a, untouched, b))
        val result = SelectionPlacement.apply(page, CanvasSelection(texts = listOf(a, b)), "Left") { 20f }
        assertEquals(listOf("a", "untouched", "b"), result.texts.map { it.id })
        assertSame(untouched, result.texts[1])
        assertEquals(80f, result.texts[2].x, .001f)
        assertEquals(90f, result.texts[2].y, .001f)
        assertEquals(3, result.texts[2].layer)
        assertEquals(page.strokes, result.strokes)
        assertEquals(page.layers, result.layers)
    }

    @Test fun distributionKeepsIdenticalStrokeInstancesIndependent() {
        val first = Stroke(Tool.PEN, 0xff000000.toInt(), 2f, listOf(InkPoint(0f, 10f), InkPoint(10f, 20f)))
        val equalButSeparate = first.copy(points = first.points.toList())
        val last = first.copy(points = first.points.map { it.copy(x = it.x + 100f) })
        val page = NotePage(strokes = listOf(first, equalButSeparate, last))
        val result = SelectionPlacement.apply(page, CanvasSelection(strokes = page.strokes), "Distribute horizontally") { 20f }
        assertEquals(0f, result.strokes[0].points[0].x, .001f)
        assertEquals(50f, result.strokes[1].points[0].x, .001f)
        assertEquals(100f, result.strokes[2].points[0].x, .001f)
        assertEquals(first.points.map { it.pressure }, result.strokes[1].points.map { it.pressure })
    }

    @Test fun savedLibraryScopePreservesAllExamFilters() {
        val filter = ExamFilter(subject = VceSubject.entries.first(), year = 2024, company = "VCAA", type = ExamType.entries.first(), unit = 4, status = ExamStatus.TO_DO, belowShare = .7f, needsRedo = true, incomplete = true)
        assertEquals(filter, uxExamFilter(filter.toUxJson()))
    }
    @Test fun blankTemplateKeepsSetupAndLeavesOriginalContentUntouched() {
        val page = NotePage(width = 1188f, height = 840f, paper = Paper.RULED, pdfIndex = 7,
            strokes = listOf(Stroke(Tool.PEN, 1, 2f, listOf(InkPoint(4f, 8f)))), title = "Question 1", bookmarked = true)
        val original = Notebook(title = "Source", cover = 0x203, folderId = "folder", tags = listOf("Methods"), pages = listOf(page), exam = ExamTags(status = ExamStatus.MARKED), defaultPaper = Paper.DOTS)
        val blank = original.blankLike("Another")
        assertNotEquals(original.id, blank.id)
        assertNotEquals(page.id, blank.pages.single().id)
        assertEquals(original.cover, blank.cover)
        assertEquals(original.folderId, blank.folderId)
        assertEquals(original.tags, blank.tags)
        assertEquals(1188f, blank.pages.single().width, 0f)
        assertEquals(840f, blank.pages.single().height, 0f)
        assertEquals(Paper.DOTS, blank.pages.single().paper)
        assertEquals(ExamStatus.TO_DO, blank.exam.status)
        assertTrue(blank.pages.single().strokes.isEmpty())
        assertTrue(blank.attempts.isEmpty())
        assertNull(blank.pages.single().pdfIndex)
        assertFalse(blank.pages.single().bookmarked)
        assertEquals(1, original.pages.single().strokes.size)
    }

    @Test fun snappingMatchesNearbyEdgesAndKeepsFreeCanvasIndependentOfPageMargins() {
        assertEquals(10f, ObjectSnapping.offset(0f, 20f, 12f, null, listOf(30f to 50f), 3f), .001f)
        assertEquals(24f, ObjectSnapping.offset(0f, 20f, 23f, 840f, emptyList(), 3f), .001f)
        assertEquals(-12f, ObjectSnapping.offset(0f, 20f, -14f, null, emptyList(), 0f), .001f)
        assertEquals(23f, ObjectSnapping.offset(Float.NaN, 20f, 23f, null, emptyList(), 3f), .001f)
    }

    @Test fun blockMovesPreserveEveryPageAndBothRelativeOrders() {
        val random = kotlin.random.Random(754)
        repeat(2000) {
            val ids = List(random.nextInt(1, 100)) { "page-$it" }
            val selected = ids.filter { random.nextBoolean() }.toSet()
            val target = random.nextInt(-10, ids.size + 10)
            val result = reorderedPageBlock(ids, selected, target)
            assertEquals(ids.size, result.size)
            assertEquals(ids.toSet(), result.toSet())
            assertEquals(ids.filter { it in selected }, result.filter { it in selected })
            assertEquals(ids.filterNot { it in selected }, result.filterNot { it in selected })
            val positions = result.indices.filter { result[it] in selected }
            if (positions.isNotEmpty()) assertEquals(positions.size, positions.last() - positions.first() + 1)
        }
    }

    @Test fun quickNotesFollowTheOpenFolderUnlessOneWasChosen() {
        val folders = listOf(Folder(id = "maths", name = "Maths"), Folder(id = "pe", name = "PE"))
        assertEquals("maths", AppPrefs.quickFolderId(null, folders, "maths"))
        assertEquals("pe", AppPrefs.quickFolderId("pe", folders, "maths"))
        assertEquals("maths", AppPrefs.quickFolderId("deleted", folders, "maths"))
        assertNull(AppPrefs.quickFolderId(null, folders))
    }

    @Test fun countsAgreeWithTheirNoun() {
        assertEquals("1 page", countOf(1, "page"))
        assertEquals("0 pages", countOf(0, "page"))
        assertEquals("1 match", countOf(1, "match", "matches"))
        assertEquals("2 matches", countOf(2, "match", "matches"))
    }

    @Test fun folderSearchMatchesEveryWordInAnyOrder() {
        val folders = listOf(Folder(id = "a", name = "2023 Methods"), Folder(id = "b", name = "Methods drafts"))
        assertEquals(listOf("a"), LibraryBrowse.folders(folders, LibraryPlace.ALL, null, null, "methods  2023").map { it.id })
    }

    @Test fun picturesLandCentredOnTheViewAndInsideThePaper() {
        val picture = PickedImage(ByteArray(0), 200, 100)
        val canvas = NotePage(infinite = true)
        val inView = picture.placement(canvas, InkPoint(5000f, -3000f))
        assertEquals(5000f, inView.x + inView.width / 2f, .01f)
        assertEquals(-3000f, inView.y + inView.height / 2f, .01f)
        val paper = NotePage()
        val corner = picture.placement(paper, InkPoint(paper.width, paper.height))
        assertEquals(paper.width, corner.x + corner.width, .01f)
        assertEquals(paper.height, corner.y + corner.height, .01f)
        val centred = picture.placement(paper)
        assertEquals(paper.width / 2f, centred.x + centred.width / 2f, .01f)
    }
}
