package com.folio.notes

import kotlin.math.*

/**
 * Lasso cleanup of existing ink. Unlike a held stroke, a diagram on the page is often drawn in
 * several strokes, so this also joins boxes and polygons drawn line by line, outlines drawn in
 * pieces, and arrows whose head was drawn separately. Small marks among other small marks are
 * handwriting and stay as written.
 */
internal object ShapeCleanup {
    /** [replaced] maps an input index to what takes its place; empty means folded into an earlier shape. */
    data class Result(val strokes: List<Stroke>, val changed: Int, val replaced: Map<Int, List<Stroke>> = emptyMap()) {
        /** Tidied shapes; a shape drawn in several strokes counts once. */
        val shapes: Int get() = replaced.values.count { it.isNotEmpty() }
    }

    /** Below this size (page units, longest side) a mark beside other small marks is taken as writing. */
    const val WRITING_SIZE = 40f

    /** Replace in place: each shape takes its first source stroke's position in the stacking order. */
    fun strokes(strokes: List<Stroke>, options: ShapeAssistOptions): Result {
        val replaced = HashMap<Int, List<Stroke>>()
        val boxes = strokes.map { box(it.points) }
        val writing = writing(strokes, boxes)
        // Individually straight strokes are the parts of multi-stroke boxes and arrows, even
        // when single lines are switched off.
        val lines = HashMap<Int, Pair<InkPoint, InkPoint>>()
        val singles = HashMap<Int, Tidied?>()
        for (i in strokes.indices) {
            val s = strokes[i]
            if (s.points.any { !it.x.isFinite() || !it.y.isFinite() }) continue
            when (s.tool) {
                Tool.LINE -> if (s.points.size == 2) lines[i] = s.points[0] to s.points[1]
                Tool.PEN -> {
                    // Handwriting is never tidied, and a stroke that small is too short to be a part of a box or arrow.
                    if (writing[i]) continue
                    val fit = ShapeRecognition.recognise(s, options.copy(lines = true, closed = true, arrows = true, curves = true))
                    fit?.strokes?.singleOrNull()?.takeIf { it.tool == Tool.LINE }?.let { lines[i] = it.points[0] to it.points[1] }
                    singles[i] = fit
                }
                Tool.HIGHLIGHTER -> singles[i] = ShapeRecognition.recognise(s, options.copy(lines = true))
                else -> {}
            }
        }
        fun free(i: Int) = i !in replaced
        fun freehand(group: List<Int>) = group.any { strokes[it].tool == Tool.PEN }
        fun sameInk(a: Int, b: Int) = strokes[a].color == strokes[b].color && strokes[a].layer == strokes[b].layer
        fun take(group: List<Int>, shape: List<Stroke>) {
            val first = group.min()
            group.forEach { replaced[it] = if (it == first) shape else emptyList() }
        }

        if (options.closed) {
            // Boxes and polygons drawn line by line: corners are where neighbouring lines meet,
            // whether the pen stopped short of the corner or ran past it.
            val parts = lines.keys.sorted()
            for (cycle in cycles(parts, { a, b -> sameInk(a, b) }) { a, b -> lineJoin(lines.getValue(a), lines.getValue(b)) }) {
                if (!freehand(cycle.members) || cycle.members.any { !free(it) }) continue
                val corners = cycle.members.indices.map { k ->
                    val a = lines.getValue(cycle.members[k]); val b = lines.getValue(cycle.members[(k + 1) % cycle.members.size])
                    intersection(a.first, a.second, b.first, b.second) ?: return@map null
                }
                if (corners.any { it == null }) continue
                val style = strokes[cycle.members.first { strokes[it].tool == Tool.PEN }]
                val shape = ShapeRecognition.polygon(style.copy(tool = Tool.PEN), corners.filterNotNull(), options) ?: continue
                if (size(box(shape.flatMap { it.points })) >= WRITING_SIZE) take(cycle.members, shape)
            }
            // Outlines drawn in pieces (two halves of a circle, a box as two L shapes): the
            // pieces meet end to end and are recognised as one stroke.
            val pieces = singles.keys.filter { free(it) && !writing[it] && strokes[it].tool == Tool.PEN && singles[it]?.kind?.closed != true }.sorted()
            for (cycle in cycles(pieces, { a, b -> sameInk(a, b) }) { a, b -> endJoin(strokes[a], boxes[a], strokes[b], boxes[b]) }) {
                if (cycle.members.any { !free(it) }) continue
                val points = ArrayList<InkPoint>()
                cycle.members.forEachIndexed { k, i -> points += if (cycle.reversed[k]) strokes[i].points.asReversed() else strokes[i].points }
                val joined = strokes[cycle.members.first()].copy(points = points)
                val shape = ShapeRecognition.recognise(joined, options)?.takeIf { it.kind.closed }?.strokes ?: continue
                if (size(box(points)) >= WRITING_SIZE) take(cycle.members, shape)
            }
        }

        if (options.arrows) arrows(strokes, lines, ::free, ::sameInk, ::take)

        for ((i, fit) in singles) {
            if (!free(i) || fit == null || writing[i]) continue
            val shape = fit.takeIf { options.allows(it.kind) }?.strokes?.let(::level) ?: continue
            if (!same(strokes[i], shape)) replaced[i] = shape
        }
        if (replaced.isEmpty()) return Result(strokes, 0)
        return Result(strokes.indices.flatMap { replaced[it] ?: listOf(strokes[it]) }, replaced.size, replaced)
    }

    /** Shaft plus head: a separate V, or two short wings, whose point meets an end of the shaft. */
    private fun arrows(strokes: List<Stroke>, lines: Map<Int, Pair<InkPoint, InkPoint>>,
        free: (Int) -> Boolean, sameInk: (Int, Int) -> Boolean, take: (List<Int>, List<Stroke>) -> Unit) {
        val vees = strokes.indices.filter { free(it) && strokes[it].tool == Tool.PEN && it !in lines }
            .mapNotNull { i -> ShapeRecognition.vee(strokes[i])?.let { i to it } }.toMap()
        val wings = wings(strokes, lines)
        val shafts = lines.keys.sortedByDescending { lines.getValue(it).let { (a, b) -> distance(a, b) } }
        for (shaft in shafts) {
            if (!free(shaft)) continue
            val (a, b) = lines.getValue(shaft)
            val length = distance(a, b)
            if (length < WRITING_SIZE) continue
            val reach = max(10f, length * .15f)
            val members = mutableListOf(shaft)
            val head = ArrayList<Stroke>()
            var shaftStroke: Stroke? = null
            for ((start, tip) in listOf(a to b, b to a)) {
                val style = strokes[shaft]
                // One V-shaped stroke whose point is at the shaft's end.
                val v = vees.entries.filter { (i, v) -> free(i) && i !in members && sameInk(i, shaft) &&
                        distance(v[1], tip) < reach && distance(v[0], v[1]) < length * .6f && distance(v[2], v[1]) < length * .6f }
                    .firstNotNullOfOrNull { (i, v) -> ShapeRecognition.arrow(style, start, tip, v[0], v[2])?.let { i to it } }
                if (v != null) { members += v.first; shaftStroke = v.second[0]; head += v.second.drop(1); continue }
                // Two separate wing strokes, each starting or ending at the shaft's end.
                val near = wings.entries.filter { (i, l) -> i != shaft && i !in members && free(i) && sameInk(i, shaft) &&
                        distance(l.first, l.second) < length * .6f &&
                        min(distance(l.first, tip), distance(l.second, tip)) < reach }
                    .map { (i, l) -> i to if (distance(l.first, tip) < distance(l.second, tip)) l.second else l.first }
                val pair = near.indices.flatMap { x -> (x + 1 until near.size).map { y -> near[x] to near[y] } }
                    .firstNotNullOfOrNull { (p, q) -> ShapeRecognition.arrow(style, start, tip, p.second, q.second)?.let { Triple(p.first, q.first, it) } }
                if (pair != null) { members += pair.first; members += pair.second; shaftStroke = pair.third[0]; head += pair.third.drop(1) }
            }
            if (head.isEmpty() || !members.any { strokes[it].tool == Tool.PEN }) continue
            take(members, listOf(shaftStroke!!) + head)
        }
    }

    /** Straight strokes that can be arrow wings: fitted lines, plus short straight pen strokes. */
    private fun wings(strokes: List<Stroke>, lines: Map<Int, Pair<InkPoint, InkPoint>>): Map<Int, Pair<InkPoint, InkPoint>> {
        val result = HashMap(lines)
        for (i in strokes.indices) if (i !in result) ShapeRecognition.segment(strokes[i])?.let { result[i] = it }
        return result
    }

    private class Cycle(val members: List<Int>, val reversed: List<Boolean>)

    /**
     * Closed chains of 2–6 parts. Each end of a part joins at most one end of another part
     * (its closest), so a crowded page cannot weave unrelated strokes into one outline.
     * [join] lists the ways an end of a (0 start, 1 end) can join an end of b, with their cost.
     */
    private fun cycles(parts: List<Int>, compatible: (Int, Int) -> Boolean,
        join: (Int, Int) -> List<Triple<Int, Int, Float>>): List<Cycle> {
        if (parts.size < 2 || parts.size > 1500) return emptyList()
        val candidates = ArrayList<Pair<Pair<Int, Int>, Pair<Pair<Int, Int>, Float>>>()
        for (x in parts.indices) for (y in x + 1 until parts.size) {
            val a = parts[x]; val b = parts[y]
            if (!compatible(a, b)) continue
            for ((ea, eb, cost) in join(a, b)) candidates += (a to ea) to ((b to eb) to cost)
        }
        val partner = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        for ((p, q) in candidates.sortedBy { it.second.second }) {
            val (other, _) = q
            if (p in partner || other in partner) continue
            // A two-part outline uses both ends of each part; a part never joins itself.
            partner[p] = other; partner[other] = p
        }
        val seen = HashSet<Int>()
        val result = ArrayList<Cycle>()
        for (start in parts) {
            if (start in seen || (start to 0) !in partner || (start to 1) !in partner) continue
            val members = ArrayList<Int>(); val reversed = ArrayList<Boolean>()
            var part = start; var entry = 0
            while (members.size < 6) {
                members += part; reversed += entry == 1
                val (next, nextEntry) = partner[part to 1 - entry] ?: break
                if (next == start) {
                    if (nextEntry == 0 && members.size >= 2) { seen += members; result += Cycle(members, reversed) }
                    break
                }
                if (next in members) break
                part = next; entry = nextEntry
            }
        }
        return result
    }

    private fun lineJoin(a: Pair<InkPoint, InkPoint>, b: Pair<InkPoint, InkPoint>): List<Triple<Int, Int, Float>> {
        val la = distance(a.first, a.second); val lb = distance(b.first, b.second)
        if (la <= 0f || lb <= 0f) return emptyList()
        val cos = abs(((a.second.x - a.first.x) * (b.second.x - b.first.x) + (a.second.y - a.first.y) * (b.second.y - b.first.y)) / (la * lb))
        if (cos > cos(20 * PI / 180)) return emptyList()
        val x = intersection(a.first, a.second, b.first, b.second) ?: return emptyList()
        val ea = if (distance(x, a.first) < distance(x, a.second)) 0 else 1
        val eb = if (distance(x, b.first) < distance(x, b.second)) 0 else 1
        val da = distance(x, if (ea == 0) a.first else a.second); val db = distance(x, if (eb == 0) b.first else b.second)
        if (da > max(8f, la * .2f) || db > max(8f, lb * .2f)) return emptyList()
        return listOf(Triple(ea, eb, da + db))
    }

    private fun endJoin(a: Stroke, ba: FloatArray, b: Stroke, bb: FloatArray): List<Triple<Int, Int, Float>> {
        val reach = max(8f, min(size(ba), size(bb)) * .12f)
        if (ba[0] - reach > bb[2] || bb[0] - reach > ba[2] || ba[1] - reach > bb[3] || bb[1] - reach > ba[3]) return emptyList()
        val ends = listOf(a.points.first(), a.points.last()); val others = listOf(b.points.first(), b.points.last())
        return (0..1).flatMap { ea -> (0..1).mapNotNull { eb ->
            distance(ends[ea], others[eb]).takeIf { it <= reach }?.let { Triple(ea, eb, it) }
        } }
    }

    /**
     * Strokes that read as handwriting: small, with another small mark beside them (not inside
     * them, so a label in a small circle still lets the circle tidy).
     */
    private fun writing(strokes: List<Stroke>, boxes: List<FloatArray>): BooleanArray {
        val small = strokes.indices.filter { strokes[it].points.isNotEmpty() && size(boxes[it]) < WRITING_SIZE }
        val result = BooleanArray(strokes.size)
        val sorted = small.sortedBy { boxes[it][0] }
        for (x in sorted.indices) {
            val a = boxes[sorted[x]]
            for (y in x + 1 until sorted.size) {
                val b = boxes[sorted[y]]
                val room = max(size(a), size(b)) * .8f
                if (b[0] > a[2] + room) break
                if (b[1] > a[3] + room || a[1] > b[3] + room) continue
                if (!inside(b, a)) result[sorted[x]] = true
                if (!inside(a, b)) result[sorted[y]] = true
            }
        }
        return result
    }

    /** Whether a's centre lies inside b. */
    private fun inside(a: FloatArray, b: FloatArray): Boolean {
        val cx = (a[0] + a[2]) / 2; val cy = (a[1] + a[3]) / 2
        return cx > b[0] && cx < b[2] && cy > b[1] && cy < b[3]
    }

    /** A nearly level or upright line is made exactly so, about its middle. */
    private fun level(shape: List<Stroke>): List<Stroke> {
        val line = shape.singleOrNull()?.takeIf { it.points.size == 2 && (it.tool == Tool.LINE || it.tool == Tool.HIGHLIGHTER) } ?: return shape
        val (a, b) = line.points
        val angle = atan2(b.y - a.y, b.x - a.x)
        val aligned = (angle / (PI.toFloat() / 2)).roundToInt() * PI.toFloat() / 2
        if (angle == aligned || abs(angle - aligned) > .04f) return shape
        val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2; val half = distance(a, b) / 2
        val dx = cos(aligned) * half; val dy = sin(aligned) * half
        return listOf(line.copy(points = listOf(a.copy(x = cx - dx, y = cy - dy), b.copy(x = cx + dx, y = cy + dy))))
    }

    /** True when cleanup would only reproduce [source] (ink tidied before), so it is not counted again. */
    private fun same(source: Stroke, shape: List<Stroke>): Boolean {
        val result = shape.singleOrNull() ?: return false
        if (result == source) return true
        if (result.tool != source.tool || result.points.size != source.points.size) return false
        fun near(from: List<InkPoint>, to: List<InkPoint>) = from.all { p -> to.any { distance(it, p) < .75f } }
        return near(result.points, source.points) && near(source.points, result.points)
    }

    private fun intersection(a: InkPoint, b: InkPoint, c: InkPoint, d: InkPoint): InkPoint? {
        val r = (b.x - a.x) to (b.y - a.y); val s = (d.x - c.x) to (d.y - c.y)
        val cross = r.first * s.second - r.second * s.first
        if (abs(cross) < 1e-6f) return null
        val t = ((c.x - a.x) * s.second - (c.y - a.y) * s.first) / cross
        return InkPoint(a.x + t * r.first, a.y + t * r.second)
    }

    private fun box(points: List<InkPoint>): FloatArray {
        if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY; var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (p in points) { if (p.x < l) l = p.x; if (p.x > r) r = p.x; if (p.y < t) t = p.y; if (p.y > b) b = p.y }
        return floatArrayOf(l, t, r, b)
    }
    private fun size(box: FloatArray) = max(box[2] - box[0], box[3] - box[1])
    private fun distance(a: InkPoint, b: InkPoint) = hypot(a.x - b.x, a.y - b.y)
}
