package com.folio.notes

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

import com.folio.notes.FolioRuntime.Companion.TIMER_START_KEY
import com.folio.notes.FolioRuntime.Companion.TIMER_WRITING_KEY
import com.folio.notes.FolioRuntime.Companion.TIMER_READING_KEY
import com.folio.notes.FolioRuntime.Companion.TIMER_LABEL_KEY
import com.folio.notes.FolioRuntime.Companion.TIMER_PAUSED_AT_KEY
import com.folio.notes.FolioRuntime.Companion.TIMER_PAUSED_MILLIS_KEY
import com.folio.notes.FolioRuntime.Companion.TIMER_LAST_SEEN_KEY
import com.folio.notes.FolioRuntime.Companion.TIMER_PARK_AUTO_KEY
import com.folio.notes.FolioRuntime.Companion.TIMER_VISITS_KEY
import com.folio.notes.FolioRuntime.Companion.LAST_SEEN_THROTTLE_MS

/** Application-owned ExamSessionManager commands over the single shared document runtime. */
internal class ExamSessionManager(private val runtime: FolioRuntime) {
    internal var timerNotebookId: String? = runtime._state.value.activeId
    internal val notebookSittings = NotebookSittings()
    internal var lastSeenPersistedAt: Long = 0L
    @Volatile internal var lastTimerActivityAt: Long = 0L
    val state get() = runtime._state.timerState
    internal fun migrateLegacyTimer(notes: List<Notebook>) {
        with(runtime) {
            if (!prefs.contains(TIMER_START_KEY)) return
            val visits = try {
                org.json.JSONArray(prefs.getString(TIMER_VISITS_KEY, "[]")).let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("pageId") }
                }
            } catch (_: Exception) { emptyList() }
            val pageIds = visits.toSet()
            val owner = notes.filter { note -> note.pages.any { it.id in pageIds } }.singleOrNull() ?: return
            val keys = listOf(TIMER_START_KEY, TIMER_PAUSED_AT_KEY, TIMER_PAUSED_MILLIS_KEY,
                TIMER_WRITING_KEY, TIMER_READING_KEY, TIMER_LABEL_KEY, TIMER_PARK_AUTO_KEY)
            val editor = prefs.edit()
            if (!prefs.contains("$TIMER_START_KEY.notebook.${owner.id}")) {
                keys.forEach { key ->
                    val destination = "$key.notebook.${owner.id}"
                    when (val value = prefs.all[key]) {
                        is Long -> editor.putLong(destination, value)
                        is Int -> editor.putInt(destination, value)
                        is String -> editor.putString(destination, value)
                    }
                }
            }
            keys.forEach { editor.remove(it) }
            editor.remove(TIMER_VISITS_KEY)
            editor.apply()
            if (timerNotebookId == owner.id) restoreNotebookTimer()
        }
    }

    internal fun restoreNotebookTimer(now: Long = runtime.timerNow()) {
        with(runtime) {
            val id = timerNotebookId ?: run {
                _state.update { it.copy(timer = ExamTimerState(), lastTimedSeconds = null) }
                return
            }
            val cached = notebookSittings.restore(id, now)
            val timer = cached?.timer
                ?: ExamTimerState.resume(storedSitting(), prefs.getLong(timerKey(TIMER_START_KEY), 0L), now = now,
                    pausedAt = prefs.getLong(timerKey(TIMER_PAUSED_AT_KEY), 0L).takeIf { it > 0L },
                    pausedMillis = prefs.getLong(timerKey(TIMER_PAUSED_MILLIS_KEY), 0L),
                    parkAuto = prefs.getInt(timerKey(TIMER_PARK_AUTO_KEY), 0) != 0,
                    lastSeen = prefs.getLong(timerKey(TIMER_LAST_SEEN_KEY), 0L).takeIf { it > 0L },
                    recoverRunning = true)
                ?: ExamTimerState()
            // A crash or kill while foregrounded leaves a running sitting with no recorded pause;
            // anything past the last confirmed-visible moment never counts.
            val clamped = timer.clampUnseenGap(prefs.getLong(timerKey(TIMER_LAST_SEEN_KEY), 0L).takeIf { it > 0L }, now)
            if (clamped != timer) saveSitting(clamped, clamped.pausedAt ?: now)
            // The idle stop measures pen work, not wall time, and a fresh clock is never idle yet.
            lastTimerActivityAt = now
            _state.update { it.copy(timer = clamped, lastTimedSeconds = cached?.seconds) }
            if (clamped.active) _state.value.active?.let { note ->
                (getApplication<FolioApplication>()).focalStudy.recordExamProgress(note, clamped, now, force = true)
            }
        }
    }

    internal fun selectNotebookTimer(id: String?, now: Long = runtime.timerNow()) {
        with(runtime) {
            if (id == timerNotebookId) return
            timerNotebookId?.let { previous ->
                val current = _state.value.timer
                val paused = if (current.running) current.pause(now, auto = true) else current
                if (paused != current) {
                    saveSitting(paused)
                    _state.value.notes.find { it.id == previous }?.let { note ->
                        (getApplication<FolioApplication>()).focalStudy.recordExamProgress(note, paused, now, force = true)
                    }
                }
                val state = _state.value
                notebookSittings.save(previous, paused, state.lastTimedSeconds)
            }
            // Switching between notebooks is still studying; only leaving the pages altogether parks.
            if (id == null) (getApplication<FolioApplication>()).focalStudy
                .parkFocus(FocalFocus.PARK_AWAY, attendedOnly = true, now = now)
            timerNotebookId = id
            restoreNotebookTimer()
        }
    }

    internal fun timerNow(): Long = with(runtime) { (getApplication<FolioApplication>()).focalStudy.now() }

    internal fun recordTimerInFocal(timer: ExamTimerState, now: Long, force: Boolean = false, phaseAt: Long? = null) {
        with(runtime) {
            val note = _state.value.active ?: return
            if (timer.startedAt == null) return
            val focal = (getApplication<FolioApplication>()).focalStudy
            if (timer.phase == ExamTimerPhase.DONE) focal.finishExam(note, timer, now, phaseAt)
            else if (timer.active) focal.recordExamProgress(note, timer, now, force, phaseAt)
        }
    }

    internal fun saveSitting(timer: ExamTimerState, lastSeen: Long = runtime.timerNow()) {
        with(runtime) {
            val saved = prefs.edit()
                .putLong(timerKey(TIMER_START_KEY), timer.startedAt ?: 0L)
                .putLong(timerKey(TIMER_PAUSED_AT_KEY), timer.pausedAt ?: 0L)
                .putLong(timerKey(TIMER_PAUSED_MILLIS_KEY), timer.pausedMillis)
                .putLong(timerKey(TIMER_LAST_SEEN_KEY), lastSeen)
                .putInt(timerKey(TIMER_PARK_AUTO_KEY), if (timer.autoParked) 1 else 0)
                .putInt(timerKey(TIMER_WRITING_KEY), timer.preset.writingSeconds)
                .putInt(timerKey(TIMER_READING_KEY), timer.preset.readingSeconds)
                .putString(timerKey(TIMER_LABEL_KEY), timer.preset.label)
                .commit()
            if (!saved) _state.update { it.copy(error = "Could not save the exam timer on this device. Check free storage.") }
        }
    }

    internal fun clearSitting() {
        with(runtime) {
            val saved = prefs.edit().remove(timerKey(TIMER_START_KEY)).remove(timerKey(TIMER_WRITING_KEY))
                .remove(timerKey(TIMER_PAUSED_AT_KEY)).remove(timerKey(TIMER_PAUSED_MILLIS_KEY))
                .remove(timerKey(TIMER_LAST_SEEN_KEY)).remove(timerKey(TIMER_PARK_AUTO_KEY))
                .remove(timerKey(TIMER_READING_KEY)).remove(timerKey(TIMER_LABEL_KEY))
                .remove(timerKey(TIMER_VISITS_KEY)).commit()
            if (!saved) _state.update { it.copy(error = "Could not save the stopped exam timer on this device. Check free storage.") }
        }
    }

    internal fun storedSitting(): ExamTimerPreset? {
        with(runtime) {
            if (prefs.getLong(timerKey(TIMER_START_KEY), 0L) <= 0L) return null
            return ExamTimerPreset(
                prefs.getString(timerKey(TIMER_LABEL_KEY), "Exam") ?: "Exam",
                prefs.getInt(timerKey(TIMER_WRITING_KEY), 90 * 60),
                prefs.getInt(timerKey(TIMER_READING_KEY), 15 * 60)
            )
        }
    }

    fun tickTimer(now: Long = runtime.timerNow()) {
        with(runtime) {
            val current = _state.value.timer
            val next = current.tick(now)
            if (next != current) _state.update { it.copy(timer = next) }
            if (next.running && now - lastSeenPersistedAt >= LAST_SEEN_THROTTLE_MS) {
                lastSeenPersistedAt = now
                saveSitting(next, lastSeen = now)
            }
            // The smart timer's inactivity stop: a clock left running with the pen idle for the
            // configured stretch parks itself, exactly like leaving the pages, and the next stroke
            // resumes it.
            if (next.idleExpired(lastTimerActivityAt, now, idleStopMinutes())) {
                val parked = next.pause(now, auto = true)
                saveSitting(parked, lastSeen = now)
                _state.update { it.copy(timer = parked) }
            }
            val latest = _state.value.timer
            if (latest.active || latest.phase == ExamTimerPhase.DONE) {
                // A throttled tick can notice the end minutes late. The automatic finish
                // happened when the countdown reached zero, not when this tick ran.
                val boundaryAt = if (latest.phase == ExamTimerPhase.DONE && current.active && current.startedAt != null)
                    (current.startedAt + current.pausedMillis +
                        (current.preset.readingSeconds.toLong() + current.preset.writingSeconds) * 1_000L).coerceAtMost(now)
                else now
                recordTimerInFocal(latest, boundaryAt,
                    force = latest.phase != current.phase || latest.paused != current.paused)
            }
        }
    }

    fun onPenActivity(beginsStroke: Boolean, now: Long = runtime.timerNow()) {
        with(runtime) {
            if (_state.value.active == null) return
            lastTimerActivityAt = now
            // MOVE samples only refresh idleness. Reading preferences and evaluating resume on
            // every sample adds work to the input path without changing the timer decision.
            if (!beginsStroke) return
            when (_state.value.timer.autoActionOnPenDown(
                    prefs.getBoolean(AppPrefs.TIMER_AUTO_START, AppPrefs.DEFAULT_TIMER_AUTO_START), beginsStroke)) {
                TimerAutoAction.NONE -> Unit
                TimerAutoAction.RESUME -> {
                    val resumed = _state.value.timer.unpause(now)
                    saveSitting(resumed, lastSeen = now)
                    _state.update { it.copy(timer = resumed) }
                    recordTimerInFocal(resumed, now, force = true)
                }
            }
        }
    }

    internal fun touchTimerActivity(now: Long = runtime.timerNow()) {
        with(runtime) {
            lastTimerActivityAt = now
        }
    }

    internal fun idleStopMinutes(): Int = with(runtime) { AppPrefs.timerIdleMinutes(
        prefs.getInt(AppPrefs.TIMER_IDLE_MIN, AppPrefs.DEFAULT_TIMER_IDLE_MIN).takeIf { prefs.contains(AppPrefs.TIMER_IDLE_MIN) }) }

    fun autoPauseTimer(now: Long = runtime.timerNow()) {
        with(runtime) {
            // The study session first: leaving the editor may already have cleared the active notebook.
            (getApplication<FolioApplication>()).focalStudy.parkFocus(FocalFocus.PARK_AWAY, attendedOnly = true, now = now)
            if (_state.value.active == null) return
            val current = _state.value.timer
            if (current.running) {
                val parked = current.pause(now, auto = true)
                if (parked != current) {
                    saveSitting(parked, lastSeen = now)
                    _state.update { it.copy(timer = parked) }
                    recordTimerInFocal(parked, now, force = true)
                }
            }
        }
    }

    fun pauseForUpdate(now: Long = runtime.timerNow()) {
        with(runtime) {
            (getApplication<FolioApplication>()).focalStudy.parkFocus(FocalFocus.PARK_UPDATE, now = now)
            val current = _state.value.timer
            if (_state.value.active != null && current.running) {
                val parked = current.pause(now, auto = true)
                if (parked != current) {
                    saveSitting(parked, lastSeen = now)
                    _state.update { it.copy(timer = parked) }
                    recordTimerInFocal(parked, now, force = true)
                }
            }
        }
    }

    fun startTimer(preset: ExamTimerPreset, now: Long = runtime.timerNow()) {
        with(runtime) {
            if (_state.value.active == null) return
            val focal = (getApplication<FolioApplication>()).focalStudy
            focal.parkFocus(FocalFocus.PARK_EXAM, now = now)
            val started = ExamTimerState().start(preset, now)
            lastTimerActivityAt = now
            saveSitting(started, lastSeen = now)
            _state.update { it.copy(timer = started) }
            recordTimerInFocal(started, now, force = true)
        }
    }

    fun adjustTimer(seconds: Int) {
        with(runtime) {
            if (_state.value.active == null) return
            val now = timerNow()
            val prior = _state.value.timer
            val adjusted = prior.adjust(seconds, now)
            saveSitting(adjusted)
            _state.update { it.copy(timer = adjusted) }
            recordTimerInFocal(adjusted, now, force = true,
                phaseAt = now.takeIf { prior.phase == ExamTimerPhase.READING && adjusted.phase != ExamTimerPhase.READING })
        }
    }

    fun toggleTimerPause() {
        with(runtime) {
            if (_state.value.active == null) return
            val current = _state.value.timer
            val now = timerNow()
            val updated = if (current.paused) current.unpause(now) else current.pause(now)
            if (updated == current) return
            saveSitting(updated)
            _state.update { it.copy(timer = updated) }
            recordTimerInFocal(updated, now, force = true)
        }
    }

    fun skipTimerPhase() {
        with(runtime) {
            if (_state.value.active == null) return
            val now = timerNow()
            val prior = _state.value.timer
            val skipped = prior.skip(now)
            saveSitting(skipped)
            _state.update { it.copy(timer = skipped) }
            recordTimerInFocal(skipped, now, force = true,
                phaseAt = now.takeIf { prior.phase == ExamTimerPhase.READING && skipped.phase != ExamTimerPhase.READING })
        }
    }

    fun stopTimer() {
        with(runtime) {
            if (_state.value.active == null) return
            val current = _state.value.timer
            val now = timerNow()
            val spent = current.elapsedWriting(now).takeIf { current.startedAt != null && it > 0 }
            if (current.startedAt != null) (getApplication<FolioApplication>()).focalStudy.finishExam(
                requireNotNull(_state.value.active), current, now)
            clearSitting()
            _state.update { it.copy(timer = current.stop(), lastTimedSeconds = spent ?: it.lastTimedSeconds) }
        }
    }
    internal fun start() { with(runtime) {
        restoreNotebookTimer()
        scope.launch {
            (application as FolioApplication).focalStudy.state.collect { shared ->
                val current = _state.value.timer
                val started = current.startedAt ?: return@collect
                if (!current.active) return@collect
                val entry = shared.entries.firstOrNull { it.kind == "exam" && it.startedAt == started && it.synced &&
                    (it.userId == null || it.userId == shared.userId) }
                    ?: return@collect
                val now = timerNow()
                when {
                    entry.deleted || entry.completed -> {
                        clearSitting()
                        _state.update { it.copy(timer = current.stop()) }
                    }
                    entry.paused && !current.paused -> {
                        val paused = current.pause(now)
                        saveSitting(paused, lastSeen = now)
                        _state.update { it.copy(timer = paused) }
                    }
                    !entry.paused && current.paused -> {
                        val resumed = current.unpause(now)
                        saveSitting(resumed, lastSeen = now)
                        _state.update { it.copy(timer = resumed) }
                    }
                }
            }
        }
    } }

}
