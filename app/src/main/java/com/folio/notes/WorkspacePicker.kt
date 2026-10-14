package com.folio.notes

/** Why the workspace is browsing the Library, which decides what a notebook tap does. */
enum class PickerPurpose { COMPANION, OPEN, TABS }

/** Pure labels for Library selection and companion pane controls. */
object WorkspacePicker {
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
        purpose == PickerPurpose.TABS -> "Open documents"
        purpose == PickerPurpose.OPEN -> "Choose a notebook to open"
        mode == CompanionMode.REFERENCE -> "Choose a notebook for reference view"
        else -> "Choose a notebook for split view"
    }
}
