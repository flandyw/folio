package com.folio.notes

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** Fitted sheet navigation retains the pager's existing stylus-safe swipe ownership. */
@Composable internal fun SinglePageCanvas(
    pages: List<NotePage>, index: Int, top: Dp, swipeTurns: Boolean, zoomed: Boolean,
    atLeftEdge: Boolean, atRightEdge: Boolean, onNote: (Float, Float) -> Boolean, haptics: Boolean,
    onSelect: (String, String) -> Unit, sheet: @Composable (NotePage, Int, Boolean) -> Unit,
) = SinglePageSheet(pages, index, top, swipeTurns, zoomed, atLeftEdge, atRightEdge, onNote, haptics, onSelect, sheet)

/** A free canvas keeps its native camera and explicit response visit; no document-follow policy. */
@Composable internal fun InfiniteCanvas(noteId: String, page: NotePage, model: FolioViewModel,
    surface: InkSurfaceConfig, presentation: PagePresentation, actions: EditorPageActions) {
    EditorPage(noteId, page, model, surface, presentation.copy(fullscreen = true), actions)
}
