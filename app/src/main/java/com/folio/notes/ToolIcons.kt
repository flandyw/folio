package com.folio.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Toolbar glyphs the Material set has no honest picture for (its "eraser" is a magic wand, its
 * "highlighter" a pencil, its "lasso" a squiggle). Drawn on the same 24 dp grid with a 2 dp round
 * stroke plus solid accents, so they sit beside the Rounded set without looking foreign.
 */
internal object ToolIcons {
    private val ink = SolidColor(Color.Black)

    private fun build(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.outline(block: PathBuilder.() -> Unit) =
        path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

    private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) = path(fill = ink, pathBuilder = block)

    /** A slanted rubber: outlined block, solid lower end, and the surface it rubs. */
    val Eraser by lazy {
        build("Eraser") {
            outline {
                moveTo(14.5f, 3.6f); lineTo(19.4f, 8.5f); lineTo(9.5f, 18.4f); lineTo(4.6f, 13.5f); close()
                moveTo(10.94f, 12.06f); lineTo(13.4f, 14.5f)
                moveTo(12f, 21f); lineTo(20f, 21f)
            }
            solid { moveTo(4.6f, 13.5f); lineTo(8.47f, 9.6f); lineTo(13.4f, 14.5f); lineTo(9.5f, 18.4f); close() }
        }
    }

    /** A chisel-tipped marker over a swatch of colour. */
    val Highlighter by lazy {
        build("Highlighter") {
            outline {
                moveTo(15.5f, 3.5f); lineTo(20.5f, 8.5f); lineTo(12.5f, 16.5f); lineTo(7.5f, 11.5f); close()
                moveTo(3.5f, 21f); lineTo(12f, 21f)
            }
            solid { moveTo(7.5f, 11.5f); lineTo(12.5f, 16.5f); lineTo(6.5f, 17.5f); close() }
        }
    }

    /** A dashed loop with its trailing rope: the marquee-by-freehand selection. */
    val Lasso by lazy {
        build("Lasso") {
            outline {
                moveTo(20.42f, 10.84f); arcTo(8.5f, 6f, 0f, false, true, 18.71f, 13.68f)
                moveTo(16.32f, 15.17f); arcTo(8.5f, 6f, 0f, false, true, 12.11f, 16f)
                moveTo(8.97f, 15.61f); arcTo(8.5f, 6f, 0f, false, true, 5.42f, 13.8f)
                moveTo(3.9f, 11.83f); arcTo(8.5f, 6f, 0f, false, true, 3.69f, 8.74f)
                moveTo(4.93f, 6.67f); arcTo(8.5f, 6f, 0f, false, true, 8.21f, 4.63f)
                moveTo(11.28f, 4.02f); arcTo(8.5f, 6f, 0f, false, true, 15.59f, 4.56f)
                moveTo(18.17f, 5.88f); arcTo(8.5f, 6f, 0f, false, true, 20.26f, 8.59f)
                moveTo(11f, 16.2f); curveTo(10f, 18f, 10.8f, 19.5f, 8.5f, 20.8f)
            }
        }
    }

    /** A straight segment with its two end points (Material's line-ish icon is a zigzag chart). */
    val Line by lazy {
        build("Line") {
            outline { moveTo(7.5f, 16.5f); lineTo(16.5f, 7.5f) }
            solid {
                moveTo(3f, 18.5f); arcToRelative(2.5f, 2.5f, 0f, true, true, 5f, 0f); arcToRelative(2.5f, 2.5f, 0f, true, true, -5f, 0f); close()
                moveTo(16f, 5.5f); arcToRelative(2.5f, 2.5f, 0f, true, true, 5f, 0f); arcToRelative(2.5f, 2.5f, 0f, true, true, -5f, 0f); close()
            }
        }
    }
}
