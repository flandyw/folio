package com.folio.notes.document

import com.folio.notes.app.FolioState
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
import com.folio.notes.model.notebook
import com.folio.notes.model.page
import com.folio.notes.model.replaceNotebook
import com.folio.notes.model.replacePage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import org.junit.Assert.*
import org.junit.Test

class DocumentStateStoreTest {
    private fun ink(x: Float) = Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(x, 1f)))
    private fun store(): DocumentStateStore = DocumentStateStore(FolioState(loading = false)).also { store ->
        store.update { it.copy(notes = listOf(Notebook(id = "n", title = "Notebook", pages = listOf(NotePage(id = "a"), NotePage(id = "b"))))) }
    }

    @Test fun changesOnlyPublishToTheirPageAndKeepSummariesFreeOfContent() = runBlocking {
        val store = store()
        val a = store.observePage("n", "a")
        val b = store.observePage("n", "b")
        val order = store.observePageOrder("n")
        var aEvents = 0; var bEvents = 0; var orderEvents = 0
        val jobs = listOf(launch(start = CoroutineStart.UNDISPATCHED) { a.collect { aEvents++ } },
            launch(start = CoroutineStart.UNDISPATCHED) { b.collect { bEvents++ } },
            launch(start = CoroutineStart.UNDISPATCHED) { order.collect { orderEvents++ } })
        val unchanged = b.value
        val note = store.value.notes.notebook("n")!!
        val edited = note.pages.page("a")!!.copy(revision = 1, strokes = listOf(ink(2f)))
        store.update { it.copy(notes = it.notes.replaceNotebook(note.copy(pages = note.pages.replacePage(edited)))) }
        yield()
        assertEquals(2, aEvents); assertEquals(1, bEvents); assertEquals(1, orderEvents)
        assertSame(unchanged, b.value)
        assertSame(edited, a.value!!.page)
        assertEquals(1, a.value!!.revision)
        val summary = store.observeNotebook("n").value!!
        assertEquals(listOf("a", "b"), summary.pageOrder)
        assertTrue(summary.metadata.pages.all { it.strokes.isEmpty() && it.texts.isEmpty() && it.images.isEmpty() && it.layers.isEmpty() })
        assertTrue(store.shellState.value.notes.all { n -> n.pages.all { it.strokes.isEmpty() } })
        jobs.forEach { it.cancelAndJoin() }
    }

    @Test fun immediatePageObserversReadTheSameCommittedIndexedSnapshot() = runBlocking {
        val store = store()
        val flow = store.observePage("n", "a")
        var seen: NotePage? = null
        val observer = launch(Dispatchers.Unconfined) {
            flow.drop(1).take(1).collect { snapshot ->
                seen = store.page("a")
                assertSame(snapshot!!.page, seen)
            }
        }
        val note = store.value.notes.notebook("n")!!
        val edited = note.pages.page("a")!!.copy(revision = 1)
        store.update { it.copy(notes = it.notes.replaceNotebook(note.copy(pages = note.pages.replacePage(edited)))) }
        observer.join()
        assertSame(edited, seen)
    }

    @Test fun immediateObserverEditsCannotBeOverwrittenByOlderNotifications() = runBlocking {
        val store = store()
        val a = store.observePage("n", "a")
        val b = store.observePage("n", "b")
        val observer = launch(Dispatchers.Unconfined) {
            a.drop(1).take(1).collect {
                store.update { current ->
                    val note = current.notes.notebook("n")!!
                    current.copy(notes = current.notes.replaceNotebook(note.copy(pages = note.pages.replacePage(note.pages.page("b")!!.copy(revision = 2)))))
                }
            }
        }
        store.update { current ->
            val note = current.notes.notebook("n")!!
            current.copy(notes = current.notes.replaceNotebook(note.copy(pages = note.pages.map { it.copy(revision = 1) })))
        }
        observer.join()
        assertEquals(2, store.page("b")!!.revision)
        assertEquals(2, b.value!!.revision)
        assertEquals(2, store.shellState.value.notes.notebook("n")!!.pages.page("b")!!.revision)
        assertEquals(2, store.observeNotebook("n").value!!.metadata.pages.page("b")!!.revision)
    }

    @Test fun reorderRemovalAndRecreationKeepExistingReadersUsable() {
        val store = store()
        val page = store.observePage("n", "a")
        val notebook = store.observeNotebook("n")
        val order = store.observePageOrder("n")
        store.update { it.copy(notes = listOf(it.notes[0].copy(pages = it.notes[0].pages.reversed()))) }
        assertEquals(listOf("b", "a"), order.value)
        assertEquals("n", store.notebookForPage("a")!!.id)
        store.update { it.copy(notes = emptyList()) }
        assertNull(page.value); assertNull(notebook.value); assertNull(store.page("a")); assertTrue(order.value.isEmpty())
        store.update { it.copy(notes = listOf(Notebook(id = "n", title = "Restored", pages = listOf(NotePage(id = "a", revision = 9))))) }
        assertEquals(9, page.value!!.revision)
        assertEquals("Restored", notebook.value!!.metadata.title)
        assertEquals(listOf("a"), order.value)
    }

    @Test fun housekeepingDoesNotReplaceContentOrPageReaders() {
        val store = store()
        val snapshot = store.observePage("n", "a").value
        val notes = store.value.notes
        store.update { it.copy(editGeneration = 10, durableGeneration = 7, saveFailed = true) }
        assertSame(notes, store.value.notes)
        assertSame(snapshot, store.observePage("n", "a").value)
        assertTrue(store.value.saving)
    }

    @Test fun notebookMetadataReusesPageProjectionsAndOrder() {
        val store = store()
        val before = store.observeNotebook("n").value!!
        val page = store.observePage("n", "a").value
        store.update { state -> state.copy(notes = state.notes.replaceNotebook(state.notes.notebook("n")!!.copy(title = "Renamed"))) }
        val after = store.observeNotebook("n").value!!
        assertEquals("Renamed", after.metadata.title)
        assertSame(before.metadata.pages, after.metadata.pages)
        assertSame(before.pageOrder, after.pageOrder)
        assertSame(page, store.observePage("n", "a").value)
    }

    @Test fun multiplePageAndNotebookReplacementsPreserveUnchangedProjections() {
        val store = DocumentStateStore(FolioState(notes = List(3) { n ->
            Notebook(id = "n$n", title = "Notebook $n", pages = List(17) { p -> NotePage(id = "n${n}p$p") })
        }, loading = false))
        val before = store.observeNotebook("n1").value!!
        val unchanged = store.observePage("n1", "n1p8").value
        val a = store.observePage("n1", "n1p2")
        val b = store.observePage("n1", "n1p15")
        store.update { state ->
            val note = state.notes.notebook("n1")!!
            val changed = note.copy(pages = note.pages
                .replacePage(note.pages.page("n1p15")!!.copy(revision = 3, strokes = listOf(ink(3f))))
                .replacePage(note.pages.page("n1p2")!!.copy(revision = 2, strokes = listOf(ink(2f)))))
            state.copy(notes = state.notes.replaceNotebook(changed)
                .replaceNotebook(state.notes.notebook("n2")!!.copy(title = "Renamed")))
        }
        val after = store.observeNotebook("n1").value!!
        assertEquals(2, a.value!!.revision); assertEquals(3, b.value!!.revision)
        assertSame(unchanged, store.observePage("n1", "n1p8").value)
        assertSame(before.metadata.pages[8], after.metadata.pages[8])
        assertSame(before.pageOrder, after.pageOrder)
        assertTrue(after.metadata.pages.all { it.strokes.isEmpty() })
        assertEquals("Renamed", store.shellState.value.notes.notebook("n2")!!.title)
    }
}
