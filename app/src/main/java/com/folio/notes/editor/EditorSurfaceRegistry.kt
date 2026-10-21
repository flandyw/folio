package com.folio.notes.editor

import androidx.compose.runtime.*
import com.folio.notes.ink.InkView

internal enum class EditorSurfaceRole { MAIN, ZOOM, PEEK, PREVIEW }

/** Handles exist only while their native surface is hosted. Content is never stored here. */
internal class EditorSurfaceRegistry {
    private val views = mutableStateMapOf<InkView, Pair<EditorSurfaceRole, String>>()
    fun bind(view: InkView, role: EditorSurfaceRole, pageId: String) {
        val identity = role to pageId
        if (views[view] != identity) views[view] = identity
    }
    fun editingSurfaces(): List<InkView> = views.entries.filter { it.value.first == EditorSurfaceRole.MAIN || it.value.first == EditorSurfaceRole.ZOOM }.map { it.key }
    fun mainSurfaces(): List<InkView> = views.entries.filter { it.value.first == EditorSurfaceRole.MAIN }.map { it.key }
    fun release(view: InkView) { views.remove(view) }
    fun find(role: EditorSurfaceRole, pageId: String): InkView? =
        views.entries.lastOrNull { it.value == role to pageId }?.key
}

internal val LocalEditorSurfaces = staticCompositionLocalOf<EditorSurfaceRegistry?> { null }
