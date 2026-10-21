package com.folio.notes.editor

import androidx.compose.ui.geometry.Rect

/** Stand-in for the editor's transfer geometry; the runner compiles the production overlay classes. */
class EditorTransferCoordinator {
    var onGeometryChanged: (() -> Unit)? = null
    val regions = mutableMapOf<String, Rect>()
    val papers = mutableMapOf<String, Rect>()
    fun workspaceFrame(id: String) = regions[id]
    fun paperFrame(id: String) = papers[id]
}
