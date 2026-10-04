package com.folio.notes

/** One thing that happened to writing follow, in the order it happened. */
sealed interface TraceEvent {
    val t: Long
    /** The page, preferences and printed guides in force from here on. */
    data class Context(override val t: Long, val page: FollowPage, val preferences: FollowPreferences,
                       val guides: List<WritingGuide>, val areas: List<AnswerArea>) : TraceEvent
    data class Down(override val t: Long) : TraceEvent
    /** A finished pen stroke, reduced to what the engine reads (its box and whether it is a straight bar). */
    data class Up(override val t: Long, val mark: InkMark, val view: InkBox?, val scale: Float) : TraceEvent
    data class Touch(override val t: Long) : TraceEvent
    data class Next(override val t: Long) : TraceEvent
    data class Back(override val t: Long) : TraceEvent
    data class Navigated(override val t: Long) : TraceEvent
    data class Hover(override val t: Long, val x: Float, val y: Float) : TraceEvent
    data class HoverEnd(override val t: Long) : TraceEvent
    /** What the view actually did, in screen pixels: an output, kept so a replay can be compared with it. */
    data class Moved(override val t: Long, val dx: Float, val dy: Float) : TraceEvent
}

/**
 * A local, opt-in record of one writing session: pen-ups reduced to boxes, taps, hovers and what the
 * view did. It holds no stroke points, only the boxes the engine reads, and it never leaves the
 * device unless the writer shares it. `tools/writing-follow-smoke.cjs <file>` replays it against the
 * current engine, so changing a threshold can be judged by how many moves it would have cancelled.
 */
class FollowTrace(private val capacity: Int = DEFAULT_CAPACITY) {
    private val events = ArrayDeque<String>()
    private var context: List<String> = emptyList()

    val size get() = events.size
    fun clear() { events.clear(); context = emptyList() }

    fun context(page: FollowPage, preferences: FollowPreferences, guides: List<WritingGuide>, areas: List<AnswerArea>, t: Long) {
        val block = buildList {
            add("C $t")
            add("W ${page.infinite} ${page.width} ${page.height}")
            add("P ${preferences.direction} ${preferences.mode} ${preferences.automaticReturn} ${preferences.feel} " +
                "${preferences.height} ${preferences.keepHeight} ${preferences.canvasLineScreens} ${preferences.adaptive}")
            guides.forEach { add("G ${it.left} ${it.right} ${it.y} ${it.block ?: -1}") }
            areas.forEach { add("A ${it.left} ${it.top} ${it.right} ${it.bottom}") }
        }
        context = block
        block.forEach(::add)
    }

    fun down(t: Long) = add("D $t")
    fun up(t: Long, seed: InkMark, view: InkBox?, scale: Float) {
        val b = seed.box
        val v = view ?: InkBox(0f, 0f, 0f, 0f)
        add("U $t ${b.left} ${b.top} ${b.right} ${b.bottom} ${seed.straight} ${v.left} ${v.top} ${v.right} ${v.bottom} $scale")
    }
    fun touch(t: Long) = add("T $t")
    fun next(t: Long) = add("N $t")
    fun back(t: Long) = add("B $t")
    fun navigated(t: Long) = add("V $t")
    fun hover(t: Long, x: Float, y: Float) {
        // A hovering pen reports every few milliseconds; the engine only needs where it lingers.
        val last = events.lastOrNull()
        if (last != null && last.startsWith("H ")) events.removeLast()
        add("H $t $x $y")
    }
    fun hoverEnd(t: Long) = add("E $t")
    fun moved(t: Long, dx: Float, dy: Float) = add("M $t $dx $dy")

    private fun add(line: String) {
        events.addLast(line)
        while (events.size > capacity) events.removeFirst()
    }

    /** The whole trace as text; the latest context leads, so a trace that dropped its start still replays. */
    fun text(): String = buildString {
        appendLine(HEADER)
        if (events.firstOrNull()?.startsWith("C ") != true) context.forEach(::appendLine)
        events.forEach(::appendLine)
    }

    companion object {
        const val HEADER = "folio-follow-trace 1"
        const val DEFAULT_CAPACITY = 20_000
        /** Every editor page records into this one while the writer has recording on. */
        val shared = FollowTrace()

        /** Reads a trace back. Lines it does not understand are skipped, so newer traces still replay. */
        fun parse(text: String): List<TraceEvent> {
            val out = ArrayList<TraceEvent>()
            var page = FollowPage()
            var prefs = FollowPreferences()
            var guides = ArrayList<WritingGuide>()
            var areas = ArrayList<AnswerArea>()
            var contextAt: Long? = null
            fun flush() {
                contextAt?.let { out += TraceEvent.Context(it, page, prefs, guides.toList(), areas.toList()) }
                contextAt = null
            }
            for (raw in text.lineSequence()) {
                val p = raw.trim().split(' ')
                if (p.isEmpty() || p[0].isEmpty() || p[0] == "folio-follow-trace") continue
                try {
                    fun f(i: Int) = p[i].toFloat()
                    fun l(i: Int) = p[i].toLong()
                    when (p[0]) {
                        "C" -> { flush(); contextAt = l(1); page = FollowPage(); prefs = FollowPreferences(); guides = ArrayList(); areas = ArrayList() }
                        "W" -> page = FollowPage(p[1].toBoolean(), f(2), f(3))
                        "P" -> prefs = FollowPreferences(WritingDirection.valueOf(p[1]), FollowMode.valueOf(p[2]), p[3].toBoolean(),
                            f(4), f(5), p[6].toBoolean(), p[7].toInt(), p.getOrNull(8)?.toBoolean() ?: true)
                        "G" -> guides += WritingGuide(f(1), f(2), f(3), p[4].toInt().takeIf { it >= 0 })
                        "A" -> areas += AnswerArea(f(1), f(2), f(3), f(4))
                        else -> {
                            flush()
                            when (p[0]) {
                                "D" -> out += TraceEvent.Down(l(1))
                                "U" -> out += TraceEvent.Up(l(1), InkMark(InkBox(f(2), f(3), f(4), f(5)), p[6].toBoolean()),
                                    InkBox(f(7), f(8), f(9), f(10)).takeIf { it.width > 0f && it.height > 0f }, f(11))
                                "T" -> out += TraceEvent.Touch(l(1))
                                "N" -> out += TraceEvent.Next(l(1))
                                "B" -> out += TraceEvent.Back(l(1))
                                "V" -> out += TraceEvent.Navigated(l(1))
                                "H" -> out += TraceEvent.Hover(l(1), f(2), f(3))
                                "E" -> out += TraceEvent.HoverEnd(l(1))
                                "M" -> out += TraceEvent.Moved(l(1), f(2), f(3))
                            }
                        }
                    }
                } catch (_: RuntimeException) {
                    // A damaged or newer line is skipped; the rest of the trace is still useful.
                }
            }
            flush()
            return out
        }
    }
}
