package com.folio.notes

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.*

object InkRenderer {
    /** Tools whose geometry traces the drag rather than freehand samples, so it is never smoothed. */
    private val SHAPES = listOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE)

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
        if (background != null) canvas.drawBitmap(background, null, RectF(0f, 0f, page.width, page.height), Paint(Paint.FILTER_BITMAP_FLAG))
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
            // Photos sit under the ink so handwriting annotates the picture, like GoodNotes.
            page.images.forEach { box -> images?.get(box.id)?.let { image(canvas, it, box) } }
            page.strokes.forEach { stroke(canvas, it) }
            page.texts.forEach { text(canvas, it) }
        }
    }

    /** A placed photo drawn into its box, scaled to fill while keeping the bitmap filtered. */
    fun image(canvas: Canvas, bitmap: Bitmap, box: PageImage) {
        if (box.width <= 0f || box.height <= 0f) return
        canvas.drawBitmap(bitmap, null, RectF(box.x, box.y, box.x + box.width, box.y + box.height),
            Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** Only the visible lattice is drawn, even when the camera is far from the origin. */
    private fun infinitePaper(canvas: Canvas, page: NotePage) {
        if (page.paper == Paper.PLAIN) return
        if (page.paper.isHanzi) {
            infiniteHanzi(canvas, page.paper == Paper.MI_GRID)
            return
        }
        val bounds = canvas.clipBounds
        val baseSpacing = if (page.paper.isGrid) page.paper.gridSpacing else 28f
        // Thin the pattern at extreme export scales instead of iterating over an enormous world.
        val stride = ceil(max(bounds.width(), bounds.height()) / (baseSpacing * 180f)).coerceAtLeast(1f)
        val spacing = baseSpacing * stride
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0DA.toInt(); strokeWidth = .8f }
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
            paint.color = 0xFF919A98.toInt(); paint.strokeWidth = 1.5f
            canvas.drawLine(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), paint)
            canvas.drawLine(bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f, paint)
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
     */
    fun textLayout(box: TextBox): StaticLayout {
        val value = box.text.ifEmpty { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = box.color
            textSize = box.size.coerceIn(TextBox.MIN_SIZE, TextBox.MAX_SIZE)
            typeface = Typeface.create(Typeface.SERIF, if (box.bold) Typeface.BOLD else Typeface.NORMAL)
            isFakeBoldText = box.bold
            textSkewX = if (box.italic) -0.25f else 0f
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, box.width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1.1f).build()
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 224, 218); strokeWidth = .8f }
        val spacing = 28f
        var y = 70f
        while (y < page.height) { canvas.drawLine(36f, y, page.width - 36f, y, paint); y += spacing }
    }

    /**
     * An Exam 2 Section A answer sheet: numbered rows with A–E bubbles to shade in with the pen.
     * Drawing the bubbles as paper rather than ink keeps them out of undo, exports, and the
     * clear-page action, so the sheet always reads like a fresh answer booklet.
     */
    private fun drawMultipleChoice(canvas: Canvas, page: NotePage) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200); strokeWidth = .9f; style = Paint.Style.STROKE }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 120, 115); textSize = 11f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val number = Paint(label).apply { color = Color.rgb(150, 150, 145); textAlign = Paint.Align.RIGHT; textSize = 13f }
        canvas.drawText("Section A — shade one bubble per question", 36f, 40f, label)
        var y = 78f
        var index = 1
        while (y < page.height - 30f) {
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 224, 218); strokeWidth = .8f }
        val spacing = 28f
        var y = spacing
        while (y < page.height) {
            var x = spacing
            while (x < page.width) { canvas.drawCircle(x, y, 1.2f, paint); x += spacing }
            y += spacing
        }
    }

    private fun drawGrid(canvas: Canvas, page: NotePage, spacing: Float, minorAlpha: Int, majorEvery: Int) {
        val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = minorAlpha; strokeWidth = .7f }
        val major = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200); strokeWidth = .9f }
        var x = spacing
        var idx = 1
        while (x < page.width) {
            val p = if (majorEvery > 0 && idx % majorEvery == 0) major else minor
            canvas.drawLine(x, 0f, x, page.height, p); x += spacing; idx++
        }
        var y = spacing
        idx = 1
        while (y < page.height) {
            val p = if (majorEvery > 0 && idx % majorEvery == 0) major else minor
            canvas.drawLine(0f, y, page.width, y, p); y += spacing; idx++
        }
    }

    /**
     * Math practice grid: minor 20 px light grid with a bolder line every 5 squares (100 px).
     * The tighter spacing keeps fractions and small diagrams proportional, while the bold
     * lines give a quick 5-unit reference without overpowering handwriting.
     */
    private fun drawMathGrid(canvas: Canvas, page: NotePage) {
        val spacing = 20f
        val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 232, 228); strokeWidth = .65f }
        val major = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 214, 210); strokeWidth = .9f }
        // Minor grid
        var x = spacing
        var i = 1
        while (x < page.width) {
            val p = if (i % 5 == 0) major else minor
            canvas.drawLine(x, 0f, x, page.height, p); x += spacing; i++
        }
        var y = spacing
        i = 1
        while (y < page.height) {
            val p = if (i % 5 == 0) major else minor
            canvas.drawLine(0f, y, page.width, y, p); y += spacing; i++
        }
    }

    /**
     * Graph paper: same math grid plus bold centered axes with arrowheads.
     * Axes sit at the center of the page so a new page is immediately usable for
     * coordinate geometry; tick marks every grid unit fall on the axes themselves.
     */
    private fun drawGraph(canvas: Canvas, page: NotePage) {
        drawMathGrid(canvas, page)
        val cx = page.width / 2f
        val cy = page.height / 2f
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140, 145, 150); strokeWidth = 1.4f }
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(175, 180, 185); strokeWidth = 0.85f }
        // Axes
        canvas.drawLine(0f, cy, page.width, cy, axis)
        canvas.drawLine(cx, 0f, cx, page.height, axis)
        // Arrowheads — small V at each end so direction reads instantly
        val ah = 9f
        val p = Path()
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
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200); strokeWidth = .9f }
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(221, 170, 170); strokeWidth = .7f
            pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
        }
        for (i in 0..cols) {
            val x = left + i * cell
            canvas.drawLine(x, top, x, bottom, outer)
        }
        for (j in 0..rows) {
            val y = top + j * cell
            canvas.drawLine(left, y, right, y, outer)
        }
        for (row in 0 until rows) {
            for (col in 0 until cols) {
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
        val bounds = canvas.clipBounds
        val base = Paper.HANZI_CELL
        val stride = ceil(max(bounds.width(), bounds.height()) / (base * 180f)).coerceAtLeast(1f)
        val cell = base * stride
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 200); strokeWidth = .9f }
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(221, 170, 170); strokeWidth = .7f
            pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
        }
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color; alpha = (Color.alpha(stroke.color) * stroke.opacity).toInt().coerceIn(0, 255); strokeWidth = stroke.width; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
        }
        val centre = when {
            stroke.tool in SHAPES -> points
            stroke.tool == Tool.PEN -> handwritingCentreline(points)
            else -> InkGeometry.smooth(points)
        }
        if (centre.size < 2) {
            paint.style = Paint.Style.FILL
            val widthScale = if (stroke.tool == Tool.PEN) penPressureScale(centre[0].pressure) else centre[0].pressure
            canvas.drawCircle(centre[0].x, centre[0].y, stroke.width * widthScale / 2, paint)
            return
        }
        // Translucent ink is drawn as one Path, so overlapping segments never darken the line.
        if (stroke.tool == Tool.HIGHLIGHTER || stroke.tool in SHAPES) {
            val path = Path().apply { moveTo(centre[0].x, centre[0].y); centre.drop(1).forEach { lineTo(it.x, it.y) } }
            canvas.drawPath(path, paint)
            return
        }
        // Pen pressure is compressed around the selected width so quick light strokes stay readable.
        val taper = InkGeometry.taperScales(centre)
        centre.zipWithNext().forEachIndexed { index, (a, b) ->
            val pressure = penPressureScale((a.pressure + b.pressure) / 2f)
            val taperScale = ((taper[index] + taper[index + 1]) / 2f).coerceAtLeast(MIN_VISIBLE_TAPER)
            paint.strokeWidth = stroke.width * pressure * taperScale
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }
}
