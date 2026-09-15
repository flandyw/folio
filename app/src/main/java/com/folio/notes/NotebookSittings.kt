package com.folio.notes

/** In-memory sitting results stay with their notebook until a mark consumes them. */
internal data class NotebookSitting(
    val timer: ExamTimerState,
    val seconds: Int?,
    val telemetry: ExamTelemetry?
)

internal class NotebookSittings {
    private val snapshots = mutableMapOf<String, NotebookSitting>()

    fun save(id: String, timer: ExamTimerState, seconds: Int?, telemetry: ExamTelemetry?) {
        snapshots[id] = NotebookSitting(timer, seconds, telemetry)
    }

    fun restore(id: String?, now: Long = System.currentTimeMillis()): NotebookSitting? =
        snapshots[id]?.let { it.copy(timer = it.timer.tick(now)) }

    fun consumeResult(id: String) {
        snapshots[id]?.let { snapshots[id] = it.copy(seconds = null, telemetry = null) }
    }
}
