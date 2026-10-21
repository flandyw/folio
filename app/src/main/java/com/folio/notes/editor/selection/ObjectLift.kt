package com.folio.notes.editor.selection

import android.graphics.Canvas
import com.folio.notes.model.CanvasSelection

/** A movable selection carried above the scrolling page column, in window pixels. */
internal class ObjectLift(
    val selection: CanvasSelection,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val unit: Float,
    val fingerX: Float,
    val fingerY: Float,
    val draw: (Canvas) -> Unit,
)
