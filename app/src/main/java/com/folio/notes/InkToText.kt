package com.folio.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Lasso handwriting → one typed text box. The selected ink is drawn black on white at a scale the
 * recogniser reads well, then read by the same on-device ML Kit recogniser the PDF search and marks
 * use. Nothing is kept: the picture is recycled and only the recognised words reach the page.
 */
internal object InkToText {
    /** Recogniser pixels per page unit; a very large selection shrinks to fit [MAX_SIDE] instead. */
    private const val SCALE = 2.5f
    private const val MAX_SIDE = 2048
    /** Whitespace around the ink, so the recogniser never sees strokes touching the border. */
    private const val PAD = 16f
    /** Typed lines sit tighter than the handwriting that produced them. */
    private const val LINE_HEIGHT = 1.25f
    private const val MIN_SIZE = 12f
    private const val MAX_SIZE = 72f
    /** Narrowest box, so a single short word still has room to be edited. */
    private const val MIN_WIDTH = 160f

    /** The recognised words plus the place and type size they should take on the page. */
    class Result(val text: String, val x: Float, val y: Float, val width: Float, val size: Float)

    /** Null when the ink holds nothing the recogniser can read. Throws when recognition itself fails. */
    suspend fun recognise(strokes: List<Stroke>): Result? {
        if (strokes.isEmpty()) return null
        val bounds = strokes.map { InkRenderer.rawBounds(it) }
        val left = bounds.minOf { it[0] }; val top = bounds.minOf { it[1] }
        val right = bounds.maxOf { it[2] }; val bottom = bounds.maxOf { it[3] }
        val spanX = right - left + 2 * PAD; val spanY = bottom - top + 2 * PAD
        val scale = min(SCALE, MAX_SIDE / max(spanX, spanY))
        val bitmap = Bitmap.createBitmap(ceil(spanX * scale).toInt().coerceAtLeast(1),
            ceil(spanY * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            canvas.translate(PAD - left, PAD - top)
            strokes.forEach { InkRenderer.stroke(canvas, it.inkOnly()) }
            val text = recognizePdfText(recognizer, bitmap)
            val lines = text.textBlocks.flatMap { it.lines }.filter { it.text.isNotBlank() }
            if (lines.isEmpty()) return null
            // Type size follows the handwriting's own line height, measured by the recogniser's line boxes.
            val heights = lines.mapNotNull { it.boundingBox?.height()?.toFloat() }.filter { it > 0f }.sorted()
            val medianHeight = heights.getOrNull(heights.size / 2)?.div(scale)
            val size = medianHeight?.let { (it / LINE_HEIGHT).coerceIn(MIN_SIZE, MAX_SIZE) }
                ?: ((bottom - top) / lines.size / LINE_HEIGHT).coerceIn(MIN_SIZE, MAX_SIZE)
            return Result(
                text = lines.joinToString("\n") { it.text.trim() },
                x = left, y = top,
                width = max(right - left, MIN_WIDTH),
                size = size
            )
        } finally {
            bitmap.recycle()
            recognizer.close()
        }
    }

    /** Recognition reads dark ink on white, whatever colour or opacity the writer used. */
    private fun Stroke.inkOnly() = copy(color = Color.BLACK, opacity = 1f)
}
