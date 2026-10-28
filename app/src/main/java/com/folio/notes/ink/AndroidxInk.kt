package com.folio.notes.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import com.folio.notes.model.Stroke
import com.folio.notes.model.StrokeStyle
import com.folio.notes.model.Tool

/**
 * Render-only meshes; document samples, erasing and undo remain in Folio's portable model.
 * Each [StrokeStyle] brush is one [BrushFamily] here, chosen per stroke by its stored style.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
internal object AndroidxInk {
    private const val GRAIN = "folio-graphite"
    private val families = java.util.concurrent.ConcurrentHashMap<Pair<StrokeStyle, Boolean>, BrushFamily>()
    private fun family(stroke: Stroke, translucent: Boolean): BrushFamily {
        val brush = if (stroke.style.isBrush) stroke.style else StrokeStyle.SOLID
        // Pencil varies its own opacity, so it always discards self-overlap.
        val discard = translucent || brush == StrokeStyle.PENCIL
        return families.getOrPut(brush to discard) { createFamily(brush, if (discard) SelfOverlap.DISCARD else SelfOverlap.ANY) }
    }

    private fun behavior(target: TargetNode.Target, low: Float, high: Float, source: SourceNode.Source, from: Float, to: Float) =
        BrushBehavior(TargetNode(target, low, high, SourceNode(source, from, to)))
    /** Brush pressure input is stored pressure / 2, so a normal press (stored 1) is .5 and lands on width 1. */
    private fun pressureSize(low: Float, high: Float) =
        behavior(TargetNode.Target.SIZE_MULTIPLIER, low, high, SourceNode.Source.NORMALIZED_PRESSURE, .125f, .9f)
    private fun taper(source: SourceNode.Source) =
        behavior(TargetNode.Target.SIZE_MULTIPLIER, .25f, 1f, source, 0f, 3f)

    private fun createFamily(brush: StrokeStyle, overlap: SelfOverlap): BrushFamily {
        val tip = when (brush) {
            StrokeStyle.FOUNTAIN -> BrushTip(behaviors = listOf(pressureSize(.55f, 1.48f),
                taper(SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE),
                taper(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE)))
            StrokeStyle.PENCIL -> BrushTip(behaviors = listOf(pressureSize(.8f, 1.15f),
                behavior(TargetNode.Target.OPACITY_MULTIPLIER, .3f, 1f, SourceNode.Source.NORMALIZED_PRESSURE, .125f, .75f)))
            StrokeStyle.MARKER -> BrushTip(cornerRounding = .45f)
            StrokeStyle.CALLIGRAPHY -> BrushTip(scaleX = 1f, scaleY = .22f, cornerRounding = .3f, rotationDegrees = 45f,
                behaviors = listOf(pressureSize(.55f, 1.48f)))
            // The plain pen: Folio supplies pressure × taper as the size, input = scale / 2.
            else -> BrushTip(behaviors = listOf(behavior(TargetNode.Target.SIZE_MULTIPLIER, 0f, 2f,
                SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)))
        }
        val textures = if (brush == StrokeStyle.PENCIL) listOf(BrushPaint.TilingTexture.builder()
            .setClientTextureId(GRAIN).setSizeX(16f).setSizeY(16f)
            .setSizeUnit(BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES).build()) else emptyList()
        return BrushFamily(tip = tip, paint = BrushPaint(textures, emptyList(), overlap),
            inputModel = BrushFamily.InputModel.PASSTHROUGH_MODEL)
    }

    /** Paper grain for the pencil: white with noisy alpha, tiled in page units so it stays put under zoom. */
    private val grain: Bitmap by lazy {
        val size = 128
        val random = java.util.Random(7)
        val noise = FloatArray(size * size) { random.nextFloat() }
        val pixels = IntArray(size * size) { i ->
            val x = i % size; val y = i / size
            // A little horizontal smear reads as graphite on paper rather than TV static.
            val n = (noise[i] * 2f + noise[y * size + (x + 1) % size] + noise[y * size + (x + size - 1) % size]) / 4f
            val alpha = (95 + 160 * n).toInt().coerceIn(0, 255)
            (alpha shl 24) or 0xFFFFFF
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    private val textures = object : TextureBitmapStore {
        override fun get(clientTextureId: String): Bitmap? = if (clientTextureId == GRAIN) grain else null
    }
    private val renderers by lazy { ThreadLocal.withInitial { CanvasStrokeRenderer.create(textures) } }
    private val matrices by lazy { ThreadLocal.withInitial { Matrix() } }
    /** Set by the first native failure; every later draw uses Folio's Canvas paths for the rest of the process. */
    @Volatile var failed = false
        private set

    internal fun widthScale(stroke: Stroke, geometry: InkRenderer.RenderedStroke, index: Int): Float =
        if (stroke.tool == Tool.PEN) geometry.widths?.get(index)
            ?: (InkRenderer.penPressureScale(geometry.centre[index].pressure) * (geometry.taper?.get(index) ?: 1f))
        // A lone highlighter or shape dot has always been scaled by its sample's pressure.
        else if (geometry.centre.size < 2) geometry.centre[index].pressure
        else 1f

    /**
     * The pressure handed to the brush. The plain pen gets Folio's own width (pressure × taper) / 2;
     * a brush gets the stored pressure / 2 and shapes it itself. The highlighter has one width per stroke.
     */
    internal fun inputPressure(stroke: Stroke, geometry: InkRenderer.RenderedStroke, index: Int): Float {
        val value = when {
            !stroke.style.isBrush -> widthScale(stroke, geometry, index) / 2f
            stroke.tool == Tool.HIGHLIGHTER -> .5f
            else -> geometry.centre[index].pressure / 2f
        }
        return if (value.isFinite()) value.coerceIn(0f, 1f) else .5f
    }

    /** One owner per cached geometry or live builder; no native resources until first draw. */
    class Geometry {
        private var brush: Brush? = null
        private var progress: InProgressStroke? = null
        private var real: MutableStrokeInputBatch? = null
        private var predicted: MutableStrokeInputBatch? = null
        private var finished: androidx.ink.strokes.Stroke? = null
        private var consumed = 0
        private var lastGeometry: InkRenderer.RenderedStroke? = null

        /** False when AndroidX Ink is unavailable, so the caller draws [stroke] the Canvas way. */
        @Synchronized
        fun draw(canvas: Canvas, stroke: Stroke, geometry: InkRenderer.RenderedStroke): Boolean {
            if (failed) return false
            return try {
                drawMesh(canvas, stroke, geometry); true
            } catch (e: RuntimeException) { fail(e) } catch (e: LinkageError) { fail(e) }
        }

        private fun fail(e: Throwable): Boolean {
            Log.w("AndroidxInk", "AndroidX Ink failed; falling back to Canvas ink", e)
            failed = true
            brush = null; progress = null; real = null; predicted = null; finished = null; lastGeometry = null
            return false
        }

        private fun drawMesh(canvas: Canvas, stroke: Stroke, geometry: InkRenderer.RenderedStroke) {
            if (geometry.centre.isEmpty() || !stroke.width.isFinite() || stroke.width <= 0f) return
            val opacity = stroke.opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
            val alpha = ((stroke.color ushr 24) * opacity).toInt()
            val color = (stroke.color and 0xFFFFFF) or (alpha shl 24)
            val family = family(stroke, alpha != 255)
            var currentBrush = brush
            if (currentBrush == null || currentBrush.family !== family || currentBrush.size != stroke.width ||
                currentBrush.colorIntArgb != color || geometry.stableCount < consumed ||
                (finished != null && lastGeometry !== geometry)) {
                currentBrush = Brush.createWithColorIntArgb(
                    family, color, stroke.width, minOf(.025f, stroke.width / 40f),
                )
                brush = currentBrush
                progress = InProgressStroke().apply { start(currentBrush) }
                real = MutableStrokeInputBatch(); predicted = MutableStrokeInputBatch()
                consumed = 0; lastGeometry = null; finished = null
            }
            if (lastGeometry !== geometry) {
                val ink = progress!!
                val inputs = real!!.apply { clear() }
                val tail = predicted!!.apply { clear() }
                for (i in consumed until geometry.centre.size) {
                    val point = geometry.centre[i]
                    if (!point.x.isFinite() || !point.y.isFinite()) continue
                    // Stored ink has no clock; the passthrough model only needs ordered timestamps.
                    (if (i < geometry.stableCount) inputs else tail).add(
                        type = InputToolType.STYLUS, x = point.x, y = point.y,
                        elapsedTimeMillis = i * 4L, pressure = inputPressure(stroke, geometry, i),
                    )
                }
                ink.enqueueInputs(inputs, tail)
                consumed = geometry.stableCount
                if (!geometry.live) ink.finishInput()
                ink.updateShape()
                lastGeometry = geometry
                if (!geometry.live) {
                    finished = ink.toImmutable()
                    // A finished mesh never takes new input; keep only the immutable copy.
                    progress = null; real = null; predicted = null
                }
            }
            val matrix = matrices.get()!!
            @Suppress("DEPRECATION")
            canvas.getMatrix(matrix)
            val renderer = renderers.get()!!
            val complete = finished
            if (complete != null) renderer.draw(canvas, complete, matrix)
            else renderer.draw(canvas, progress!!, matrix)
        }
    }
}
