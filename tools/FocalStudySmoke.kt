package com.folio.notes

fun main() {
    val start = 1_000_000L
    val note = Notebook(id = "exam", title = "Methods")
    val preset = ExamTimerPreset("Short exam", writingSeconds = 120, readingSeconds = 60)
    var timer = ExamTimerState().start(preset, start)
    var entry = examStudyEntry(note, timer, start, null)
    check(entry.intervals == listOf(FocalStudyInterval(start, null)))

    fun record(at: Long) {
        timer = timer.tick(at)
        entry = examStudyEntry(note, timer, at, entry)
    }
    fun expect(millis: Long, at: Long) {
        check(entry.activeMillis == millis) { "Expected $millis ms, got ${entry.activeMillis}" }
        check(entry.intervals.sumOf { (it.endAt ?: at) - it.startAt } == millis)
    }

    record(start + 30_500L)
    expect(30_500L, start + 30_500L)
    check(timer.elapsedWriting(start + 30_500L) == 0)
    check(focalActiveMillisBetween(entry, start, start + 30_500L) == 30_500L)

    timer = timer.pause(start + 30_500L)
    record(start + 90_500L)
    expect(30_500L, start + 90_500L)
    timer = timer.unpause(start + 90_500L)
    record(start + 90_500L)
    check(entry.intervals.last() == FocalStudyInterval(start + 90_500L, null))
    record(start + 130_000L)
    expect(70_000L, start + 130_000L)
    check(timer.phase == ExamTimerPhase.WRITING)
    check(timer.elapsedWriting(start + 130_000L) == 10)

    timer = timer.pause(start + 140_000L)
    record(start + 140_000L)
    timer = timer.unpause(start + 200_000L)
    record(start + 200_000L)
    record(start + 220_000L)
    entry = examStudyEntry(note, timer, start + 220_000L, entry, completed = true)
    expect(100_000L, start + 220_000L)
    check(!entry.deleted && entry.completed)
    check(focalStudyMillisBetween(listOf(entry), start, start + 220_000L) == 0L)

    val reading = ExamTimerState().start(preset, start).tick(start + 20_000L)
    val readingOnly = examStudyEntry(note, reading, start + 20_000L, null, completed = true)
    check(readingOnly.activeMillis == 20_000L && !readingOnly.deleted)
    check(readingOnly.intervals == listOf(FocalStudyInterval(start, start + 20_000L)))
    val empty = examStudyEntry(note, reading, start, null, completed = true)
    check(empty.deleted && empty.activeMillis == 0L)

    val paused = requireNotNull(focalControlledEntry(readingOnly.copy(completed = false), "pause", start + 20_000L))
    val resumed = requireNotNull(focalControlledEntry(paused, "resume", start + 80_000L))
    check(resumed.intervals.last() == FocalStudyInterval(start + 80_000L, null))
    val finished = requireNotNull(focalControlledEntry(resumed, "finish", start + 90_000L))
    check(finished.activeMillis == 30_000L)

    val skipped = ExamTimerState().start(preset, start).skip(start + 20_000L)
    check(skipped.phase == ExamTimerPhase.WRITING)
    check(skipped.elapsedActiveMillis(start + 30_000L) == 30_000L)
    check(skipped.elapsedWriting(start + 30_000L) == 10)
    val done = skipped.tick(start + 500_000L)
    val late = examStudyEntry(note, done, start + 500_000L, null, completed = true)
    check(late.activeMillis == 140_000L)
    check(late.intervals.single().endAt == start + 140_000L)

    val writingOnly = ExamTimerState().start(preset.copy(readingSeconds = 0), start)
    check(examStudyEntry(note, writingOnly, start + 15_000L, null).activeMillis == 15_000L)
    val restored = requireNotNull(ExamTimerState.resume(preset, start, start + 100_000L,
        pausedAt = start + 30_000L))
    check(examStudyEntry(note, restored, start + 100_000L, null).activeMillis == 30_000L)
    val longPreset = ExamTimerPreset("Reading idle check", writingSeconds = 3_600, readingSeconds = 900)
    val sitting = ExamTimerState().start(longPreset, start)
    check(!sitting.tick(start + 899_000L).idleExpired(start, start + 899_000L, 2))
    check(!sitting.tick(start + 900_000L).idleExpired(start, start + 900_000L, 2))
    check(!sitting.tick(start + 1_019_999L).idleExpired(start, start + 1_019_999L, 2))
    check(sitting.tick(start + 1_020_000L).idleExpired(start, start + 1_020_000L, 2))
    check(!sitting.tick(start + 1_020_000L).idleExpired(start + 1_000_000L, start + 1_020_000L, 2))
    check(!sitting.tick(start + 1_020_000L).idleExpired(start, start + 1_020_000L, 0))
    check(!sitting.pause(start + 1_000_000L).idleExpired(start, start + 1_200_000L, 2))

    val pausedReading = sitting.pause(start + 500_000L).unpause(start + 600_000L)
    check(!pausedReading.tick(start + 1_000_000L).idleExpired(start, start + 1_000_000L, 2))
    check(pausedReading.tick(start + 1_120_000L).idleExpired(start, start + 1_120_000L, 2))
    val skippedReading = sitting.skip(start + 500_000L)
    check(!skippedReading.idleExpired(start, start + 500_000L, 2))
    check(skippedReading.tick(start + 620_000L).idleExpired(start, start + 620_000L, 2))
    val extendedReading = sitting.adjust(300, start + 500_000L)
    check(!extendedReading.tick(start + 1_000_000L).idleExpired(start, start + 1_000_000L, 2))
    check(!extendedReading.tick(start + 1_200_000L).idleExpired(start, start + 1_200_000L, 2))
    check(extendedReading.tick(start + 1_320_000L).idleExpired(start, start + 1_320_000L, 2))
    check(writingOnly.tick(start + 60_000L).idleExpired(start, start + 60_000L, 1))
    println("Focal exam timing: reading, writing, pauses, resume, early finish, skip, completion, restore and writing-only inactivity passed.")
}
