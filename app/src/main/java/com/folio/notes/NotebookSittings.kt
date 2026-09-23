package com.folio.notes

/** In-memory sitting results stay with their notebook until a mark consumes them. */
internal data class NotebookSitting(
    val timer: ExamTimerState,
    val seconds: Int?,
    val stopwatch: StopwatchState = StopwatchState()
)

internal class NotebookSittings {
    private val snapshots = mutableMapOf<String, NotebookSitting>()

    fun save(id: String, timer: ExamTimerState, seconds: Int?) {
        snapshots[id] = NotebookSitting(timer, seconds, snapshots[id]?.stopwatch ?: StopwatchState())
    }

    fun saveStopwatch(id: String, stopwatch: StopwatchState) {
        val current = snapshots[id]
        snapshots[id] = if (current == null) NotebookSitting(ExamTimerState(), null, stopwatch)
        else current.copy(stopwatch = stopwatch)
    }

    fun restore(id: String?, now: Long = System.currentTimeMillis()): NotebookSitting? =
        snapshots[id]?.let { it.copy(timer = it.timer.tick(now), stopwatch = it.stopwatch.tick(now)) }

    fun consumeResult(id: String) {
        snapshots[id]?.let { snapshots[id] = it.copy(seconds = null) }
    }
}
