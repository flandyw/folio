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
    /**
     * Every brush gets the ballpoint's own pressure width / 2 as its pressure ([inputPressure]), so a
     * normal press is the same width on every brush; brushes only add their character on top.
     */
    private fun folioWidth() = behavior(TargetNode.Target.SIZE_MULTIPLIER, 0f, 2f, SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)
    /**
     * Brush character over the usual pressure band: input .43 is stored pressure .25 (the floor, a light
     * touch) and .5 is stored 1 (firm). `penPressureScale` squeezes that band hard, so a range wider than
     * this would leave ordinary writing using a sliver of it. Firmer presses clamp to [firm].
     */
    private fun pressure(target: TargetNode.Target, light: Float, firm: Float) =
        behavior(target, light, firm, SourceNode.Source.NORMALIZED_PRESSURE, .43f, .5f)

    private fun createFamily(brush: StrokeStyle, overlap: SelfOverlap): BrushFamily {
        val tip = when (brush) {
            // A wide pressure swing, neutral at an ordinary press (stored ≈ .4), and speed thinning baked
            // into the stored pressure at input. No distance taper: it shrinks dots and full stops, tapers
            // every tidied shape and both halves of an erased stroke, and an end taper would thin the ink
            // under the moving tip. Fast flicks thin on their own, which is the real pen's taper.
            StrokeStyle.FOUNTAIN -> BrushTip(behaviors = listOf(folioWidth(), pressure(TargetNode.Target.SIZE_MULTIPLIER, .9f, 1.3f)))
            StrokeStyle.PENCIL -> BrushTip(behaviors = listOf(folioWidth(), pressure(TargetNode.Target.OPACITY_MULTIPLIER, .4f, 1f)))
            StrokeStyle.MARKER -> BrushTip(cornerRounding = .45f)
            StrokeStyle.CALLIGRAPHY -> BrushTip(scaleX = 1f, scaleY = .22f, cornerRounding = .3f, rotationDegrees = 45f,
                behaviors = listOf(folioWidth()))
            // The ballpoint: Folio's pressure × taper width is the size.
            else -> BrushTip(behaviors = listOf(folioWidth()))
        }
        val textures = if (brush == StrokeStyle.PENCIL) listOf(BrushPaint.TilingTexture.builder()
            .setClientTextureId(GRAIN).setSizeX(16f).setSizeY(16f)
            .setSizeUnit(BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES).build()) else emptyList()
        return BrushFamily(tip = tip, paint = BrushPaint(textures, emptyList(), overlap),
            inputModel = BrushFamily.InputModel.PASSTHROUGH_MODEL)
    }

    /**
     * Paper grain for the pencil: white with noisy alpha, tiled in page units so it stays put under zoom.
     * Smooth value noise (a 32-cell lattice, bilinear) rather than per-pixel static: the texture is drawn
     * shrunk without mipmaps, and pixel noise would sparkle while panning.
     */
    private val grain: Bitmap by lazy {
        val size = 128; val cells = 32; val step = size / cells
        val random = java.util.Random(7)
        val lattice = FloatArray(cells * cells) { random.nextFloat() }
        fun at(cx: Int, cy: Int) = lattice[(cy % cells) * cells + cx % cells]
        val pixels = IntArray(size * size) { i ->
            val x = i % size; val y = i / size
            val cx = x / step; val cy = y / step
            val fx = (x % step) / step.toFloat(); val fy = (y % step) / step.toFloat()
            val top = at(cx, cy) + (at(cx + 1, cy) - at(cx, cy)) * fx
            val bottom = at(cx, cy + 1) + (at(cx + 1, cy + 1) - at(cx, cy + 1)) * fx
            val n = top + (bottom - top) * fy
            (((110 + 145 * n).toInt().coerceIn(0, 255)) shl 24) or 0xFFFFFF
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
     * The pressure handed to the brush: the ballpoint's width / 2. The ballpoint's includes its global
     * soft ends; other brushes get the bare pressure width and shape it themselves. The highlighter has
     * one width per stroke.
     */
    internal fun inputPressure(stroke: Stroke, geometry: InkRenderer.RenderedStroke, index: Int): Float {
        val value = when {
            !stroke.style.isBrush -> widthScale(stroke, geometry, index) / 2f
            stroke.tool == Tool.HIGHLIGHTER -> .5f
            else -> InkRenderer.penPressureScale(geometry.centre[index].pressure) / 2f
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
        fun draw(canvas: Canvas, stroke: Stroke, geometry: InkRenderer.RenderedStroke, ahead: FloatArray? = null, aheadCount: Int = 0): Boolean {
            if (failed) return false
            return try {
                drawMesh(canvas, stroke, geometry, ahead, aheadCount); true
            } catch (e: RuntimeException) { fail(e) } catch (e: LinkageError) { fail(e) }
        }

        private fun fail(e: Throwable): Boolean {
            Log.w("AndroidxInk", "AndroidX Ink failed; falling back to Canvas ink", e)
            failed = true
            brush = null; progress = null; real = null; predicted = null; finished = null; lastGeometry = null
            return false
        }

        /** True while the provisional input holds a predicted tip, which the next draw must replace. */
        private var predicting = false

        /**
         * [ahead] is the predicted pen path as page-unit x/y pairs starting at the drawn tip ([aheadCount]
         * points). It joins the provisional input, so the brush draws the prediction in its own shape.
         */
        private fun drawMesh(canvas: Canvas, stroke: Stroke, geometry: InkRenderer.RenderedStroke, ahead: FloatArray?, aheadCount: Int) {
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
            val predicts = geometry.live && ahead != null && aheadCount >= 2
            if (lastGeometry !== geometry || predicts || predicting) {
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
                predicting = predicts && geometry.centre.isNotEmpty()
                if (predicting) {
                    val tip = geometry.centre.size - 1
                    val pressure = inputPressure(stroke, geometry, tip)
                    for (k in 1 until aheadCount) {
                        val x = ahead!![2 * k]; val y = ahead[2 * k + 1]
                        if (!x.isFinite() || !y.isFinite()) continue
                        tail.add(type = InputToolType.STYLUS, x = x, y = y, elapsedTimeMillis = (tip + k) * 4L, pressure = pressure)
                    }
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
