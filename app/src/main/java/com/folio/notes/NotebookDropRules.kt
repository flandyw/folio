package com.folio.notes

/** A drag captures identities once; changing folders or filtering never changes its selection. */
internal sealed interface NotebookDragPayload {
    val title: String
    data class Notes(val ids: Set<String>, override val title: String) : NotebookDragPayload
    data class Folder(val id: String, override val title: String) : NotebookDragPayload
}

internal sealed interface NotebookDropDestination {
    val label: String
    data class Folder(val id: String?, override val label: String) : NotebookDropDestination
    data object Favorites : NotebookDropDestination { override val label = "Favorites" }
    data class Tag(val tag: String) : NotebookDropDestination { override val label = "Tag · $tag" }
}

internal object NotebookDropRules {
    fun canOpen(payload: NotebookDragPayload, destination: NotebookDropDestination, folders: List<Folder>): Boolean {
        if (destination !is NotebookDropDestination.Folder) return false
        if (destination.id != null && folders.none { it.id == destination.id }) return false
        return payload !is NotebookDragPayload.Folder || LibraryFolders.canMove(folders, payload.id, destination.id)
    }

    fun selection(id: String, selected: Set<String>, notes: List<Notebook>): Set<String> {
        val visible = notes.map { it.id }.toSet()
        return (if (id in selected) selected else setOf(id)).intersect(visible)
    }

    fun canDrop(payload: NotebookDragPayload, destination: NotebookDropDestination, notes: List<Notebook>, folders: List<Folder>): Boolean {
        if (destination is NotebookDropDestination.Folder && destination.id != null && folders.none { it.id == destination.id }) return false
        return when (payload) {
            is NotebookDragPayload.Folder -> {
                val folder = folders.find { it.id == payload.id } ?: return false
                destination is NotebookDropDestination.Folder && folder.parentId != destination.id &&
                    LibraryFolders.canMove(folders, folder.id, destination.id) &&
                    folders.none { it.id != folder.id && it.parentId == destination.id && it.name.equals(folder.name, true) }
            }
            is NotebookDragPayload.Notes -> {
                val dragged = notes.filter { it.id in payload.ids }
                if (dragged.isEmpty() || dragged.size != payload.ids.size) return false
                when (destination) {
                    is NotebookDropDestination.Folder -> dragged.any { it.folderId != destination.id }
                    NotebookDropDestination.Favorites -> dragged.any { !it.starred }
                    is NotebookDropDestination.Tag -> {
                        val label = destination.tag.trim()
                        label.isNotEmpty() && label.length <= NotebookTags.MAX_LENGTH &&
                            dragged.any { note -> note.tags.none { it.equals(label, true) } } &&
                            dragged.all { note -> note.tags.any { it.equals(label, true) } || note.tags.size < NotebookTags.MAX_TAGS }
                    }
                }
            }
        }
    }

    /** Signed edge velocity, ramping from zero to one toward the viewport boundary. */
    fun edgeScroll(y: Float, top: Float, bottom: Float, edge: Float): Float {
        if (y < top || y > bottom || bottom <= top || edge <= 0) return 0f
        val band = minOf(edge, (bottom - top) / 2)
        return when {
            y < top + band -> -((top + band - y) / band).coerceIn(0f, 1f)
            y > bottom - band -> ((y - bottom + band) / band).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}
