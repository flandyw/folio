package com.folio.notes

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

object InkRenderer {
    /** Tools whose geometry traces the drag rather than freehand samples, so it is never smoothed. */
    private val SHAPES = listOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE)

    fun page(canvas: Canvas, page: NotePage, background: Bitmap?, ink: Boolean = true) {
        canvas.drawColor(Color.WHITE)
        if (background != null) canvas.drawBitmap(background, null, RectF(0f, 0f, page.width, page.height), Paint(Paint.FILTER_BITMAP_FLAG))
        else if (page.pdfIndex == null) {
            when (page.paper) {
                Paper.RULED -> drawRuled(canvas, page)
                Paper.GRID -> drawGrid(canvas, page, spacing = 28f, minorAlpha = 0xFFE0E0DA.toInt(), majorEvery = -1)
                Paper.MATH_GRID -> drawMathGrid(canvas, page)
                Paper.GRAPH -> drawGraph(canvas, page)
                Paper.DOTS -> drawDots(canvas, page)
                Paper.MC_SHEET -> drawMultipleChoice(canvas, page)
                Paper.PLAIN -> Unit
            }
        }
        if (ink) { page.strokes.forEach { stroke(canvas, it) }; page.texts.forEach { text(canvas, it) } }
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

    /** The box's rendered height in page units, used for hit-testing and for the drag outline. */
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

    fun stroke(canvas: Canvas, stroke: Stroke) {
        val points = InkGeometry.pathPoints(stroke)
        if (points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color; alpha = (Color.alpha(stroke.color) * stroke.opacity).toInt().coerceIn(0, 255); strokeWidth = stroke.width; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
        }
        // Freehand ink is resampled through a spline, so the line reads as a curve, not a polyline.
        val centre = if (stroke.tool in SHAPES) points else InkGeometry.smooth(points)
        if (centre.size < 2) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(centre[0].x, centre[0].y, stroke.width * centre[0].pressure / 2, paint)
            return
        }
        // Translucent ink is drawn as one Path, so overlapping segments never darken the line.
        if (stroke.tool == Tool.HIGHLIGHTER || stroke.tool in SHAPES) {
            val path = Path().apply { moveTo(centre[0].x, centre[0].y); centre.drop(1).forEach { lineTo(it.x, it.y) } }
            canvas.drawPath(path, paint)
            return
        }
        // A pen follows the pressure along the stroke and eases in and out of the page at both ends.
        val taper = InkGeometry.taperScales(centre)
        centre.zipWithNext().forEachIndexed { index, (a, b) ->
            paint.strokeWidth = stroke.width * ((a.pressure + b.pressure) / 2f).coerceIn(.25f, 1.8f) * (taper[index] + taper[index + 1]) / 2f
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }
}
