package com.folio.notes.exam

/**
 * Exam-condition timer presets. VCAA gives 15 minutes reading time before each exam, so the
 * countdown shows reading separately from writing time and beeps between them.
 */
data class ExamTimerPreset(val label: String, val writingSeconds: Int, val readingSeconds: Int) {
    companion object {
        val METHODS_EXAM_1 = ExamTimerPreset("Exam 1 · 60 min", 60 * 60, 15 * 60)
        val METHODS_EXAM_2 = ExamTimerPreset("Exam 2 · 120 min", 120 * 60, 15 * 60)
        val CUSTOM = ExamTimerPreset("Custom", 90 * 60, 15 * 60)
        val PRESETS = listOf(METHODS_EXAM_1, METHODS_EXAM_2, CUSTOM)
    }
}

/** Phases of a timed sitting; a timer that is not running is simply [IDLE]. */
enum class ExamTimerPhase { IDLE, READING, WRITING, DONE }

/** What touching the pen to the page should do to the exam clock by itself. */
enum class TimerAutoAction { NONE, RESUME }

/**
 * The timer's pure state machine, so every transition is unit-testable without a clock.
 * [remaining] counts down in the current phase; when it reaches zero the phase advances and the
 * leftover seconds roll into the next one, which is what a real clock does.
 */
data class ExamTimerState(
    val phase: ExamTimerPhase = ExamTimerPhase.IDLE,
    /** Seconds left in [phase]. */
    val remaining: Int = 0,
    val preset: ExamTimerPreset = ExamTimerPreset.CUSTOM,
    val startedAt: Long? = null,
    val pausedAt: Long? = null,
    val pausedMillis: Long = 0L,
    /**
     * True when the clock was parked by the app itself — pen idleness, a hidden editor, an unseen
     * gap — rather than by hand. Kept for display (Stopped vs Paused); a pen stroke resumes any
     * paused sitting while the auto-start setting is on.
     */
    val autoParked: Boolean = false
) {
    val active: Boolean get() = phase == ExamTimerPhase.READING || phase == ExamTimerPhase.WRITING
    val paused: Boolean get() = pausedAt != null
    val running: Boolean get() = active && !paused
    private fun elapsedMillis(now: Long): Long =
        ((pausedAt ?: now) - (startedAt ?: now) - pausedMillis).coerceAtLeast(0L)

    /** Reading and writing time for the Focal session, excluding pauses and time after pens down. */
    fun elapsedActiveMillis(now: Long = System.currentTimeMillis()): Long =
        elapsedMillis(now).coerceAtMost((preset.readingSeconds.toLong() + preset.writingSeconds) * 1_000L)

    /**
     * Seconds spent writing so far, recorded onto the attempt when the exam is stopped. Measured
     * from the sitting's own start, so a sitting that survives a restart is still timed correctly,
     * and it never grows past the paper's writing time no matter how late the stop comes.
     */
    fun elapsedWriting(now: Long = System.currentTimeMillis()): Int {
        if (startedAt == null) return 0
        val total = (elapsedMillis(now) / 1000).toInt()
        val plannedReading = if (preset.readingSeconds > 0 && phase != ExamTimerPhase.IDLE) preset.readingSeconds else 0
        return (total - plannedReading).coerceIn(0, preset.writingSeconds)
    }
    /** The state one second later, or this state when the timer is not running. */
    fun tick(now: Long = System.currentTimeMillis()): ExamTimerState {
        if (!active || startedAt == null) return this
        val elapsed = (elapsedMillis(now) / 1000).toInt()
        val reading = preset.readingSeconds
        val writing = preset.writingSeconds
        return when {
            reading > 0 && elapsed < reading ->
                copy(phase = ExamTimerPhase.READING, remaining = reading - elapsed)
            elapsed < reading + writing ->
                copy(phase = ExamTimerPhase.WRITING, remaining = reading + writing - elapsed)
            else -> copy(phase = ExamTimerPhase.DONE, remaining = 0, pausedAt = null, autoParked = false)
        }
    }
    fun start(preset: ExamTimerPreset, now: Long = System.currentTimeMillis()): ExamTimerState =
        copy(preset = preset, startedAt = now, pausedAt = null, pausedMillis = 0L, autoParked = false,
            phase = if (preset.readingSeconds > 0) ExamTimerPhase.READING else ExamTimerPhase.WRITING,
            remaining = if (preset.readingSeconds > 0) preset.readingSeconds else preset.writingSeconds)
    /** Adjust the active phase without changing time already spent writing. */
    fun adjust(seconds: Int, now: Long = System.currentTimeMillis()): ExamTimerState {
        val current = tick(now)
        if (!current.active || current.startedAt == null) return current
        val elapsed = (current.elapsedMillis(now) / 1000).toInt()
        val updated = if (current.phase == ExamTimerPhase.READING) {
            current.preset.copy(readingSeconds = (current.preset.readingSeconds.toLong() + seconds)
                .coerceIn(elapsed.toLong(), Int.MAX_VALUE.toLong() - current.preset.writingSeconds).toInt())
        } else {
            val spent = (elapsed - current.preset.readingSeconds).coerceAtLeast(0)
            current.preset.copy(writingSeconds = (current.preset.writingSeconds.toLong() + seconds)
                .coerceIn(spent.toLong(), Int.MAX_VALUE.toLong() - current.preset.readingSeconds).toInt())
        }
        return current.copy(preset = updated).tick(now)
    }

    fun skip(now: Long = System.currentTimeMillis()): ExamTimerState {
        val current = tick(now)
        return current.adjust(-current.remaining, now)
    }

    /** Parks the clock. [auto] marks a park the pen can undo by itself, as opposed to the Pause button. */
    fun pause(now: Long = System.currentTimeMillis(), auto: Boolean = false): ExamTimerState {
        val current = tick(now)
        return if (current.running) current.copy(pausedAt = now, autoParked = auto) else current
    }

    fun unpause(now: Long = System.currentTimeMillis()): ExamTimerState {
        val pausedSince = pausedAt ?: return this
        return copy(pausedAt = null, autoParked = false, pausedMillis = pausedMillis + (now - pausedSince).coerceAtLeast(0L)).tick(now)
    }

    fun stop(): ExamTimerState = copy(phase = ExamTimerPhase.IDLE, remaining = 0, startedAt = null, pausedAt = null, pausedMillis = 0L, autoParked = false)
    /**
     * Parks a sitting that came back running after the user stopped looking at the pages — a
     * crash or kill while it was foregrounded, so no background pause was ever recorded. Time
     * after [lastSeen] never counts: the clock is frozen exactly there and stays parked until
     * the user resumes it. Returns this state when it is not running, when [lastSeen] is
     * unknown, or when the gap is within [graceMs] of continuous foreground use (a notebook
     * switch restoring moments later, or a heartbeat that simply has not been written yet).
     */
    fun clampUnseenGap(lastSeen: Long?, now: Long, graceMs: Long = UNSEEN_GAP_GRACE_MS): ExamTimerState {
        if (!running || lastSeen == null || lastSeen <= 0L) return this
        if (now - lastSeen <= graceMs) return this
        return pause(lastSeen.coerceAtLeast(startedAt ?: lastSeen), auto = true)
    }
    /**
     * What a pen stroke on the page should do to this sitting by itself: a stroke on a paused
     * sitting starts it again where it stopped. A clock that was never started, is already
     * running, or is finished is left alone — the pen never starts a fresh sitting. Erasing and
     * other edits only count as activity.
     */
    fun autoActionOnPenDown(autoStart: Boolean, beginsStroke: Boolean): TimerAutoAction = when {
        !autoStart || !beginsStroke -> TimerAutoAction.NONE
        paused -> TimerAutoAction.RESUME
        else -> TimerAutoAction.NONE
    }
    /**
     * True once the pen has left the page idle for [idleMinutes] during writing. Reading never
     * expires for pen inactivity, and its time cannot consume the writing phase's idle allowance.
     * Zero minutes disables the idle stop, and [lastActivityAt] must name a real moment.
     */
    fun idleExpired(lastActivityAt: Long, now: Long, idleMinutes: Int): Boolean {
        if (idleMinutes <= 0 || !running || phase != ExamTimerPhase.WRITING || lastActivityAt <= 0L) return false
        val writingStartedAt = (startedAt ?: lastActivityAt) + pausedMillis + preset.readingSeconds * 1_000L
        return now - maxOf(lastActivityAt, writingStartedAt) >= idleMinutes * 60_000L
    }
    /** "1:28:03" style, used by the countdown chip and the timer panel. */
    fun clockText(): String {
        val total = remaining.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds)
    }

    companion object {
        /** A stored sitting older than this is dropped rather than resurrected. */
        private const val MAX_RESUME_AGE_MS = 48L * 60 * 60 * 1000

        /**
         * A restored sitting still running this long after its last confirmed-visible moment is
         * treated as an unseen gap and parked, not caught up. Covers crashes and kills while
         * foregrounded; clean backgroundings are already recorded as pauses before this runs.
         */
        const val UNSEEN_GAP_GRACE_MS = 30_000L

        /**
         * Rebuilds a sitting that was already running when the app last stopped, from the preset and
         * start moment that were saved with it. The clock catches up on its own because every state
         * is derived from the start moment, and a deadline that passed while the app was closed
         * comes back as Pens down rather than being silently discarded. Callers clamp unseen gaps
         * first ([clampUnseenGap]), so this catch-up only ever covers moments the user was
         * confirmed to be looking. [parkAuto] restores whether the park was the app's own, only
         * used for display (Stopped vs Paused).
         * [recoverRunning] is for disk restoration after process death: it always parks at
         * [lastSeen], including a restart within the live-process grace period.
         * Returns null when there is nothing sane to restore: no preset,
         * a missing or future start moment, or a record older than [MAX_RESUME_AGE_MS].
         */
        fun resume(preset: ExamTimerPreset?, startedAt: Long?, now: Long = System.currentTimeMillis(),
                   pausedAt: Long? = null, pausedMillis: Long = 0L, parkAuto: Boolean = false,
                   lastSeen: Long? = null, recoverRunning: Boolean = false): ExamTimerState? {
            if (preset == null || startedAt == null || startedAt <= 0 || startedAt > now) return null
            // Disk restoration means the owning process ended. Even an immediate restart
            // must stop at the heartbeat; the grace period is only for a live process.
            if (recoverRunning && pausedAt == null) {
                return resume(preset, startedAt, now,
                    pausedAt = (lastSeen ?: (startedAt + pausedMillis.coerceAtLeast(0L))).coerceIn(startedAt, now),
                    pausedMillis = pausedMillis, parkAuto = true)
            }
            // A sitting still running after a kill is frozen at its last heartbeat *before* it is
            // caught up: ticking to [now] first would finish it (or age it out) on time nobody
            // was watching, and the later clamp could no longer park a finished sitting.
            if (pausedAt == null && lastSeen != null && lastSeen >= startedAt && lastSeen < now &&
                now - lastSeen > UNSEEN_GAP_GRACE_MS) {
                return resume(preset, startedAt, now, pausedAt = lastSeen, pausedMillis = pausedMillis, parkAuto = true)
            }
            if (pausedAt != null && (pausedAt < startedAt || pausedAt > now)) return null
            val elapsed = (pausedAt ?: now) - startedAt - pausedMillis
            if (pausedMillis < 0L || elapsed < 0L || elapsed > MAX_RESUME_AGE_MS) return null
            return ExamTimerState().start(preset, now = startedAt)
                .copy(pausedAt = pausedAt, pausedMillis = pausedMillis, autoParked = parkAuto).tick(now)
        }
    }
}
