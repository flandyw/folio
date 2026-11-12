package com.folio.notes.music

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class MetronomeVoice(val label: String) {
    WOOD("Wood"), CLICK("Click"), SOFT("Soft");
    companion object {
        fun decode(value: String?): MetronomeVoice = entries.firstOrNull { it.name == value } ?: WOOD
    }
}

internal fun metronomeBeatStrength(index: Int, accents: List<Int>, accentFirst: Boolean): Int =
    accents.getOrNull(index) ?: if (index == 0 && accentFirst) 2 else 1

internal data class MetronomePosition(val beat: Int, val countInBars: Int = 0)

/** All tick positions come from the same sample clock; rounding never accumulates between beats. */
internal class MetronomeRhythm(
    val bpm: Int, val beats: Int, subdivision: Int, val countInBars: Int = 0,
    val accents: List<Int> = emptyList(), val accentFirst: Boolean = true,
) {
    init {
        require(bpm in 30..240 && beats in 1..12 && subdivision in 0..4 && countInBars in 0..4)
        require(accents.isEmpty() || (accents.size == beats && accents.all { it in 0..2 }))
    }
    val steps = subdivision.coerceAtLeast(1)
    private val leadBeats = countInBars * beats
    private val denominator = bpm * steps
    fun frame(tick: Long): Long = (tick * 60L * SAMPLE_RATE + denominator / 2) / denominator
    fun tickAt(frames: Long): Long {
        val estimate = frames * denominator / (60L * SAMPLE_RATE)
        return if (frame(estimate + 1) <= frames) estimate + 1 else estimate
    }
    fun position(frame: Long): MetronomePosition {
        val beat = tickAt(frame) / steps
        return if (beat < leadBeats) MetronomePosition((beat % beats).toInt() + 1, countInBars - (beat / beats).toInt())
        else MetronomePosition(((beat - leadBeats) % beats).toInt() + 1)
    }
    /** 0 = muted, 1 = normal, 2 = strong, 3 = a quieter subdivision. */
    fun strength(tick: Long): Int {
        val beat = tick / steps
        val part = (tick % steps).toInt()
        if (beat < leadBeats) return if (part > 0) 0 else if (beat % beats == 0L) 2 else 1
        val index = ((beat - leadBeats) % beats).toInt()
        val strength = metronomeBeatStrength(index, accents, accentFirst)
        return if (strength == 0 || part == 0) strength else 3
    }
    companion object { const val SAMPLE_RATE = 48_000 }
}

/** Short, tapered percussion; no bundled recordings or system notification tones. */
internal fun metronomeClick(voice: MetronomeVoice, strength: Int): ShortArray {
    val rate = MetronomeRhythm.SAMPLE_RATE
    val duration = when (voice) { MetronomeVoice.WOOD -> .025; MetronomeVoice.CLICK -> .012; MetronomeVoice.SOFT -> .04 }
    val gain = when (strength) { 0 -> 0.0; 2 -> .85; 3 -> .24; else -> .58 }
    val pitch = if (strength == 2) 1.35 else if (strength == 3) .8 else 1.0
    var noise = 17
    return ShortArray((rate * duration).toInt()) { index ->
        val t = index.toDouble() / rate
        val attack = minOf(1.0, t / .0008)
        val tail = (1.0 - t / duration).coerceAtLeast(0.0)
        noise = noise * 1664525 + 1013904223
        val random = (noise ushr 8) / 8388607.5 - 1.0
        val wave = when (voice) {
            MetronomeVoice.WOOD -> (.7 * sin(2 * PI * 1150 * pitch * t) + .3 * sin(2 * PI * 1870 * pitch * t)) * exp(-t * 180)
            MetronomeVoice.CLICK -> (.55 * random + .45 * sin(2 * PI * 2500 * pitch * t)) * exp(-t * 420)
            MetronomeVoice.SOFT -> sin(2 * PI * 760 * pitch * t) * exp(-t * 100)
        }
        (wave * attack * tail * gain * Short.MAX_VALUE).roundToInt().coerceIn(-32767, 32767).toShort()
    }
}

/** A reusable block renderer; click boundaries are independent of the audio device's write sizes. */
internal class MetronomePcm(val rhythm: MetronomeRhythm, voice: MetronomeVoice) {
    private val clicks = (0..3).map { metronomeClick(voice, it) }
    private var frame = 0L
    private var tick = 0L
    private var click = clicks[0]
    private var offset = click.size
    fun fill(buffer: ShortArray) {
        for (i in buffer.indices) {
            if (frame == rhythm.frame(tick)) {
                click = clicks[rhythm.strength(tick++)]
                offset = 0
            }
            buffer[i] = if (offset < click.size) click[offset++] else 0
            frame++
        }
    }
}

/** Average up to four intervals; a long pause starts a fresh tapping sequence. */
internal class MetronomeTaps {
    private val taps = ArrayDeque<Long>()
    fun tap(now: Long): Int? {
        if (taps.isNotEmpty() && now - taps.last() !in 250L..2000L) taps.clear()
        taps.addLast(now)
        if (taps.size > 5) taps.removeFirst()
        if (taps.size < 2) return null
        return (60_000.0 * (taps.size - 1) / (taps.last() - taps.first())).roundToInt().coerceIn(30, 240)
    }
}
