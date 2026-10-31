package com.folio.notes.workspace

import com.folio.notes.model.Notebook
import com.folio.notes.library.matchesQuery
import com.folio.notes.model.hiddenFromLibrary

/** Why the workspace is browsing the Library, which decides what a notebook tap does. */
enum class PickerPurpose { OPEN, TABS }

/** Pure labels for Library selection and the split view controls. */
object WorkspacePicker {
    /** What tapping the flip control will do, in words. */
    fun flipAction(editorOnRight: Boolean): String = if (editorOnRight) "Put the editor on the left" else "Put the editor on the right"

    /** The Library remains a picker until a notebook is tapped or the user cancels. */
    fun libraryCaption(purpose: PickerPurpose): String = when (purpose) {
        PickerPurpose.TABS -> "Open documents"
        PickerPurpose.OPEN -> "Choose a notebook to open"
    }
}

/** What the split view chooser offers, in the order it shows them. */
data class SplitChoices(
    /** The notebook being edited, offered first so two pages of one notebook are one tap away. */
    val current: Notebook?,
    /** Other documents already open as tabs. */
    val tabs: List<Notebook>,
    /** Everything else, most recently changed first. */
    val recent: List<Notebook>,
) {
    val isEmpty: Boolean get() = current == null && tabs.isEmpty() && recent.isEmpty()
}

/** Pure rules for the in-pane document chooser that opens a split. */
object SplitChooser {
    /** Notebooks that never open beside the editor: practice pages, music scores, hidden copies. */
    fun eligible(note: Notebook): Boolean = !note.hiddenFromLibrary && !note.mistakePractice && note.musicScoreId == null

    /** Use the same titles, page names, subject and tags as the Library search. */
    fun matches(note: Notebook, query: String): Boolean = matchesQuery(note, query)

    fun choices(notes: List<Notebook>, tabs: List<EditorTab>, activeId: String?, query: String = ""): SplitChoices {
        val usable = notes.filter { eligible(it) && matches(it, query) }
        val byId = usable.associateBy { it.id }
        val current = activeId?.let(byId::get)
        val open = tabs.map { it.notebookId }.distinct().filter { it != activeId }.mapNotNull(byId::get)
        val shown = open.map { it.id }.toSet() + listOfNotNull(activeId)
        val recent = usable.filter { it.id !in shown }.sortedByDescending { it.updated }
        return SplitChoices(current, open, recent)
    }
}
