package com.folio.notes

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.*

object InkRenderer {
    /** Tools whose geometry traces the drag rather than freehand samples, so it is never smoothed. */
    private val SHAPE_SET = setOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE)
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
    private val arrowPathPool by lazy { ThreadLocal.withInitial { Path() } }
    private val strokePathPool by lazy { ThreadLocal.withInitial { Path() } }
    private val clipRectPool by lazy { ThreadLocal.withInitial { android.graphics.Rect() } }
    private val bitmapRectPool by lazy { ThreadLocal.withInitial { RectF() } }
    // Finite-paper paints hoisted so scrolling never allocates Paint/drawText Paints per frame.
    private val mcInkPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200); strokeWidth = .9f; style = Paint.Style.STROKE } }
    private val mcLabelPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 120, 115); textSize = 11f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    } }
    private val mcNumberPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 145); textAlign = Paint.Align.RIGHT; textSize = 13f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
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
    private val textLayoutCache = object : LinkedHashMap<TextBox, StaticLayout>(MAX_CACHED_TEXT_LAYOUTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextBox, StaticLayout>?): Boolean =
            size > MAX_CACHED_TEXT_LAYOUTS
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
    private const val MIN_VISIBLE_TAPER = 0.68f

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
     * Raw stylus pressure is deliberately compressed around the selected pen width. Fast light
     * strokes stay visible instead of collapsing to a hairline, while hard presses still thicken a
     * little. p=1 remains exactly the chosen width.
     */
    internal fun penPressureScale(pressure: Float): Float {
        val p = pressure.coerceIn(.25f, 1.8f)
        return if (p <= 1f) 0.72f + 0.28f * sqrt(p) else 1f + 0.20f * (p - 1f)
    }

    fun page(canvas: Canvas, page: NotePage, background: Bitmap?, ink: Boolean = true, images: Map<String, Bitmap?>? = null) {
        canvas.drawColor(Color.WHITE)
        if (background != null) {
            val dst = bitmapRectPool.get()!!.apply { set(0f, 0f, page.width, page.height) }
            canvas.drawBitmap(background, null, dst, bitmapPaintPool.get()!!)
        }
        else if (page.infinite) infinitePaper(canvas, page)
        else if (page.pdfIndex == null) {
            when (page.paper) {
                Paper.RULED -> drawRuled(canvas, page)
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
            // Cull to the visible region: a long page only draws what is on screen, so scrolling
            // past dense ink no longer pays for the strokes above and below the viewport.
            // Reuses a thread-local Rect to avoid allocating clipBounds per frame.
            val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
            // Photos sit under the ink so handwriting annotates the picture, like GoodNotes.
            page.images.forEach { box ->
                if (!rectVisible(box.x, box.y, box.x + box.width, box.y + box.height, clip)) return@forEach
                images?.get(box.id)?.let { image(canvas, it, box) }
            }
            page.strokes.forEach { if (strokeVisible(it, clip)) stroke(canvas, it) }
            page.texts.forEach {
                val h = textHeight(it)
                if (rectVisible(it.x, it.y, it.x + it.width, it.y + h, clip)) text(canvas, it)
            }
        }
    }

    /** Clears memoized text layouts under memory pressure; layouts are rebuilt on demand. */
    fun trimMemory() {
        synchronized(textLayoutCache) { textLayoutCache.clear() }
    }

    private fun rectVisible(l: Float, t: Float, r: Float, b: Float, clip: Rect): Boolean =
        r >= clip.left && l <= clip.right && b >= clip.top && t <= clip.bottom

    /**
     * Fast bounds check on the stored samples (shapes re-derive from two corners, so their raw
     * points already bound the rendered geometry). Smoothed centrelines never leave this box by
     * more than the stroke width, which the margin covers.
     */
    private fun strokeVisible(stroke: Stroke, clip: Rect): Boolean {
        val points = stroke.points
        if (points.isEmpty()) return false
        val margin = stroke.width * 2f + 8f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        // Shapes only store their corners; iterating raw points bounds them exactly without
        // expanding an ellipse into 65 samples just to test visibility.
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return maxX + margin >= clip.left && minX - margin <= clip.right &&
            maxY + margin >= clip.top && minY - margin <= clip.bottom
    }

    /** A placed photo drawn into its box, scaled to fill while keeping the bitmap filtered. */
    fun image(canvas: Canvas, bitmap: Bitmap, box: PageImage) {
        if (box.width <= 0f || box.height <= 0f) return
        val dst = bitmapRectPool.get()!!.apply { set(box.x, box.y, box.x + box.width, box.y + box.height) }
        canvas.drawBitmap(bitmap, null, dst,
            bitmapPaintPool.get()!!)
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
        for (row in firstY..lastY) {
            val y = row * spacing
            if (page.paper == Paper.DOTS) {
                for (column in firstX..lastX) canvas.drawCircle(column * spacing, y, 1.2f, paint)
            } else canvas.drawLine(bounds.left.toFloat(), y, bounds.right.toFloat(), y, paint)
        }
        if (page.paper.isGrid) for (column in firstX..lastX) {
            val x = column * spacing
            canvas.drawLine(x, bounds.top.toFloat(), x, bounds.bottom.toFloat(), paint)
        }
        if (page.paper == Paper.GRAPH) {
            // Dedicated axis paint: never mutate the shared minor paint (races + leaks state).
            canvas.drawLine(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), graphAxisPaint)
            canvas.drawLine(bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f, graphAxisPaint)
        }
    }

    /** A finite, translated copy for previews and exports; stored coordinates stay untouched. */
    fun exportPage(page: NotePage): NotePage {
        if (!page.infinite) return page
        var left = 0f; var top = 0f; var right = page.width; var bottom = page.height
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

    /**
     * The measured layout for a text box. Drawing and hit-testing share it, so a box is tapped and
     * dragged exactly where it is drawn, and its height is never stored out of date.
     * Layouts are cached because measuring text on every frame made scrolling past text-heavy
     * pages visibly heavy; boxes are immutable so the cache key is the box itself.
     */
    fun textLayout(box: TextBox): StaticLayout {
        synchronized(textLayoutCache) { textLayoutCache[box]?.let { return it } }
        val value = box.text.ifEmpty { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = box.color
            textSize = box.size.coerceIn(TextBox.MIN_SIZE, TextBox.MAX_SIZE)
            typeface = Typeface.create(Typeface.SERIF, if (box.bold) Typeface.BOLD else Typeface.NORMAL)
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
        synchronized(textLayoutCache) { textLayoutCache[box] = layout }
        return layout
    }

    /** The box's rendered height, used for hit-testing and for the drag outline. */
    fun textHeight(box: TextBox): Float = textLayout(box).height.toFloat()

    fun text(canvas: Canvas, box: TextBox) {
        val layout = textLayout(box)
        canvas.save()
        canvas.translate(box.x, box.y)
        layout.draw(canvas)
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
        while (y < endY) { canvas.drawLine(36f, y, page.width - 36f, y, paint); y += spacing }
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
        val paint = paperMinorPaint
        val spacing = 28f
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val firstY = (floor(clip.top / spacing).toInt() * spacing).coerceAtLeast(spacing)
        val lastY = min(page.height, clip.bottom.toFloat())
        val firstX = (floor(clip.left / spacing).toInt() * spacing).coerceAtLeast(spacing)
        val lastX = min(page.width, clip.right.toFloat())
        var y = firstY
        while (y < lastY) {
            var x = firstX
            while (x < lastX) { canvas.drawCircle(x, y, 1.2f, paint); x += spacing }
            y += spacing
        }
    }

    private fun drawGrid(canvas: Canvas, page: NotePage, spacing: Float, minorAlpha: Int, majorEvery: Int) {
        val minor = if (minorAlpha == 0xFFE0E0DA.toInt()) paperGridMinorPaint
            else Paint(Paint.ANTI_ALIAS_FLAG).apply { color = minorAlpha; strokeWidth = .7f }
        val major = paperGridMajorPaint
        val clip = clipRectPool.get()!!.also { canvas.getClipBounds(it) }
        val left = max(0f, clip.left.toFloat()); val right = min(page.width, clip.right.toFloat())
        val top = max(0f, clip.top.toFloat()); val bottom = min(page.height, clip.bottom.toFloat())
        var x = (ceil(left / spacing).toInt() * spacing).coerceAtLeast(spacing)
        var idx = (x / spacing).roundToInt()
        while (x < right) {
            val p = if (majorEvery > 0 && idx % majorEvery == 0) major else minor
            canvas.drawLine(x, top, x, bottom, p); x += spacing; idx++
        }
        var y = (ceil(top / spacing).toInt() * spacing).coerceAtLeast(spacing)
        idx = (y / spacing).roundToInt()
        while (y < bottom) {
            val p = if (majorEvery > 0 && idx % majorEvery == 0) major else minor
            canvas.drawLine(left, y, right, y, p); y += spacing; idx++
        }
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
        var x = (ceil(left / spacing).toInt() * spacing).coerceAtLeast(spacing)
        var i = (x / spacing).roundToInt()
        while (x < right) {
            val p = if (i % 5 == 0) major else minor
            canvas.drawLine(x, top, x, bottom, p); x += spacing; i++
        }
        var y = (ceil(top / spacing).toInt() * spacing).coerceAtLeast(spacing)
        i = (y / spacing).roundToInt()
        while (y < bottom) {
            val p = if (i % 5 == 0) major else minor
            canvas.drawLine(left, y, right, y, p); y += spacing; i++
        }
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
        var tx = cx + spacing
        while (tx < page.width - spacing) {
            canvas.drawLine(tx, cy - 4f, tx, cy + 4f, tick)
            canvas.drawLine(cx - (tx - cx), cy - 4f, cx - (tx - cx), cy + 4f, tick)
            tx += spacing
        }
        var ty = cy + spacing
        while (ty < page.height - spacing) {
            canvas.drawLine(cx - 4f, ty, cx + 4f, ty, tick)
            canvas.drawLine(cx - 4f, cy - (ty - cy), cx + 4f, cy - (ty - cy), tick)
            ty += spacing
        }
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
        val points = InkGeometry.pathPoints(stroke)
        if (points.isEmpty()) return
        val paint = strokePaintPool.get()!!.apply {
            color = stroke.color; alpha = (Color.alpha(stroke.color) * stroke.opacity).toInt().coerceIn(0, 255); strokeWidth = stroke.width; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
            // Dashes only apply to shape tools drawn as one path; freehand ink stays solid so
            // pressure-varying segments never break into uneven fragments.
            pathEffect = if (stroke.tool in SHAPE_SET) dashEffect(stroke.style, stroke.width) else null
        }
        val centre = when {
            stroke.tool in SHAPE_SET -> points
            stroke.tool == Tool.PEN -> handwritingCentreline(points)
            else -> InkGeometry.smooth(points)
        }
        if (centre.size < 2) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            val widthScale = if (stroke.tool == Tool.PEN) penPressureScale(centre[0].pressure) else centre[0].pressure
            canvas.drawCircle(centre[0].x, centre[0].y, stroke.width * widthScale / 2, paint)
            return
        }
        // Translucent ink is drawn as one Path, so overlapping segments never darken the line.
        // Reuses a thread-local Path to avoid allocating one per stroke per frame.
        if (stroke.tool == Tool.HIGHLIGHTER || stroke.tool in SHAPE_SET) {
            val path = strokePathPool.get()!!.apply {
                reset(); moveTo(centre[0].x, centre[0].y); centre.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
            return
        }
        // Pen pressure is compressed around the selected width so quick light strokes stay readable.
        // The thread-local paint is reused, so a dashed shape must not leak its effect into the
        // next solid stroke drawn with the same Paint instance.
        paint.pathEffect = null
        val taper = InkGeometry.taperScales(centre)
        centre.zipWithNext().forEachIndexed { index, (a, b) ->
            val pressure = penPressureScale((a.pressure + b.pressure) / 2f)
            val taperScale = ((taper[index] + taper[index + 1]) / 2f).coerceAtLeast(MIN_VISIBLE_TAPER)
            paint.strokeWidth = stroke.width * pressure * taperScale
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        paint.pathEffect = null
    }

    /**
     * Dash pattern for a shape stroke, scaled by its width so thin and heavy lines read alike.
     * Dotted uses a zero-length dash with a round cap, which renders as evenly spaced dots.
     */
    internal fun dashEffect(style: StrokeStyle, width: Float): android.graphics.PathEffect? = when (style) {
        StrokeStyle.SOLID -> null
        StrokeStyle.DASHED -> android.graphics.DashPathEffect(floatArrayOf(14f.coerceAtLeast(width * 3f), 10f.coerceAtLeast(width * 2f)), 0f)
        StrokeStyle.DOTTED -> android.graphics.DashPathEffect(floatArrayOf(0.5f, (width * 3f).coerceAtLeast(8f)), 0f)
    }
}
