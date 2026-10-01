package com.folio.notes

/** What the workspace picker was opened to choose, which decides what a tap does. */
enum class PickerPurpose { COMPANION, OPEN, TABS }

/** One labelled run of notebooks in the picker: open documents first, then the library. */
data class PickerSection(val label: String, val notes: List<Notebook>)

/**
 * Which documents the workspace picker shows and in what order, free of Compose types so the
 * search, grouping and section rules stay JVM-testable.
 */
object WorkspacePicker {
    /** The unfiled bucket sits after every folder, under this heading. */
    const val UNFILED_LABEL = "Notebooks"
    const val OPEN_LABEL = "Open documents"

    /**
     * True when [note] answers [query]: its title, exam summary, subject, tags or folder name.
     * A blank query matches everything, so the picker opens on the whole library.
     */
    fun matches(note: Notebook, query: String, folderName: String? = null): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        val haystack = listOfNotNull(
            note.title,
            note.exam.summaryLine(),
            note.exam.subjectLabel,
            folderName,
            note.exam.company,
            note.exam.type?.label,
            note.exam.tags.joinToString(" ") { it.label }
        ).joinToString(" ").lowercase()
        return haystack.contains(needle)
    }

    /**
     * The picker's rows: open documents first, then one section per folder, then the unfiled
     * notebooks. Sections with nothing in them never appear, so an empty folder is not a heading.
     */
    fun sections(
        notes: List<Notebook>,
        query: String,
        openIds: Set<String>,
        folders: Map<String, String> = emptyMap()
    ): List<PickerSection> {
        val kept = notes.filter { matches(it, query, it.folderId?.let(folders::get)) }
        if (kept.isEmpty()) return emptyList()
        val out = mutableListOf<PickerSection>()
        val opened = kept.filter { it.id in openIds }.sortedBy { it.title.lowercase() }
        if (opened.isNotEmpty()) out += PickerSection(OPEN_LABEL, opened)
        // An open document is listed once, at the top: never again under its folder.
        val rest = kept.filterNot { it.id in openIds }
        // Folders keep the library's own order, so the picker mirrors how the shelves are filed.
        folders.forEach { (id, name) ->
            val group = rest.filter { it.folderId == id }.sortedBy { it.title.lowercase() }
            if (group.isNotEmpty()) out += PickerSection(name, group)
        }
        val loose = rest.filter { it.folderId == null || it.folderId !in folders }
            .sortedBy { it.title.lowercase() }
        if (loose.isNotEmpty()) out += PickerSection(UNFILED_LABEL, loose)
        return out
    }

    /**
     * The badge a row shows instead of its own action: what this document already is in the
     * workspace. Null when the row's tap is the only thing to do.
     */
    fun badge(noteId: String, activeId: String?, companionId: String?, openIds: Set<String>): String? = when {
        noteId == companionId -> "In this pane"
        noteId == activeId -> "Editing"
        noteId in openIds -> "Open"
        else -> null
    }

    /** The supporting line under a title: how long it is and what paper it is. */
    fun subtitle(note: Notebook): String {
        val pages = if (note.pages.size == 1) "1 page" else "${note.pages.size} pages"
        val pdf = if (note.pages.any { it.pdfIndex != null }) "PDF" else null
        val summary = note.exam.summaryLine().takeIf { it.isNotBlank() && it != note.title }
        return listOfNotNull(pages, pdf, summary).joinToString(" · ")
    }

    /**
     * The labels for the flip control, which say which side the work document will end up on
     * rather than naming a direction the user has to work out from the current layout.
     */
    fun flipCaption(editorOnRight: Boolean): String = if (editorOnRight) "Editor on the right" else "Editor on the left"

    /** What tapping the flip control will do, in words. */
    fun flipAction(editorOnRight: Boolean): String = if (editorOnRight) "Put the editor on the left" else "Put the editor on the right"

    /** Which pane each side shows, for the flip hint under the switch. */
    fun sideHint(editorOnRight: Boolean): String =
        if (editorOnRight) "The reference pane is on the left." else "The reference pane is on the right."

    /** A one-line description of what a companion mode does, shown under its toggle. */
    fun modeCaption(mode: CompanionMode): String = when (mode) {
        CompanionMode.SPLIT -> "Both panes are editable. Drag the divider to resize them."
        CompanionMode.REFERENCE -> "The other pane is read only, with its own zoom, search and page controls."
    }

    /** The Library remains a picker until a notebook is tapped or the user cancels. */
    fun libraryCaption(purpose: PickerPurpose, mode: CompanionMode): String = when {
        purpose != PickerPurpose.COMPANION -> "Choose a notebook to open"
        mode == CompanionMode.REFERENCE -> "Choose a notebook for reference view"
        else -> "Choose a notebook for split view"
    }

    /** The title the panel carries for each purpose. */
    fun title(purpose: PickerPurpose): String = when (purpose) {
        PickerPurpose.COMPANION -> "Open beside the editor"
        PickerPurpose.OPEN -> "Open a document"
        PickerPurpose.TABS -> "Open documents"
    }

    /**
     * The empty-state line for each reason a list can come back with nothing in it: the library
     * is still reading, the library is empty, or the search simply matched no document.
     */
    fun emptyCaption(purpose: PickerPurpose, loading: Boolean, query: String, libraryCount: Int): String = when {
        loading -> "Opening your library…"
        libraryCount == 0 && purpose == PickerPurpose.COMPANION -> "No documents yet — make one from the library."
        libraryCount == 0 -> "Your library is empty. Make a notebook to get started."
        query.isNotBlank() -> "No documents match “${query.trim().take(40)}”."
        else -> "Nothing to show."
    }
}
