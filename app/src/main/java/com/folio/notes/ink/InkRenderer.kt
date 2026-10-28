package com.folio.notes.ink

import android.graphics.*
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import com.folio.notes.editor.sticky.StickyNotes
import com.folio.notes.math.FormulaSpan
import com.folio.notes.math.TextMath
import com.folio.notes.math.TextMathBitmaps
import com.folio.notes.model.ImageTransforms
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageImage
import com.folio.notes.model.PageLayers
import com.folio.notes.model.Paper
import com.folio.notes.model.ShapeTools
import com.folio.notes.model.Stroke
import com.folio.notes.model.StrokeStyle
import com.folio.notes.model.TextAlignMode
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import kotlin.math.*

object InkRenderer {
    /** Tools whose geometry traces the drag rather than freehand samples, so it is never smoothed. */
    private val SHAPE_SET = ShapeTools
    // One Paint per thread, reused across strokes: onDraw used to allocate a Paint per stroke
    // per frame, which churned hundreds of objects while writing or scrolling a dense page.
    // Each thread (UI + thumbnail IO) gets its own instance, so reuse never races.
    // Everything here is lazy so object init stays free of Android types: unit tests run on a
    // plain JVM where Paint/Path are stubs, and they only exercise the pure geometry below.
    private val strokePaintPool by lazy { ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG) } }
    private val bitmapPaintPool by lazy { ThreadLocal.withInitial { Paint(Paint.FILTER_BITMAP_FLAG) } }
    // Paper paints never change, so they are shared read-only instead of rebuilt every frame.
    private val paperMinorPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0DA.toInt(); strokeWidth = .8f } }
    private val paperGridMinorPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0DA.toInt(); strokeWidth = .7f } }
    private val paperGridMajorPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200); strokeWidth = .9f } }
    private val paperMathMinorPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 232, 228); strokeWidth = .65f } }
    private val paperMathMajorPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 214, 210); strokeWidth = .9f } }
    private val graphAxisPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140, 145, 150); strokeWidth = 1.4f } }
    private val graphTickPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(175, 180, 185); strokeWidth = 0.85f } }
    // A dot drawn as a round point is the same disc as drawCircle(r = 1.2), but a whole page goes in one call.
    private val paperDotPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0DA.toInt(); strokeWidth = 2.4f; strokeCap = Paint.Cap.ROUND } }
    /** Two reusable coordinate buffers per thread, so a paper pattern is one draw call per paint, not one per line or dot. */
    private val paperBatchPool by lazy { ThreadLocal.withInitial { arrayOf(PaperBatch(), PaperBatch()) } }
    private val arrowPathPool by lazy { ThreadLocal.withInitial { Path() } }
    private val strokePathPool by lazy { ThreadLocal.withInitial { Path() } }
    private val clipRectPool by lazy { ThreadLocal.withInitial { android.graphics.Rect() } }
    private val bitmapRectPool by lazy { ThreadLocal.withInitial { RectF() } }
    // Cached typefaces: Typeface.create does a font lookup, far too heavy per text layout.
    private val serifNormal by lazy { Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
    private val serifBold by lazy { Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    private val sansNormal by lazy { Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    // Dash effects are immutable and shareable; building one per stroke per frame churned
    // hundreds of native objects while scrolling a shape-heavy page. Widths are bucketed
    // to 0.5px so pressure/base-width variance stays in a small cache.
    private val dashCache = mutableMapOf<Pair<StrokeStyle, Int>, android.graphics.PathEffect?>()
    // Finite-paper paints hoisted so scrolling never allocates Paint/drawText Paints per frame.
    private val mcInkPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200); strokeWidth = .9f; style = Paint.Style.STROKE } }
    private val mcLabelPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 120, 115); textSize = 11f; typeface = sansNormal
    } }
    private val mcNumberPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 145); textAlign = Paint.Align.RIGHT; textSize = 13f
        typeface = sansNormal
    } }
    private val hanziOuterPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200); strokeWidth = .9f } }
    private val hanziGuidePaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(221, 170, 170); strokeWidth = .7f
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    } }
    // Text layouts are pure functions of their box, but building a StaticLayout parses and
    // measures text — far too heavy to redo for every box on every frame. Boxes are immutable,
    // so the layout itself can be memoized; drawing the cached layout is just a blit.
    private const val MAX_CACHED_TEXT_LAYOUTS = 64
    /** [math] says which of the box's formulas have a picture yet, so a layout is rebuilt as they arrive. */
    private data class TextLayoutKey(val box: TextBox, val math: Int)
    private val textLayoutCache = object : LinkedHashMap<TextLayoutKey, StaticLayout>(MAX_CACHED_TEXT_LAYOUTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextLayoutKey, StaticLayout>?): Boolean =
            size > MAX_CACHED_TEXT_LAYOUTS
    }
    private val mathSplitCache = object : LinkedHashMap<String, List<TextMath.Part>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TextMath.Part>>?): Boolean = size > 64
    }
    private fun mathParts(text: String): List<TextMath.Part>? {
        if (!TextMath.mayHaveMath(text)) return null
        val parts = synchronized(mathSplitCache) { mathSplitCache[text] } ?: TextMath.split(text).also {
            synchronized(mathSplitCache) { mathSplitCache[text] = it }
        }
        return parts.takeIf { list -> list.any { it is TextMath.Part.Formula } }
    }

    /** Uniform-width live ink shares the same bounded-tail spline path. */
    internal class IncrementalHighlighterStroke {
        private val smoother = InkGeometry.IncrementalSmooth()
        private var consumed = 0
        private val ink = AndroidxInk.Geometry()
        private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE
        private var maxX = -Float.MAX_VALUE; private var maxY = -Float.MAX_VALUE
        fun update(points: List<InkPoint>): RenderedStroke {
            while (consumed < points.size) {
                val p = points[consumed++]
                minX = min(minX, p.x); minY = min(minY, p.y)
                maxX = max(maxX, p.x); maxY = max(maxY, p.y)
            }
            return RenderedStroke(smoother.update(points), null,
                if (points.isEmpty()) 0f else minX, if (points.isEmpty()) 0f else minY,
                if (points.isEmpty()) 0f else maxX, if (points.isEmpty()) 0f else maxY).also {
                    it.live = true
                    it.stableCount = smoother.stablePointCount
                    it.ink = ink
                }
        }
    }

    /**
     * Tiny handwriting needs its direction changes left intact. A normal smoothing pass is useful on
     * long sweeps, but averaging across a fast, tight turn can erase the hump of a small n/m/r.
     */
    private const val DUPLICATE_EPSILON = 0.05f
    private const val FAST_SAMPLE_GAP = 5f
    private const val MICRO_STROKE_SPAN = 36f
    private const val MICRO_TURN_COS = 0.94f
    private const val NORMAL_TURN_COS = 0.82f

    /**
     * Smooths handwriting in sections, splitting at tight turns and sparse/fast samples. Each split
     * point becomes the endpoint of both neighbouring spline sections, so it cannot be averaged away.
     * This keeps small letters legible while still smoothing the straighter parts of a long stroke.
     */
    internal fun handwritingCentreline(points: List<InkPoint>): List<InkPoint> {
        if (points.size < 3) return points

        val clean = ArrayList<InkPoint>(points.size)
        points.forEach { point ->
            if (clean.isEmpty() || hypot(point.x - clean.last().x, point.y - clean.last().y) > DUPLICATE_EPSILON) {
                clean += point
            }
        }
        if (clean.size < 3) return clean

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        clean.forEach {
            minX = min(minX, it.x); minY = min(minY, it.y)
            maxX = max(maxX, it.x); maxY = max(maxY, it.y)
        }
        val microStroke = hypot(maxX - minX, maxY - minY) <= MICRO_STROKE_SPAN
        val turnThreshold = if (microStroke) MICRO_TURN_COS else NORMAL_TURN_COS

        fun shouldPreserve(index: Int): Boolean {
            val previous = clean[index - 1]
            val point = clean[index]
            val next = clean[index + 1]
            val inX = point.x - previous.x; val inY = point.y - previous.y
            val outX = next.x - point.x; val outY = next.y - point.y
            val inLength = hypot(inX, inY); val outLength = hypot(outX, outY)
            if (inLength <= DUPLICATE_EPSILON || outLength <= DUPLICATE_EPSILON) return true

            // A large per-sample jump usually means a fast stroke; avoid inventing a broad curve
            // across it because that is exactly where quick small letters lose their shape.
            if (max(inLength, outLength) >= FAST_SAMPLE_GAP) return true

            val turnCos = ((inX * outX + inY * outY) / (inLength * outLength)).coerceIn(-1f, 1f)
            return turnCos < turnThreshold
        }

        val result = ArrayList<InkPoint>(clean.size * 2)
        var sectionStart = 0

        fun appendSection(endInclusive: Int) {
            if (endInclusive <= sectionStart) return
            val section = clean.subList(sectionStart, endInclusive + 1)
            val smoothed = if (section.size <= 2) section else InkGeometry.smooth(section, preserveEndpoints = true)
            // Both sections retain the exact shared endpoint, so only its duplicate is skipped.
            if (result.isEmpty()) result.addAll(smoothed)
            else result.addAll(smoothed.drop(1))
            sectionStart = endInclusive
        }

        for (index in 1 until clean.lastIndex) {
            if (shouldPreserve(index)) appendSection(index)
        }
        appendSection(clean.lastIndex)
        return result
    }

    /**
     * Prefix-stable geometry for the pen stroke currently being drawn.
     *
     * [handwritingCentreline] cuts a stroke into spline sections at tight turns and sparse gaps and
     * rejoins them through the exact shared sample. Every one of those cuts looks at one sample
     * either side of a candidate, so once a candidate's successor exists the section ending there is
     * settled: appending a sample can only extend the section still being built. This produces the
     * same geometry as the batch pass while paying for the live section alone instead of
     * re-smoothing and re-resampling the whole stroke on every frame — which is what made a long
     * stroke heavier to write the longer it got.
     *
     * Owned by one drawing surface and valid only for its stroke in progress: [update] hands back its
     * own mutable centreline, so draw what it returns before the next call.
     */
    internal class IncrementalPenStroke {
        private val clean = ArrayList<InkPoint>()
        private val committed = ArrayList<InkPoint>()
        private var liveTail: List<InkPoint> = emptyList()
        private val centre = object : AbstractList<InkPoint>() {
            private val skip get() = if (committed.isEmpty()) 0 else 1
            override val size get() = committed.size + (liveTail.size - skip).coerceAtLeast(0)
            override fun get(index: Int): InkPoint = if (index < committed.size) committed[index]
                else liveTail[index - committed.size + skip]
        }
        private val sectionSmoother = InkGeometry.IncrementalSmooth(preserveEndpoints = true)
        private var foldedRaw = 0
        private var sectionStart = 0
        private var nextCandidate = 1
        private var microStroke = false
        private var rawMinX = Float.MAX_VALUE; private var rawMinY = Float.MAX_VALUE
        private var rawMaxX = -Float.MAX_VALUE; private var rawMaxY = -Float.MAX_VALUE
        private var cleanMinX = Float.MAX_VALUE; private var cleanMinY = Float.MAX_VALUE
        private var cleanMaxX = -Float.MAX_VALUE; private var cleanMaxY = -Float.MAX_VALUE
        private var cached: RenderedStroke? = null
        private var ink = AndroidxInk.Geometry()
        private var widthBuffer = FloatArray(16)
        /** Distance along the centreline to each point, kept only while the start of the stroke is tapered. */
        private var along = FloatArray(16)
        /** Fixed for the stroke, so a setting changed mid-stroke cannot make its start inconsistent. */
        private val taperLength = InkTaper.length()
        private var stableWidthCount = 0
        private var nextStableWidthCount = 0

        /**
         * Geometry for the raw [points] so far. Only samples that arrived since the previous call
         * are folded in; a list shorter than last time re-arms the pass, so a reused list is never
         * trusted to still be the same prefix.
         */
        fun update(points: List<InkPoint>): RenderedStroke {
            if (points.size < foldedRaw) restart()
            var folded = false
            while (foldedRaw < points.size) {
                val point = points[foldedRaw++]
                folded = true
                if (point.x < rawMinX) rawMinX = point.x
                if (point.x > rawMaxX) rawMaxX = point.x
                if (point.y < rawMinY) rawMinY = point.y
                if (point.y > rawMaxY) rawMaxY = point.y
                val last = if (clean.isEmpty()) null else clean[clean.size - 1]
                if (last == null || hypot(point.x - last.x, point.y - last.y) > DUPLICATE_EPSILON) {
                    clean += point
                    if (point.x < cleanMinX) cleanMinX = point.x
                    if (point.x > cleanMaxX) cleanMaxX = point.x
                    if (point.y < cleanMinY) cleanMinY = point.y
                    if (point.y > cleanMaxY) cleanMaxY = point.y
                }
            }
            if (!folded) cached?.let { return it }
            // A stroke that reads as small keeps its tight turns; crossing that span changes the
            // threshold, so every earlier split has to be taken again. Bounds only grow with the
            // prefix, so this flips at most once per stroke.
            val micro = hypot(cleanMaxX - cleanMinX, cleanMaxY - cleanMinY) <= MICRO_STROKE_SPAN
            if (micro != microStroke) { microStroke = micro; retakeSplits() }
            nextStableWidthCount = 0
            val centreline =
                if (points.size < 3) points
                else if (clean.size < 3) clean
                else smoothed()
            // Taper is neutral; reuse the pressure buffer and touch only the changing spline
            // tail. A settled point's pressure never changes on a later update.
            if (widthBuffer.size < centreline.size) {
                var capacity = widthBuffer.size
                while (capacity < centreline.size) capacity *= 2
                widthBuffer = widthBuffer.copyOf(capacity)
                along = along.copyOf(capacity)
            }
            for (i in stableWidthCount.coerceAtMost(centreline.size) until centreline.size) {
                widthBuffer[i] = penPressureScale(centreline[i].pressure)
                // The tip is still moving, so only the start can be thinned live; the end joins on pen-up.
                if (taperLength > 0f) {
                    along[i] = if (i == 0) 0f
                        else along[i - 1] + hypot(centreline[i].x - centreline[i - 1].x, centreline[i].y - centreline[i - 1].y)
                    widthBuffer[i] *= InkTaper.scaleAt(along[i], taperLength)
                }
            }
            stableWidthCount = nextStableWidthCount
            val widths = if (centreline.size >= 2) widthBuffer else null
            // Bounds stay on the stored samples, exactly as `rendered` measures them.
            val rendered = if (points.isEmpty()) RenderedStroke(centreline, null, 0f, 0f, 0f, 0f, widths)
            else RenderedStroke(centreline, null, rawMinX, rawMinY, rawMaxX, rawMaxY, widths)
            rendered.live = true
            rendered.stableCount = stableWidthCount.coerceAtMost(centreline.size)
            rendered.ink = ink
            cached = rendered
            return rendered
        }

        /** Settles every candidate whose successor has arrived, then rebuilds only the live tail. */
        private fun smoothed(): List<InkPoint> {
            val turnThreshold = if (microStroke) MICRO_TURN_COS else NORMAL_TURN_COS
            val lastDecidable = clean.size - 2
            while (nextCandidate <= lastDecidable) {
                if (shouldPreserve(nextCandidate, turnThreshold)) appendSection(nextCandidate)
                nextCandidate++
            }
            // The final section is never committed: its endpoint still moves as the tip does.
            val section = clean.subList(sectionStart, clean.size)
            liveTail = if (section.size <= 2) section else sectionSmoother.update(section)
            val skip = if (committed.isEmpty()) 0 else 1
            nextStableWidthCount = committed.size +
                (sectionSmoother.stablePointCount - skip).coerceAtLeast(0)
            return centre
        }

        private fun appendSection(endInclusive: Int) {
            if (endInclusive <= sectionStart) return
            val section = clean.subList(sectionStart, endInclusive + 1)
            val smoothed = if (section.size <= 2) section else sectionSmoother.update(section)
            if (committed.isEmpty()) committed.addAll(smoothed)
            else for (i in 1 until smoothed.size) committed.add(smoothed[i])
            sectionStart = endInclusive
            sectionSmoother.reset()
        }

        private fun shouldPreserve(index: Int, turnThreshold: Float): Boolean {
            val previous = clean[index - 1]
            val point = clean[index]
            val next = clean[index + 1]
            val inX = point.x - previous.x; val inY = point.y - previous.y
            val outX = next.x - point.x; val outY = next.y - point.y
            val inLength = hypot(inX, inY); val outLength = hypot(outX, outY)
            if (inLength <= DUPLICATE_EPSILON || outLength <= DUPLICATE_EPSILON) return true
            if (max(inLength, outLength) >= FAST_SAMPLE_GAP) return true
            val turnCos = ((inX * outX + inY * outY) / (inLength * outLength)).coerceIn(-1f, 1f)
            return turnCos < turnThreshold
        }

        private fun retakeSplits() {
            ink = AndroidxInk.Geometry()
            sectionSmoother.reset()
            committed.clear(); liveTail = emptyList(); sectionStart = 0; nextCandidate = 1; cached = null
            stableWidthCount = 0
        }

        private fun restart() {
            clean.clear(); retakeSplits()
            foldedRaw = 0
            microStroke = false
            rawMinX = Float.MAX_VALUE; rawMinY = Float.MAX_VALUE
            rawMaxX = -Float.MAX_VALUE; rawMaxY = -Float.MAX_VALUE
            cleanMinX = Float.MAX_VALUE; cleanMinY = Float.MAX_VALUE
            cleanMaxX = -Float.MAX_VALUE; cleanMaxY = -Float.MAX_VALUE
        }
    }

    /**
     * Raw stylus pressure is deliberately compressed around the selected pen width. Fast light
     * strokes stay visible instead of collapsing to a hairline, while hard presses still thicken a
     * little. p=1 remains exactly the chosen width.
     */
    internal fun penPressureScale(pressure: Float): Float {
        val p = pressure.coerceIn(.25f, 1.8f)
        return if (p <= 1f) 0.72f + 0.28f * sqrt(p) else 1f + 0.20f * (p - 1f)
    }

    fun page(canvas: Canvas, page: NotePage, background: Bitmap?, ink: Boolean = true, images: Map<String, Bitmap?>? = null) {
        // Hidden layers do not print, export or preview; the layer order is the draw order.
        pageCached(canvas, PageLayers.view(page), background, ink, images, ::rawBounds, ::rendered)
    }

    /**
     * Smoothed geometry for one stroke, safe to memoize by object identity: strokes are
     * immutable, so the centreline, taper and bounds never change while the instance lives.
     * Untouched strokes keep their identity through erasing ([InkGeometry.erase] returns the
     * same instance when nothing reaches it) and through page copies, so a per-view cache
     * keyed by identity pays the spline math once per stroke instead of once per frame.
     */
    data class RenderedStroke(
        val centre: List<InkPoint>,
        /** Per-point width multipliers for [Tool.PEN], or null for paths drawn at uniform width. */
        val taper: FloatArray?,
        val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
        /**
         * Per-point combined pressure×taper multipliers for [Tool.PEN], or null otherwise.
         * Precomputed once with the spline so every frame after pays one add+multiply per
         * segment instead of a sqrt per segment — which is what made dense pages re-pay
         * pressure math on every raster. Compare via [widthsEqual], not `==`: FloatArray
         * equality is referential, like [taper].
         */
        val widths: FloatArray? = null
    ) {
        internal var live = false
        internal var stableCount: Int = centre.size
        internal var ink: AndroidxInk.Geometry? = null
    }

    /** Structural equality for cached width multipliers; FloatArray `==` is referential. */
    internal fun widthsEqual(a: FloatArray?, b: FloatArray?): Boolean {
        if (a === b) return true
        if (a == null || b == null || a.size != b.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    /**
     * Per-point pressure×taper multipliers for a pen centreline. One sqrt per point here
     * replaces one sqrt per segment per frame in the old draw loop.
     */
    internal fun widthMultipliers(centre: List<InkPoint>, taper: FloatArray?): FloatArray? {
        if (centre.size < 2 || taper == null || taper.size != centre.size) return null
        return FloatArray(centre.size) { i ->
            penPressureScale(centre[i].pressure) * taper[i]
        }
    }

    /** Pure geometry for [stroke]: centreline, taper and raw bounds. No Android types. */
    fun rendered(stroke: Stroke): RenderedStroke {
        val points = InkGeometry.pathPoints(stroke)
        val centre = when {
            stroke.tool in SHAPE_SET -> points
            stroke.tool == Tool.PEN -> handwritingCentreline(points)
            else -> InkGeometry.smooth(points)
        }
        val taper = if (stroke.tool == Tool.PEN && centre.size >= 2) InkGeometry.taperScalesArray(centre) else null
        val widths = if (stroke.tool == Tool.PEN) widthMultipliers(centre, taper) else null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        // Raw points already bound the rendered geometry (shapes re-derive from two corners),
        // so there is no need to expand an ellipse into 65 samples just to measure it.
        val raw = stroke.points
        if (raw.isEmpty()) {
            minX = 0f; minY = 0f; maxX = 0f; maxY = 0f
        } else for (p in raw) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return RenderedStroke(centre, taper, minX, minY, maxX, maxY, widths)
    }

    /** Raw bounds of [stroke] as `[minX, minY, maxX, maxY]`. No spline math: O(points). */
    fun rawBounds(stroke: Stroke): FloatArray {
        val pts = stroke.points
        if (pts.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** True when `[minX, minY, maxX, maxY]` plus the stroke-width margin hits the clip. */
    fun boundsVisible(bounds: FloatArray, strokeWidth: Float, clip: Rect): Boolean {
        if (bounds.size < 4) return false
        val margin = strokeWidth * 2f + 8f
        return bounds[2] + margin >= clip.left && bounds[0] - margin <= clip.right &&
            bounds[3] + margin >= clip.top && bounds[1] - margin <= clip.bottom
    }

    /**
     * Draws [stroke] from its precomputed [rendered] geometry. Identical output to
     * [stroke], but without repeating the spline, taper or bounds math per frame.
     */
    fun drawRendered(canvas: Canvas, stroke: Stroke, rendered: RenderedStroke, ahead: FloatArray? = null, aheadCount: Int = 0) {
        if ((stroke.tool == Tool.PEN || stroke.tool == Tool.HIGHLIGHTER || stroke.tool in SHAPE_SET) &&
            (stroke.style == StrokeStyle.SOLID || stroke.style.isBrush)) {
            val ink = synchronized(rendered) {
                rendered.ink ?: AndroidxInk.Geometry().also { rendered.ink = it }
            }
            if (ink.draw(canvas, stroke, rendered, ahead, aheadCount)) return
        }
        val centre = rendered.centre
        if (centre.isEmpty()) return
        val paint = strokePaintPool.get()!!.apply {
            color = stroke.color; alpha = (Color.alpha(stroke.color) * stroke.opacity).toInt().coerceIn(0, 255); strokeWidth = stroke.width; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
            pathEffect = if (stroke.tool in SHAPE_SET) dashEffect(stroke.style, stroke.width) else null
        }
        if (centre.size < 2) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            val widthScale = if (stroke.tool == Tool.PEN) penPressureScale(centre[0].pressure) else centre[0].pressure
            canvas.drawCircle(centre[0].x, centre[0].y, stroke.width * widthScale / 2, paint)
            return
        }
        if (stroke.tool == Tool.HIGHLIGHTER || stroke.tool in SHAPE_SET) {
            canvas.drawPath(polylinePath(centre), paint)
            return
        }
        paint.pathEffect = null
        drawTaperedLines(canvas, paint, centre,
            rendered.taper, stroke.width, rendered.widths)
        paint.pathEffect = null
    }

    /** Navigation previews share the cached AndroidX meshes, so ink never changes brush while panning or zooming. */
    fun drawPreview(canvas: Canvas, stroke: Stroke, rendered: RenderedStroke) {
        if ((stroke.tool == Tool.PEN || stroke.tool == Tool.HIGHLIGHTER || stroke.tool in SHAPE_SET) &&
            (stroke.style == StrokeStyle.SOLID || stroke.style.isBrush) && !AndroidxInk.failed) {
            drawRendered(canvas, stroke, rendered)
            return
        }
        val centre = rendered.centre
        if (centre.isEmpty()) return
        val paint = strokePaintPool.get()!!.apply {
            color = stroke.color; alpha = (Color.alpha(stroke.color) * stroke.opacity).toInt().coerceIn(0, 255); strokeWidth = stroke.width; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
            pathEffect = if (stroke.tool in SHAPE_SET) dashEffect(stroke.style, stroke.width) else null
        }
        if (centre.size < 2) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            val widthScale = if (stroke.tool == Tool.PEN) penPressureScale(centre[0].pressure) else centre[0].pressure
            canvas.drawCircle(centre[0].x, centre[0].y, stroke.width * widthScale / 2, paint)
            return
        }
        canvas.drawPath(polylinePath(centre), paint)
    }

    /** The cached [stroke] path, rebuilt in a pooled [Path] without allocating a sublist per frame. */
    private fun polylinePath(centre: List<InkPoint>): Path {
        val path = strokePathPool.get()!!
        path.reset()
        path.moveTo(centre[0].x, centre[0].y)
        for (i in 1 until centre.size) path.lineTo(centre[i].x, centre[i].y)
        return path
    }

    /**
     * Pressure- and taper-varying ink as one filled outline ([InkOutline], one contour per stroke): one `drawPath`.
     * Width multipliers come precomputed from [RenderedStroke.widths] (one sqrt per point, once).
     */
    private fun drawTaperedLines(
        canvas: Canvas,
        paint: Paint,
        centre: List<InkPoint>,
        taper: FloatArray?,
        width: Float,
        widths: FloatArray?
    ) {
        if (centre.size < 2) return
        val mults: FloatArray = if (widths != null && widths.size >= centre.size) widths
        else {
            // Fallback for geometry built without multipliers: one sqrt per point (not per
            // segment), then the same bucketed runs below.
            val t = if (taper != null && taper.size == centre.size) taper else null
            FloatArray(centre.size) { i ->
                penPressureScale(centre[i].pressure) * (t?.get(i) ?: 1f)
            }
        }
        var low = mults[0]; var high = mults[0]
        for (i in 1 until centre.size) { val m = mults[i]; if (m < low) low = m; if (m > high) high = m }
        if ((high - low) * width < EVEN_WIDTH) {
            // An even width swept by a round nib is exactly a round-capped, round-joined line: one cheap stroke.
            paint.strokeWidth = width * (low + high) / 2f
            canvas.drawPath(polylinePath(centre), paint)
            return
        }
        // One filled outline: the width follows every sample exactly, so pressure and soft ends change
        // smoothly rather than in stepped runs, and translucent ink never darkens where runs overlap.
        val path = strokePathPool.get()!!
        path.reset()
        path.fillType = Path.FillType.WINDING
        val sink = pathSinkPool.get()!!.also { it.path = path }
        InkOutline.build(centre, { i -> width * mults[i] / 2f }, sink)
        val style = paint.style
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        paint.style = style
    }

    /** Feeds [InkOutline] straight into a pooled [Path], so building an outline allocates nothing. */
    private class PathSink : InkOutline.Sink {
        var path: Path? = null
        override fun moveTo(x: Float, y: Float) { path!!.moveTo(x, y) }
        override fun lineTo(x: Float, y: Float) { path!!.lineTo(x, y) }
        override fun close() { path!!.close() }
    }
    private val pathSinkPool = ThreadLocal.withInitial { PathSink() }

    /** Growable float buffer for batched [Canvas.drawLines] / [Canvas.drawPoints]. */
    private class PaperBatch {
        var coords = FloatArray(1024); var size = 0
        fun point(x: Float, y: Float) { ensure(2); coords[size++] = x; coords[size++] = y }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) { ensure(4); coords[size++] = x0; coords[size++] = y0; coords[size++] = x1; coords[size++] = y1 }
        private fun ensure(more: Int) { if (size + more > coords.size) coords = coords.copyOf(max(coords.size * 2, size + more)) }
        fun lines(canvas: Canvas, paint: Paint) { if (size > 0) canvas.drawLines(coords, 0, size, paint); size = 0 }
        fun points(canvas: Canvas, paint: Paint) { if (size > 0) canvas.drawPoints(coords, 0, size, paint); size = 0 }
    }
    private fun paperBatches(): Array<PaperBatch> = paperBatchPool.get()!!.also { batches -> batches.forEach { it.size = 0 } }
    /** Width spread (page units) below which a pen stroke draws as a plain line rather than an outline. */
    private const val EVEN_WIDTH = .02f

    /**
     * Selection halo for one stroke, drawn from its cached centreline so a drag never
     * re-smooths the selection per frame. Same geometry as drawing a translated copy via
     * [stroke], but without allocating a new stroke or paying the spline math again.
     */
    fun drawHalo(canvas: Canvas, stroke: Stroke, rendered: RenderedStroke, color: Int) {
        val centre = rendered.centre
        if (centre.isEmpty()) return
        val paint = strokePaintPool.get()!!.apply {
            this.color = color; alpha = (Color.alpha(color) * 0.35f).toInt().coerceIn(0, 255)
            strokeWidth = stroke.width + 14f; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
            pathEffect = if (stroke.tool in SHAPE_SET) dashEffect(stroke.style, stroke.width + 14f) else null
        }
        if (centre.size < 2) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            val widthScale = if (stroke.tool == Tool.PEN) penPressureScale(centre[0].pressure) else centre[0].pressure
            canvas.drawCircle(centre[0].x, centre[0].y, (stroke.width + 14f) * widthScale / 2, paint)
            return
        }
        if (stroke.tool == Tool.HIGHLIGHTER || stroke.tool in SHAPE_SET) {
            canvas.drawPath(polylinePath(centre), paint)
            return
        }
        paint.pathEffect = null
        drawTaperedLines(canvas, paint, centre,
            rendered.taper, stroke.width + 14f, rendered.widths)
        paint.pathEffect = null
    }

    /**
     * Same as [page], but stroke geometry comes from [renderOf] so a view holding an
     * identity-keyed cache smooths each committed stroke once and reuses it for every
     * frame, recording and visibility check instead of re-walking every sample.
     * Bounds come from [boundsOf] first, so off-screen strokes never pay the spline math.
     */
    fun pageCached(
        canvas: Canvas,
        page: NotePage,
        background: Bitmap?,
        ink: Boolean = true,
        images: Map<String, Bitmap?>? = null,
        boundsOf: (Stroke) -> FloatArray = ::rawBounds,
        renderOf: (Stroke) -> RenderedStroke = ::rendered,
        drawInk: ((Canvas) -> Unit)? = null,
        backgroundDetail: ((Canvas) -> Unit)? = null,
        paper: Boolean = true,
    ) {
        if (paper) canvas.drawColor(Color.WHITE)
        // A paperless view draws over a sheet its host lays out underneath.
        if (!paper) Unit
        else if (background != null) {
            val dst = bitmapRectPool.get()!!.apply { set(0f, 0f, page.width, page.height) }
            canvas.drawBitmap(background, null, dst, bitmapPaintPool.get()!!)
            backgroundDetail?.invoke(canvas)
        }
        else if (page.infinite) infinitePaper(canvas, page)
        else if (page.pdfIndex == null) {
            when (page.paper) {
                Paper.RULED, Paper.SPLIT_RULED -> drawRuled(canvas, page)
                Paper.GRID -> drawGrid(canvas, page, spacing = 28f, minorAlpha = 0xFFE0E0DA.toInt(), majorEvery = -1)
                Paper.MATH_GRID -> drawMathGrid(canvas, page)
                Paper.GRAPH -> drawGraph(canvas, page)
                Paper.DOTS -> drawDots(canvas, page)
                Paper.MC_SHEET -> drawMultipleChoice(canvas, page)
                Paper.TIAN_GRID -> drawHanzi(canvas, page, mi = false)
                Paper.MI_GRID -> drawHanzi(canvas, page, mi = true)
                Paper.PLAIN -> Unit
            }
        }
        if (ink) {
            val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
            page.images.forEach { box ->
                if (!rectVisible(box.x, box.y, box.x + box.width, box.y + box.height, clip)) return@forEach
                images?.get(box.id)?.let { image(canvas, it, box) }
            }
            if (drawInk != null) drawInk(canvas) else page.strokes.forEach { stroke ->
                // Bounds first: off-screen ink never pays the spline math, so a dense page
                // only smooths what is actually on screen.
                if (!boundsVisible(boundsOf(stroke), stroke.width, clip)) return@forEach
                drawRendered(canvas, stroke, renderOf(stroke))
            }
            page.texts.forEach {
                val h = textHeight(it)
                if (rectVisible(it.x, it.y, it.x + it.width, it.y + h, clip)) text(canvas, it)
            }
        }
    }

    /**
     * Only the Folio content of a page (placed images, ink strokes, typed text) with no paper,
     * background or white fill, for a transparent preservation overlay. The caller must start
     * with a transparent bitmap; drawing uses source-over blending so highlighter translucency
     * survives compositing over the original PDF in the viewer.
     */
    fun overlay(canvas: Canvas, source: NotePage, images: Map<String, Bitmap?>? = null) {
        val page = PageLayers.view(source)
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        page.images.forEach { box ->
            if (!rectVisible(box.x, box.y, box.x + box.width, box.y + box.height, clip)) return@forEach
            images?.get(box.id)?.let { image(canvas, it, box) }
        }
        page.strokes.forEach { stroke ->
            if (!boundsVisible(rawBounds(stroke), stroke.width, clip)) return@forEach
            drawRendered(canvas, stroke, rendered(stroke))
        }
        page.texts.forEach {
            if (it.text.isBlank() && !it.isSticky) return@forEach
            val h = textHeight(it)
            if (rectVisible(it.x, it.y, it.x + it.width, it.y + h, clip)) text(canvas, it)
        }
    }

    /** Clears memoized text layouts under memory pressure; layouts are rebuilt on demand. */
    fun trimMemory() {
        synchronized(textLayoutCache) { textLayoutCache.clear() }
        TextMathBitmaps.trimMemory()
    }

    private fun rectVisible(l: Float, t: Float, r: Float, b: Float, clip: Rect): Boolean =
        r >= clip.left && l <= clip.right && b >= clip.top && t <= clip.bottom

    /** A placed photo drawn into its box, scaled to fill while keeping the bitmap filtered. */
    fun image(canvas: Canvas, bitmap: Bitmap, box: PageImage) {
        if (box.width <= 0f || box.height <= 0f) return
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val pixels = ImageTransforms.sourcePixels(bitmap.width, bitmap.height, box)
        val src = android.graphics.Rect(
            pixels[0].toInt().coerceIn(0, bitmap.width - 1),
            pixels[1].toInt().coerceIn(0, bitmap.height - 1),
            pixels[2].toInt().coerceIn(1, bitmap.width),
            pixels[3].toInt().coerceIn(1, bitmap.height)
        )
        if (src.right <= src.left || src.bottom <= src.top) return
        val rotation = box.normalizedRotation()
        if (rotation == 0) {
            val dst = bitmapRectPool.get()!!.apply { set(box.x, box.y, box.x + box.width, box.y + box.height) }
            canvas.drawBitmap(bitmap, src, dst, bitmapPaintPool.get()!!)
            return
        }
        // Non-destructive content turn: the frame stays axis-aligned for hit-testing while the
        // cropped source is drawn through a centre rotation. Sideways turns draw through the
        // transposed frame so the visible photo keeps its aspect instead of stretching.
        val cx = box.x + box.width / 2f
        val cy = box.y + box.height / 2f
        val dstBounds = ImageTransforms.rotatedDrawRect(box)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotation.toFloat())
        canvas.translate(-cx, -cy)
        val dst = bitmapRectPool.get()!!.apply { set(dstBounds[0], dstBounds[1], dstBounds[2], dstBounds[3]) }
        canvas.drawBitmap(bitmap, src, dst, bitmapPaintPool.get()!!)
        canvas.restore()
    }

    /** Only the visible lattice is drawn, even when the camera is far from the origin. */
    private fun infinitePaper(canvas: Canvas, page: NotePage) {
        if (page.paper == Paper.PLAIN) return
        if (page.paper.isHanzi) {
            infiniteHanzi(canvas, page.paper == Paper.MI_GRID)
            return
        }
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val bounds = clip
        val baseSpacing = if (page.paper.isGrid) page.paper.gridSpacing else 28f
        // Thin the pattern at extreme export scales instead of iterating over an enormous world.
        val stride = ceil(max(bounds.width(), bounds.height()) / (baseSpacing * 180f)).coerceAtLeast(1f)
        val spacing = baseSpacing * stride
        val paint = paperMinorPaint
        val firstX = floor(bounds.left / spacing).toInt()
        val lastX = ceil(bounds.right / spacing).toInt()
        val firstY = floor(bounds.top / spacing).toInt()
        val lastY = ceil(bounds.bottom / spacing).toInt()
        val batch = paperBatches()[0]
        for (row in firstY..lastY) {
            val y = row * spacing
            if (page.paper == Paper.DOTS) {
                for (column in firstX..lastX) batch.point(column * spacing, y)
            } else batch.line(bounds.left.toFloat(), y, bounds.right.toFloat(), y)
        }
        if (page.paper == Paper.DOTS) batch.points(canvas, paperDotPaint)
        if (page.paper.isGrid) for (column in firstX..lastX) {
            val x = column * spacing
            batch.line(x, bounds.top.toFloat(), x, bounds.bottom.toFloat())
        }
        batch.lines(canvas, paint)
        if (page.paper == Paper.SPLIT_RULED) {
            canvas.drawLine(page.width / 2f, bounds.top.toFloat(), page.width / 2f, bounds.bottom.toFloat(), paperMinorPaint)
        }
        if (page.paper == Paper.GRAPH) {
            // Dedicated axis paint: never mutate the shared minor paint (races + leaks state).
            canvas.drawLine(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), graphAxisPaint)
            canvas.drawLine(bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f, graphAxisPaint)
        }
    }

    /** A finite, translated copy for previews and exports; stored coordinates stay untouched. */
    /** With [tight], an infinite canvas is cropped to its content instead of also reaching back to the origin. */
    fun exportPage(source: NotePage, tight: Boolean = false): NotePage {
        val exportable = source.copy(texts = source.texts.filter { StickyNotes.exports(it, source) })
        val page = PageLayers.view(exportable)
        if (!page.infinite) return exportable
        val hasContent = page.strokes.any { it.points.isNotEmpty() } || page.texts.isNotEmpty() || page.images.isNotEmpty()
        val crop = tight && hasContent
        var left = if (crop) Float.MAX_VALUE else 0f; var top = if (crop) Float.MAX_VALUE else 0f
        var right = if (crop) -Float.MAX_VALUE else page.width; var bottom = if (crop) -Float.MAX_VALUE else page.height
        page.strokes.forEach { stroke ->
            val pad = stroke.width * 2f + 24f
            stroke.points.forEach {
                left = min(left, it.x - pad); top = min(top, it.y - pad)
                right = max(right, it.x + pad); bottom = max(bottom, it.y + pad)
            }
        }
        page.texts.forEach {
            left = min(left, it.x - 24f); top = min(top, it.y - 24f)
            right = max(right, it.x + it.width + 24f); bottom = max(bottom, it.y + textHeight(it) + 24f)
        }
        page.images.forEach {
            left = min(left, it.x - 24f); top = min(top, it.y - 24f)
            right = max(right, it.x + it.width + 24f); bottom = max(bottom, it.y + it.height + 24f)
        }
        return page.copy(width = right - left, height = bottom - top,
            strokes = page.strokes.map { InkGeometry.translate(it, -left, -top) },
            texts = page.texts.map { it.moved(-left, -top) },
            images = page.images.map { it.moved(-left, -top) })
    }

    private fun mathKeys(box: TextBox): List<TextMathBitmaps.Key> {
        val size = TextMathBitmaps.quantize(if (box.isSticky) StickyNotes.renderedTextSize(box.size) else box.size.coerceIn(TextBox.MIN_SIZE, TextBox.MAX_SIZE))
        return mathParts(box.text).orEmpty().filterIsInstance<TextMath.Part.Formula>()
            .map { TextMathBitmaps.Key(it.latex, it.display, size, box.color) }
    }

    /**
     * Waits (at most [timeoutMs]) until every formula in [box] has a picture or has failed, so a height
     * measured from [textLayout] afterwards is the one it will be drawn at. Needs an editor on screen.
     */
    suspend fun awaitMath(box: TextBox, timeoutMs: Long = 8_000L) {
        val keys = mathKeys(box).ifEmpty { return }
        keys.forEach(TextMathBitmaps::want)
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (!keys.all(TextMathBitmaps::settled)) kotlinx.coroutines.delay(50)
        }
    }

    /**
     * The measured layout for a text box. Drawing and hit-testing share it, so a box is tapped and
     * dragged exactly where it is drawn, and its height is never stored out of date.
     * Layouts are cached because measuring text on every frame made scrolling past text-heavy
     * pages visibly heavy; boxes are immutable so the cache key is the box itself.
     */
    fun textLayout(box: TextBox): StaticLayout {
        val parts = mathParts(box.text)
        val size = if (box.isSticky) StickyNotes.renderedTextSize(box.size) else box.size.coerceIn(TextBox.MIN_SIZE, TextBox.MAX_SIZE)
        // Which formulas already have a picture: the layout is only valid for that state.
        val pictures = parts?.filterIsInstance<TextMath.Part.Formula>()?.map { f ->
            val key = TextMathBitmaps.Key(f.latex, f.display, TextMathBitmaps.quantize(size), box.color)
            TextMathBitmaps.peek(key).also { if (it == null) TextMathBitmaps.want(key) }
        }
        val signature = pictures?.fold(1) { acc, p -> acc * 2 + if (p != null) 1 else 0 } ?: 0
        val cacheKey = TextLayoutKey(box, signature)
        synchronized(textLayoutCache) { textLayoutCache[cacheKey]?.let { return it } }
        val opacity = (Color.alpha(box.color) * box.opacity.coerceIn(TextBox.MIN_OPACITY, TextBox.MAX_OPACITY))
            .toInt().coerceIn(0, 255)
        val value: CharSequence = if (parts == null) box.text.ifEmpty { " " } else {
            val built = SpannableStringBuilder()
            val next = pictures!!.iterator()
            var ownLine = false
            parts.forEachIndexed { i, part ->
                when (part) {
                    is TextMath.Part.Text -> {
                        // A display formula already ended its own line, so drop the spaces and line break after it.
                        val text = if (ownLine) part.text.trimStart(' ', '\t').removePrefix("\n") else part.text
                        built.append(text)
                        ownLine = false
                    }
                    is TextMath.Part.Formula -> {
                        val picture = next.next()
                        if (picture == null) built.append(part.source)
                        else {
                            if (part.display && built.isNotEmpty() && built.last() != '\n') built.append('\n')
                            val at = built.length
                            built.append('\uFFFC')
                            built.setSpan(FormulaSpan(picture, part.display, box.width, opacity), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            if (part.display && i < parts.lastIndex) { built.append('\n'); ownLine = true }
                        }
                    }
                }
            }
            built
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = box.color
            alpha = opacity
            textSize = size
            typeface = if (box.bold) serifBold else serifNormal
            isFakeBoldText = box.bold
            textSkewX = if (box.italic) -0.25f else 0f
            isUnderlineText = box.underline
        }
        val alignment = when (box.align) {
            TextAlignMode.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignMode.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            TextAlignMode.LEFT -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, box.width.toInt().coerceAtLeast(1))
            .setAlignment(alignment).setIncludePad(false).setLineSpacing(0f, 1.1f).build()
        synchronized(textLayoutCache) { textLayoutCache[cacheKey] = layout }
        return layout
    }

    /**
     * Notes draw every frame above the page raster, so their ink would be re-smoothed per frame.
     * Strokes are immutable, so geometry is memoized by identity per thread; bounded, and dropped
     * when the stroke-end look ([InkTaper.version]) changes.
     */
    private class StickyGeometry { val strokes = java.util.IdentityHashMap<Stroke, RenderedStroke>(); var taper = -1 }
    private val stickyGeometry by lazy { ThreadLocal.withInitial { StickyGeometry() } }
    private fun stickyRendered(stroke: Stroke): RenderedStroke {
        val memo = stickyGeometry.get()!!
        if (memo.taper != InkTaper.version || memo.strokes.size > 2048) { memo.strokes.clear(); memo.taper = InkTaper.version }
        return memo.strokes.getOrPut(stroke) { rendered(stroke) }
    }

    private class StickyPaints {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f; style = Paint.Style.STROKE }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F000000 }
    }
    // Editor, thumbnails and exports can render different paper colours concurrently.
    private val stickyPaints = ThreadLocal.withInitial { StickyPaints() }

    /** The box's rendered height, used for hit-testing and for the drag outline. */
    fun textHeight(box: TextBox): Float = if (box.isSticky) box.stickyHeight else textLayout(box).height.toFloat()

    /**
     * A text box, or a sticky note: opaque paper with a soft drop shadow, its typed words, then its
     * own ink on top, clipped to the paper so a stroke never spills onto the page around it.
     */
    fun text(canvas: Canvas, box: TextBox) {
        canvas.save()
        canvas.translate(box.x, box.y)
        if (box.isSticky) {
            val w = box.width; val h = box.stickyHeight; val r = StickyNotes.CORNER
            val paints = stickyPaints.get()!!
            canvas.drawRoundRect(0f, 2f, w, h + 2.5f, r, r, paints.shadow)
            paints.fill.color = StickyNotes.fill(box.stickyColor)
            paints.edge.color = StickyNotes.edge(box.stickyColor)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paints.fill)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paints.edge)
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            if (box.text.isNotBlank()) {
                canvas.save()
                canvas.translate(StickyNotes.PADDING, StickyNotes.PADDING)
                textLayout(box.copy(width = (w - StickyNotes.PADDING * 2).coerceAtLeast(1f), stickyInk = emptyList())).draw(canvas)
                canvas.restore()
            }
            box.stickyInk.forEach { drawRendered(canvas, it, stickyRendered(it)) }
            canvas.restore()
        } else textLayout(box).draw(canvas)
        canvas.restore()
    }

    private fun drawRuled(canvas: Canvas, page: NotePage) {
        val paint = paperMinorPaint
        val spacing = 28f
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        var y = max(70f, clip.top.toFloat())
        // Align to the ruled spacing so culling never shifts the lines.
        y -= ((y - 70f) % spacing + spacing) % spacing
        val endY = min(page.height, clip.bottom.toFloat())
        val batch = paperBatches()[0]
        while (y < endY) { batch.line(36f, y, page.width - 36f, y); y += spacing }
        batch.lines(canvas, paint)
        if (page.paper == Paper.SPLIT_RULED) canvas.drawLine(page.width / 2f, 70f, page.width / 2f, page.height, paint)
    }

    /**
     * An Exam 2 Section A answer sheet: numbered rows with A–E bubbles to shade in with the pen.
     * Drawing the bubbles as paper rather than ink keeps them out of undo, exports, and the
     * clear-page action, so the sheet always reads like a fresh answer booklet.
     */
    private fun drawMultipleChoice(canvas: Canvas, page: NotePage) {
        val ink = mcInkPaint
        val label = mcLabelPaint
        val number = mcNumberPaint
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        if (clip.top <= 40) canvas.drawText("Section A — shade one bubble per question", 36f, 40f, label)
        // Cull rows to the visible band instead of drawing ~170 circles + texts per frame.
        val firstIndex = max(1, ((clip.top - 78f) / 34f).toInt() + 1)
        var y = 78f + (firstIndex - 1) * 34f
        var index = firstIndex
        val endY = min(page.height - 30f, clip.bottom.toFloat())
        while (y < endY) {
            canvas.drawText(index.toString(), 44f, y + 4f, number)
            var letter = 0
            while (letter < 5) {
                val cx = 84f + letter * 44f
                canvas.drawCircle(cx, y, 11f, ink)
                canvas.drawText(('A' + letter).toString(), cx - 4f, y + 4f, label)
                letter++
            }
            y += 34f
            index++
        }
    }

    private fun drawDots(canvas: Canvas, page: NotePage) {
        val batch = paperBatches()[0]
        val spacing = 28f
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val firstY = (floor(clip.top / spacing).toInt() * spacing).coerceAtLeast(spacing)
        val lastY = min(page.height, clip.bottom.toFloat())
        val firstX = (floor(clip.left / spacing).toInt() * spacing).coerceAtLeast(spacing)
        val lastX = min(page.width, clip.right.toFloat())
        var y = firstY
        while (y < lastY) {
            var x = firstX
            while (x < lastX) { batch.point(x, y); x += spacing }
            y += spacing
        }
        batch.points(canvas, paperDotPaint)
    }

    private fun drawGrid(canvas: Canvas, page: NotePage, spacing: Float, minorAlpha: Int, majorEvery: Int) {
        val minor = if (minorAlpha == 0xFFE0E0DA.toInt()) paperGridMinorPaint
            else Paint(Paint.ANTI_ALIAS_FLAG).apply { color = minorAlpha; strokeWidth = .7f }
        val major = paperGridMajorPaint
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val left = max(0f, clip.left.toFloat()); val right = min(page.width, clip.right.toFloat())
        val top = max(0f, clip.top.toFloat()); val bottom = min(page.height, clip.bottom.toFloat())
        val (minorLines, majorLines) = paperBatches()
        var x = (ceil(left / spacing).toInt() * spacing).coerceAtLeast(spacing)
        var idx = (x / spacing).roundToInt()
        while (x < right) {
            (if (majorEvery > 0 && idx % majorEvery == 0) majorLines else minorLines).line(x, top, x, bottom); x += spacing; idx++
        }
        var y = (ceil(top / spacing).toInt() * spacing).coerceAtLeast(spacing)
        idx = (y / spacing).roundToInt()
        while (y < bottom) {
            (if (majorEvery > 0 && idx % majorEvery == 0) majorLines else minorLines).line(left, y, right, y); y += spacing; idx++
        }
        minorLines.lines(canvas, minor); majorLines.lines(canvas, major)
    }

    /**
     * Math practice grid: minor 20 px light grid with a bolder line every 5 squares (100 px).
     * The tighter spacing keeps fractions and small diagrams proportional, while the bold
     * lines give a quick 5-unit reference without overpowering handwriting.
     */
    private fun drawMathGrid(canvas: Canvas, page: NotePage) {
        val spacing = 20f
        val minor = paperMathMinorPaint
        val major = paperMathMajorPaint
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val left = max(0f, clip.left.toFloat()); val right = min(page.width, clip.right.toFloat())
        val top = max(0f, clip.top.toFloat()); val bottom = min(page.height, clip.bottom.toFloat())
        // Minor grid culled to the viewport; major every 5.
        val (minorLines, majorLines) = paperBatches()
        var x = (ceil(left / spacing).toInt() * spacing).coerceAtLeast(spacing)
        var i = (x / spacing).roundToInt()
        while (x < right) {
            (if (i % 5 == 0) majorLines else minorLines).line(x, top, x, bottom); x += spacing; i++
        }
        var y = (ceil(top / spacing).toInt() * spacing).coerceAtLeast(spacing)
        i = (y / spacing).roundToInt()
        while (y < bottom) {
            (if (i % 5 == 0) majorLines else minorLines).line(left, y, right, y); y += spacing; i++
        }
        minorLines.lines(canvas, minor); majorLines.lines(canvas, major)
    }

    /**
     * Graph paper: same math grid plus bold centered axes with arrowheads.
     * Axes sit at the center of the page so a new page is immediately usable for
     * coordinate geometry; tick marks every grid unit fall on the axes themselves.
     */
    private fun drawGraph(canvas: Canvas, page: NotePage) {
        drawMathGrid(canvas, page)
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val cx = page.width / 2f
        val cy = page.height / 2f
        // Skip off-screen axes work when the viewport is far from the centre lines.
        if (cy < clip.top || cy > clip.bottom) {
            // Still draw vertical axis below; horizontal axis culled.
        } else {
            canvas.drawLine(max(0f, clip.left.toFloat()), cy, min(page.width, clip.right.toFloat()), cy, graphAxisPaint)
        }
        if (cx >= clip.left && cx <= clip.right) {
            canvas.drawLine(cx, max(0f, clip.top.toFloat()), cx, min(page.height, clip.bottom.toFloat()), graphAxisPaint)
        } else {
            // Both axes off-screen: ticks/arrowheads are off-screen too.
            return
        }
        val axis = graphAxisPaint
        val tick = graphTickPaint
        // Arrowheads — small V at each end so direction reads instantly
        val ah = 9f
        val p = arrowPathPool.get()!!
        // X+ (right)
        p.reset(); p.moveTo(page.width - ah, cy - ah / 1.9f); p.lineTo(page.width, cy); p.lineTo(page.width - ah, cy + ah / 1.9f); canvas.drawPath(p, axis)
        // X- (left)
        p.reset(); p.moveTo(ah, cy - ah / 1.9f); p.lineTo(0f, cy); p.lineTo(ah, cy + ah / 1.9f); canvas.drawPath(p, axis)
        // Y+ (top)
        p.reset(); p.moveTo(cx - ah / 1.9f, ah); p.lineTo(cx, 0f); p.lineTo(cx + ah / 1.9f, ah); canvas.drawPath(p, axis)
        // Y- (bottom)
        p.reset(); p.moveTo(cx - ah / 1.9f, page.height - ah); p.lineTo(cx, page.height); p.lineTo(cx + ah / 1.9f, page.height - ah); canvas.drawPath(p, axis)
        // Short tick marks on axes every 20 px (one grid unit) inside the central band
        val spacing = Paper.GRAPH.gridSpacing
        val ticks = paperBatches()[0]
        var tx = cx + spacing
        while (tx < page.width - spacing) {
            ticks.line(tx, cy - 4f, tx, cy + 4f)
            ticks.line(cx - (tx - cx), cy - 4f, cx - (tx - cx), cy + 4f)
            tx += spacing
        }
        var ty = cy + spacing
        while (ty < page.height - spacing) {
            ticks.line(cx - 4f, ty, cx + 4f, ty)
            ticks.line(cx - 4f, cy - (ty - cy), cx + 4f, cy - (ty - cy))
            ty += spacing
        }
        ticks.lines(canvas, tick)
    }

    /**
     * Hanzi practice paper: large squares with printed guides, like a Chinese exercise book.
     * Tian (田) draws a dashed cross in each square; mi (米) adds the two diagonals.
     * The block is centred so a partial cell never clings to one edge, and guides are paper —
     * never ink — so writing, undo and clear-page leave them alone.
     */
    private fun drawHanzi(canvas: Canvas, page: NotePage, mi: Boolean) {
        val cell = Paper.HANZI_CELL
        val cols = (page.width / cell).toInt().coerceAtLeast(1)
        val rows = (page.height / cell).toInt().coerceAtLeast(1)
        val left = (page.width - cols * cell) / 2f
        val top = (page.height - rows * cell) / 2f
        val right = left + cols * cell
        val bottom = top + rows * cell
        val outer = hanziOuterPaint
        val guide = hanziGuidePaint
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val cLeft = max(left, clip.left.toFloat()); val cRight = min(right, clip.right.toFloat())
        val cTop = max(top, clip.top.toFloat()); val cBottom = min(bottom, clip.bottom.toFloat())
        if (cLeft > cRight || cTop > cBottom) return
        val firstCol = max(0, floor((cLeft - left) / cell).toInt())
        val lastCol = min(cols, ceil((cRight - left) / cell).toInt())
        val firstRow = max(0, floor((cTop - top) / cell).toInt())
        val lastRow = min(rows, ceil((cBottom - top) / cell).toInt())
        for (i in firstCol..lastCol) {
            val x = left + i * cell
            canvas.drawLine(x, cTop, x, cBottom, outer)
        }
        for (j in firstRow..lastRow) {
            val y = top + j * cell
            canvas.drawLine(cLeft, y, cRight, y, outer)
        }
        for (row in firstRow until lastRow) {
            for (col in firstCol until lastCol) {
                val x = left + col * cell
                val y = top + row * cell
                canvas.drawLine(x + cell / 2f, y, x + cell / 2f, y + cell, guide)
                canvas.drawLine(x, y + cell / 2f, x + cell, y + cell / 2f, guide)
                if (mi) {
                    canvas.drawLine(x, y, x + cell, y + cell, guide)
                    canvas.drawLine(x + cell, y, x, y + cell, guide)
                }
            }
        }
    }

    /** A tiled copy of [drawHanzi] for the infinite canvas, aligned to the page origin. */
    private fun infiniteHanzi(canvas: Canvas, mi: Boolean) {
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val bounds = clip
        val base = Paper.HANZI_CELL
        val stride = ceil(max(bounds.width(), bounds.height()) / (base * 180f)).coerceAtLeast(1f)
        val cell = base * stride
        val outer = hanziOuterPaint
        val guide = hanziGuidePaint
        val firstCol = floor(bounds.left / cell).toInt()
        val lastCol = ceil(bounds.right / cell).toInt()
        val firstRow = floor(bounds.top / cell).toInt()
        val lastRow = ceil(bounds.bottom / cell).toInt()
        for (col in firstCol..lastCol) {
            val x = col * cell
            canvas.drawLine(x, bounds.top.toFloat(), x, bounds.bottom.toFloat(), outer)
        }
        for (row in firstRow..lastRow) {
            val y = row * cell
            canvas.drawLine(bounds.left.toFloat(), y, bounds.right.toFloat(), y, outer)
        }
        for (row in firstRow until lastRow) {
            for (col in firstCol until lastCol) {
                val x = col * cell
                val y = row * cell
                canvas.drawLine(x + cell / 2f, y, x + cell / 2f, y + cell, guide)
                canvas.drawLine(x, y + cell / 2f, x + cell, y + cell / 2f, guide)
                if (mi) {
                    canvas.drawLine(x, y, x + cell, y + cell, guide)
                    canvas.drawLine(x + cell, y, x, y + cell, guide)
                }
            }
        }
    }

    fun stroke(canvas: Canvas, stroke: Stroke) {
        val r = rendered(stroke)
        if (r.centre.isEmpty()) return
        drawRendered(canvas, stroke, r)
    }

    /**
     * Dash pattern for a shape stroke, scaled by its width so thin and heavy lines read alike.
     * Dotted uses a zero-length dash with a round cap, which renders as evenly spaced dots.
     * Effects are cached by style + width bucket; PathEffect is immutable so sharing is safe.
     */
    internal fun dashEffect(style: StrokeStyle, width: Float): android.graphics.PathEffect? {
        if (style == StrokeStyle.SOLID || style.isBrush) return null
        val bucket = (width * 2).roundToInt()
        val key = style to bucket
        synchronized(dashCache) { dashCache[key]?.let { return it } }
        val effect = when (style) {
            StrokeStyle.DASHED -> android.graphics.DashPathEffect(floatArrayOf(14f.coerceAtLeast(width * 3f), 10f.coerceAtLeast(width * 2f)), 0f)
            StrokeStyle.DOTTED -> android.graphics.DashPathEffect(floatArrayOf(0.5f, (width * 3f).coerceAtLeast(8f)), 0f)
            StrokeStyle.LONG_DASH -> android.graphics.DashPathEffect(floatArrayOf(30f.coerceAtLeast(width * 6f), 12f.coerceAtLeast(width * 2.5f)), 0f)
            StrokeStyle.DASH_DOT -> {
                val gap = 8f.coerceAtLeast(width * 2f)
                android.graphics.DashPathEffect(floatArrayOf(20f.coerceAtLeast(width * 4f), gap, 0.5f, gap), 0f)
            }
            StrokeStyle.DENSE_DOTS -> android.graphics.DashPathEffect(floatArrayOf(0.5f, (width * 1.8f).coerceAtLeast(5f)), 0f)
            else -> null
        }
        synchronized(dashCache) {
            if (dashCache.size > 64) dashCache.clear()
            dashCache[key] = effect
        }
        return effect
    }
}
