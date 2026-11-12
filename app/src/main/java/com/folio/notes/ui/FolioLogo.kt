package com.folio.notes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * The Folio logo on its tile. Brand colours are fixed; they never follow the theme or the user's
 * accent. Size it with [modifier] (square).
 *
 * The shapes are the launcher icon's (`ic_folio_mark.xml`, `docs/brand/folio-icon.svg`), drawn
 * here so the splash can animate them: [fan] 0 → 1 slides the two back sheets out from under the
 * front page, [write] 0 → 1 draws the three lines in turn. Both are read while drawing only.
 */
@Composable
fun FolioLogo(modifier: Modifier = Modifier, fan: () -> Float = { 1f }, write: () -> Float = { 1f }) {
    val tab = remember { PathParser().parsePathString(TAB).toPath() }
    Canvas(modifier.clip(RoundedCornerShape(25)).background(FolioTile)) {
        val f = fan()
        val w = write()
        // The art sits in the central 72 of the 108-unit adaptive-icon canvas.
        val k = size.minDimension / 72f
        withTransform({ scale(k, k, Offset.Zero); translate(-18f, -18f) }) {
            sheet(BACK, f, -11f, Color(0xFF467D8B), shadow = false)
            sheet(MIDDLE, f, 8f, Color(0xFFF1E0CD), shadow = true)
            sheet(FRONT, 1f, 0f, Color(0xFFFBF8F3), shadow = true)
            drawPath(tab, Color(0xFFAD4B2D))
            LINES.forEachIndexed { index, (y, width) ->
                val drawn = (w * LINES.size - index).coerceIn(0f, 1f)
                if (drawn > 0f) drawRoundRect(Color(0xFF8BA8B0), Offset(44.5f, y),
                    Size((width * drawn).coerceAtLeast(LINE), LINE), CornerRadius(LINE / 2f))
            }
        }
    }
}

/** One 34 × 38 page: at [fan] 0 it lies under the front page, at 1 at its own place and [angle]. */
private fun DrawScope.sheet(rest: Offset, fan: Float, angle: Float, color: Color, shadow: Boolean) {
    val at = FRONT + (rest - FRONT) * fan
    rotate(angle * fan, at + Offset(17f, 19f)) {
        if (shadow) drawRoundRect(SHADOW, at + Offset(-.6f, .9f), SHEET, CORNER)
        drawRoundRect(color, at, SHEET, CORNER)
    }
}

/** `@color/folio_tile`, the powder blue behind the mark. */
val FolioTile = Color(0xFFB6D0DE)

private val SHEET = Size(34f, 38f)
private val CORNER = CornerRadius(4.6f)
private val SHADOW = Color(0x241F3A44)
private val FRONT = Offset(40.5f, 32.5f)
private val MIDDLE = Offset(34.2f, 34.9f)
private val BACK = Offset(33.6f, 37.3f)
private const val LINE = 2.2f
/** Each line's top and full width. */
private val LINES = listOf(46.2f to 26f, 53.1f to 26f, 60f to 18f)
private const val TAB = "M45.1,32.5 H52.5 V38.5 A2,2 0 0 1 50.5,40.5 H40.5 V37.1 A4.6,4.6 0 0 1 45.1,32.5 Z"
