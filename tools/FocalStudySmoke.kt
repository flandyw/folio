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
    // A disk restore always freezes at its heartbeat, even after a restart a second later.
    val immediateRestart = requireNotNull(ExamTimerState.resume(preset, start, start + 21_000L,
        lastSeen = start + 20_000L, recoverRunning = true))
    check(immediateRestart.paused && immediateRestart.autoParked)
    check(immediateRestart.elapsedActiveMillis(start + 500_000L) == 20_000L)
    check(immediateRestart.unpause(start + 500_000L).elapsedActiveMillis(start + 510_000L) == 30_000L)
    val manualRestart = requireNotNull(ExamTimerState.resume(preset, start, start + 500_000L,
        pausedAt = start + 20_000L, lastSeen = start + 25_000L, recoverRunning = true))
    check(manualRestart.paused && !manualRestart.autoParked)
    check(manualRestart.elapsedActiveMillis(start + 500_000L) == 20_000L)
    val noHeartbeat = requireNotNull(ExamTimerState.resume(preset, start, start + 500_000L,
        recoverRunning = true))
    check(noHeartbeat.paused && noHeartbeat.elapsedActiveMillis(start + 500_000L) == 0L)
    val oldResumedSave = requireNotNull(ExamTimerState.resume(preset, start, start + 500_000L,
        pausedMillis = 30_000L, recoverRunning = true))
    check(oldResumedSave.paused && oldResumedSave.elapsedActiveMillis(start + 500_000L) == 0L)
    val longClosure = requireNotNull(ExamTimerState.resume(preset, start, start + 72 * 60 * 60_000L,
        lastSeen = start + 20_000L, recoverRunning = true))
    check(longClosure.paused && longClosure.phase == ExamTimerPhase.READING)

    val focus = FocalFocus(notebookId = note.id, title = "Methods Focus", subjectId = "mm",
        startedAt = start, resumedAt = start, intervals = listOf(FocalStudyInterval(start, null)))
    val runningEntry = FocalStudyEntry(id = focus.sessionId, notebookId = note.id, title = focus.title,
        subjectId = focus.subjectId, kind = "study", startedAt = start, endedAt = start,
        activeMillis = 0L, completed = false, synced = true, intervals = focus.intervals)
    val live = FocalStudyState(focus = focus, entries = listOf(runningEntry), configured = false)
    val heartbeat = focalStudyCheckpoint(live, start + 35_000L)
    val checkpoint = requireNotNull(heartbeat.focus)
    check(checkpoint.resumedAt == null && checkpoint.parkReason == FocalFocus.PARK_CLOSED)
    check(checkpoint.elapsed(start + 500_000L) == 35_000L)
    check(heartbeat.entries.single().let {
        it.paused && !it.synced && it.activeMillis == 35_000L && it.endedAt == start + 35_000L &&
            it.intervals == checkpoint.intervals
    })
    check(live.focus == focus && live.entries.single() == runningEntry) // No runtime pause/dirty entry.
    check(focalStudyCheckpoint(heartbeat, start + 500_000L) == heartbeat) // Reopening is idempotent.
    // An unrelated receipt writes a later checkpoint, never the original running start.
    val receipt = focalStudyCheckpoint(live, start + 55_000L)
    val recovered = focalRecoveredFocus(requireNotNull(receipt.focus))
    check(recovered.accumulatedMillis == 55_000L)
    check(focalDecodeIntervals(focalEncodeIntervals(recovered.intervals)) == recovered.intervals)
    check(focalStudyMillisBetween(receipt.entries, start, start + 500_000L) == 55_000L)

    // Old running saves prove only the open interval's start. Close it in BOTH models.
    val legacy = focalRecoveredFocus(focus)
    check(legacy.intervals.single() == FocalStudyInterval(start, start))
    check(legacy.elapsed(start + 500_000L) == 0L)
    check(focalRecoverFocus(runningEntry, legacy).intervals == legacy.intervals)
    check(focalRecoveredFocus(recovered) == recovered)

    val resumedFocus = recovered.resume(start + 100_000L)
    val pausedFocus = resumedFocus.pause(start + 125_000L)
    check(pausedFocus.accumulatedMillis == 80_000L)
    check(pausedFocus.intervals == listOf(FocalStudyInterval(start, start + 55_000L),
        FocalStudyInterval(start + 100_000L, start + 125_000L)))
    check(pausedFocus.pause(start + 500_000L, FocalFocus.PARK_AWAY) == pausedFocus)
    check(resumedFocus.resume(start + 500_000L) == resumedFocus)
    var cycles = pausedFocus
    repeat(20) { index ->
        val at = start + 200_000L + index * 20_000L
        cycles = cycles.resume(at).pause(at + 5_000L)
    }
    check(cycles.accumulatedMillis == 180_000L)
    check(cycles.intervals.sumOf { requireNotNull(it.endAt) - it.startAt } == cycles.accumulatedMillis)
    val completedFocus = focalRecoverFocus(runningEntry, pausedFocus).copy(completed = true)
    check(focalStudyMillisBetween(listOf(completedFocus), start, start + 500_000L) == 80_000L)

    // Recovery must replay the exact pause/resume gaps after an offline closure.
    fun commands(row: FocalStudyEntry) = focalCommandsFor(row, "device") { _, at, previous ->
        FocalSessionCommandTiming(null, if (at != null && previous != null) at - previous else 0L)
    }
    val offline = commands(completedFocus)
    check(offline.map { it.getString("action") } == listOf("start", "pause", "resume", "complete"))
    check(offline.map { it.getLong("elapsed_since_previous_ms") } == listOf(0L, 55_000L, 45_000L, 25_000L))
    val canonical = org.json.JSONObject().put("completed", false).put("phase", "focus")
        .put("segment_started_at", java.time.Instant.ofEpochMilli(start).toString())
        .put("segments", org.json.JSONArray().put(org.json.JSONObject()))
    val pendingRecovery = receipt.entries.single().copy(remotePayload = canonical.toString())
    val onlinePause = commands(pendingRecovery)
    check(onlinePause.single().getString("action") == "pause")
    check(onlinePause.single().getLong("elapsed_since_previous_ms") == 55_000L)
    check(focalMergeSession(pendingRecovery, runningEntry).paused) // Old remote echo cannot resume recovery.

    // A server checkpoint of an OPEN interval is already counted by focus.elapsed().
    val remoteRunning = runningEntry.copy(intervals = resumedFocus.intervals, activeMillis = 90_000L,
        synced = true, endedAt = start + 135_000L)
    val reconciled = requireNotNull(focalReconcileFocus(resumedFocus, remoteRunning))
    check(reconciled.accumulatedMillis == 55_000L)
    check(reconciled.elapsed(start + 135_000L) == 90_000L)
    check(requireNotNull(focalReconcileFocus(reconciled, remoteRunning.copy(activeMillis = 95_000L)))
        .elapsed(start + 140_000L) == 95_000L)
    val remoteResume = requireNotNull(focalReconcileFocus(recovered, remoteRunning))
    check(remoteResume.parkReason == null && remoteResume.resumedAt != null)
    check(focalReconcileFocus(reconciled, completedFocus.copy(synced = true)) == null)
    val localPause = pendingRecovery.copy(synced = false)
    check(focalReconcileFocus(recovered, localPause) == recovered)
    val finishedState = live.copy(focus = null, entries = listOf(completedFocus))
    check(focalStudyCheckpoint(finishedState, start + 500_000L) == finishedState)

    // Website plans retain identity/details, but scheduled blocks never become worked time.
    val planPayload = org.json.JSONObject().put("completed", false).put("kind", "focus")
        .put("title", "Revise integration").put("subject_id", "mm").put("revision", 7)
        .put("metadata", org.json.JSONObject().put("description", "Practice substitution")
            .put("topics", org.json.JSONArray().put("Integration")))
        .put("segments", org.json.JSONArray()
            .put(org.json.JSONObject().put("started_at", java.time.Instant.ofEpochMilli(start + 600_000L))
                .put("ended_at", java.time.Instant.ofEpochMilli(start + 1_800_000L)))
            .put(org.json.JSONObject().put("started_at", java.time.Instant.ofEpochMilli(start + 2_000_000L))
                .put("ended_at", java.time.Instant.ofEpochMilli(start + 2_600_000L))))
    val plan = requireNotNull(focalEntryFromCanonical("website-plan", planPayload, "remote", "user"))
    check(plan.planned && plan.intervals.isEmpty())
    check(focalPlannedSlots(plan).sumOf { requireNotNull(it.endAt) - it.startAt } == 1_800_000L)
    check(focalStudyMillisBetween(listOf(plan), start, start + 3_000_000L) == 0L)
    val plannedFocus = requireNotNull(focalPlannedFocus(plan, note, start, attended = true))
    check(plannedFocus.sessionId == plan.id && plannedFocus.title == plan.title && plannedFocus.subjectId == "mm")
    check(plannedFocus.attended && plannedFocus.notebookId == note.id && plannedFocus.elapsed(start) == 0L)
    check(plannedFocus.intervals == listOf(FocalStudyInterval(start, null)))
    check(focalPlannedFocus(plan.copy(completed = true), note, start) == null)
    check(focalPlannedFocus(plan.copy(deleted = true), note, start) == null)
    check(focalPlannedFocus(plan.copy(kind = "exam"), note, start) == null)
    check(focalPlannedFocus(plan.copy(planned = false), note, start) == null)
    val begun = plan.copy(planned = false, synced = false, startedAt = start, endedAt = start + 1_000L,
        intervals = plannedFocus.intervals)
    val startCommand = commands(begun).single()
    check(startCommand.getString("action") == "start" && startCommand.getString("session_id") == plan.id)
    check(startCommand.getLong("expected_revision") == 7L && startCommand.getString("title") == plan.title)
    check(startCommand.getJSONObject("metadata").getString("description") == "Practice substitution")
    check(startCommand.getJSONObject("metadata").getJSONArray("topics").getString(0) == "Integration")
    val offlinePlan = begun.copy(completed = true, paused = true, endedAt = start + 125_000L,
        activeMillis = 80_000L, intervals = pausedFocus.intervals)
    val plannedReplay = commands(offlinePlan)
    check(plannedReplay.map { it.getString("action") } == listOf("start", "pause", "resume", "complete"))
    check(plannedReplay.map { it.getLong("expected_revision") } == listOf(7L, 8L, 9L, 10L))
    check(focalMergeSession(begun, plan).planned == false) // Stale planned echoes cannot stop a pending start.
    check(focalPlannedSlots(plan.copy(remotePayload = "invalid")).isEmpty())

    // Plans made/edited offline keep their desired metadata separate from the server receipt.
    val newPlan = FocalStudyEntry(id = "folio-plan", notebookId = null, title = "", subjectId = null,
        kind = "study", startedAt = start, endedAt = start, activeMillis = 0L, planned = true, completed = false)
    val slots = listOf(FocalStudyInterval(start + 600_000L, start + 1_800_000L),
        FocalStudyInterval(start + 2_000_000L, start + 2_600_000L))
    val draft = focalPlanEdit(newPlan, "  Practice integrals  ", "mm", "  Try substitution  ", listOf("Integration", "Integration"), slots)
    check(draft.title == "Practice integrals" && draft.remotePayload == null && draft.planDraft != null)
    check(focalPlanDetails(draft) == ("Try substitution" to listOf("Integration")))
    check(focalPlannedSlots(draft) == slots)
    val createPlan = commands(draft).single()
    check(createPlan.getString("action") == "create" && createPlan.getJSONArray("blocks").length() == 2)
    check(!createPlan.has("elapsed_since_previous_ms") && !createPlan.has("occurred_at"))
    check(focalStudyMillisBetween(listOf(draft), start, start + 3_000_000L) == 0L)
    val editedPlan = focalPlanEdit(plan, "Rescheduled", "phys", "New notes", listOf("Motion"), slots.map {
        FocalStudyInterval(it.startAt + 86_400_000L, requireNotNull(it.endAt) + 86_400_000L)
    })
    check(editedPlan.remotePayload == plan.remotePayload && editedPlan.planDraft != null)
    val editPlan = commands(editedPlan).single()
    check(editPlan.getString("action") == "save_progress" && editPlan.getLong("expected_revision") == plan.revision)
    check(editPlan.getJSONArray("blocks").getJSONObject(0).getString("start") == java.time.Instant.ofEpochMilli(slots[0].startAt + 86_400_000L).toString())
    check(!editPlan.has("elapsed_since_previous_ms"))
    val uncreatedStart = draft.copy(planned = false, intervals = listOf(FocalStudyInterval(start, null)))
    val startUncreated = commands(uncreatedStart).single()
    check(startUncreated.getString("action") == "start" && !startUncreated.has("blocks"))
    check(startUncreated.getJSONObject("metadata").getJSONObject("schedule").getJSONArray("blocks").length() == 2)
    check(focalPlannedSlots(uncreatedStart) == slots) // A running timer still knows its original target.
    check(runCatching { focalPlanEdit(plan, "", null, "", emptyList(), slots) }.isFailure)
    check(runCatching { focalPlanEdit(plan, "Study", null, "", emptyList(), listOf(FocalStudyInterval(start, start))) }.isFailure)
    check(runCatching { focalPlanEdit(plan, "Study", null, "", emptyList(), listOf(FocalStudyInterval(start, start + 10), FocalStudyInterval(start + 5, start + 20))) }.isFailure)
    check(runCatching { focalPlanEdit(plan.copy(planned = false), "Study", null, "", emptyList(), slots) }.isFailure)

    val capturedPlan = begun.copy(planDraft = focalPlanStartPayload(plan))
    check(focalPlannedSlots(capturedPlan) == focalPlannedSlots(plan))
    check(commands(capturedPlan).single().getJSONObject("metadata").getJSONObject("schedule").getJSONArray("blocks").length() == 2)
    check(focalPlanStartPayload(draft.copy(remotePayload = draft.planDraft)) == null)

    val conflictedPlan = editedPlan.copy(synced = true)
    check(focalPlannedFocus(conflictedPlan, note, start) == null)
    val remoteReschedule = plan.copy(revision = plan.revision + 1)
    val retainedDraft = focalMergeSession(conflictedPlan, remoteReschedule)
    check(retainedDraft.planDraft == editedPlan.planDraft && retainedDraft.synced && retainedDraft.revision == remoteReschedule.revision)
    check(focalPlanEdit(retainedDraft, retainedDraft.title, retainedDraft.subjectId,
        focalPlanDetails(retainedDraft).first, focalPlanDetails(retainedDraft).second, focalPlannedSlots(retainedDraft)).synced == false)

    // Moving/resizing a block preserves its siblings, identity and plan details through publication.
    val movedSlot = FocalStudyInterval(slots[0].startAt - 300_000L, requireNotNull(slots[0].endAt) - 300_000L)
    val movedBlocks = focalReplaceSessionBlock(plan, 0, movedSlot)
    check(movedBlocks == listOf(movedSlot, slots[1]))
    val movedPlan = requireNotNull(focalBlockEdit(plan, movedBlocks, false, start))
    check(movedPlan.id == plan.id && movedPlan.revision == plan.revision)
    check(focalPlanDetails(movedPlan) == focalPlanDetails(plan))
    check(focalSessionBlocks(movedPlan) == movedBlocks && movedPlan.activeMillis == 0L)
    val movedCommand = commands(movedPlan).single()
    check(movedCommand.getString("action") == "save_progress" && !movedCommand.getBoolean("completed"))
    check(movedCommand.getJSONArray("blocks").length() == 2)
    check(movedCommand.getJSONObject("metadata").getJSONObject("schedule").getJSONArray("blocks")
        .getJSONObject(0).getString("start") == java.time.Instant.ofEpochMilli(movedSlot.startAt).toString())
    val movedDraft = requireNotNull(focalBlockEdit(draft, focalReplaceSessionBlock(draft, 0, movedSlot), false, start))
    check(focalPlannedSlots(movedDraft) == movedBlocks)
    val movedCreate = commands(movedDraft).single()
    check(movedCreate.getString("action") == "create")
    check(movedCreate.getJSONArray("blocks").toString() == movedCreate.getJSONObject("metadata")
        .getJSONObject("schedule").getJSONArray("blocks").toString())
    val donePlan = requireNotNull(focalBlockEdit(plan.copy(synced = false, planDraft = focalPlanStartPayload(plan)), slots, true, start + 3_000_000L))
    val resizedSlot = slots[1].copy(endAt = requireNotNull(slots[1].endAt) + 100_000L)
    val resizedDone = requireNotNull(focalBlockEdit(donePlan,
        focalReplaceSessionBlock(donePlan, 1, resizedSlot), true, start + 3_000_000L))
    check(resizedDone.completed && !resizedDone.planned && resizedDone.id == plan.id)
    check(focalSessionBlocks(resizedDone) == listOf(slots[0], resizedSlot))
    check(resizedDone.activeMillis == 1_900_000L) // The gap remains a break.
    check(focalPlannedSlots(resizedDone) == slots) // Keep the original goal for completed plans.
    val resizedCommand = commands(resizedDone).single()
    check(resizedCommand.getString("action") == "save_progress" && resizedCommand.getBoolean("completed"))
    check(resizedCommand.getJSONArray("blocks").length() == 2)
    check(focalBlockEdit(plan, listOf(slots[0], slots[1].copy(startAt = slots[0].startAt)), false, start) == null)
    check(focalBlockEdit(donePlan, listOf(slots[0], resizedSlot), true, start) == null)
    check(!focalCanEditBlocks(begun) && !focalCanEditBlocks(conflictedPlan))
    check(!focalCanEditBlocks(offlinePlan.copy(remotePayload = null)))

    // Manual merge: Focal's study_session_merge request. Shared time counts once, gaps stay breaks.
    fun done(id: String, from: Long, to: Long, notes: String, topic: String) = FocalStudyEntry(id = id, notebookId = null, title = id,
        subjectId = "maths", kind = "study", startedAt = from, endedAt = to, activeMillis = to - from, notes = notes,
        synced = true, revision = 3L, intervals = listOf(FocalStudyInterval(from, to)),
        remotePayload = org.json.JSONObject().put("metadata", org.json.JSONObject().put("topics", org.json.JSONArray().put(topic))).toString())
    val a = done("a", start, start + 600_000L, "Chain rule", "Calculus")
    val b = done("b", start + 300_000L, start + 900_000L, "Chain rule", "Calculus")
    val c = done("c", start + 1_200_000L, start + 1_500_000L, "Product rule", "Algebra")
    check(focalMergedBlocks(listOf(c, a, b)) == listOf(FocalStudyInterval(start, start + 900_000L), FocalStudyInterval(start + 1_200_000L, start + 1_500_000L)))
    check(focalMergedBlocks(listOf(a, done("t", start + 600_000L, start + 700_000L, "", "x"))) == listOf(FocalStudyInterval(start, start + 700_000L)))
    check(focalMergeRefusal(listOf(a, b, c)) == null)
    check(focalMergeRefusal(listOf(a)) != null && focalMergeRefusal(listOf(a, a)) != null)
    check(focalMergeRefusal(listOf(a, c.copy(subjectId = "chem"))) != null)
    check(focalMergeRefusal(listOf(a, c.copy(completed = false, planned = true))) != null)
    val (mergeCommand, mergeSources) = focalMergeRequest(listOf(a, b, c), "device", "m1")
    check(mergeCommand.getString("action") == "save_progress" && mergeCommand.getString("session_id") == "a" &&
        mergeCommand.getLong("expected_revision") == 3L && mergeCommand.getString("mutation_id") == "m1")
    check(mergeCommand.getJSONArray("blocks").length() == 2 && !mergeCommand.has("completed"))
    check(mergeSources.length() == 3 && mergeSources.getJSONObject(0).getString("id") == "a" && mergeSources.getJSONObject(2).getLong("revision") == 3L)
    val mergedMetadata = mergeCommand.getJSONObject("metadata")
    check(mergedMetadata.getJSONObject("reflection").getString("notes") == "Chain rule\n\nProduct rule")
    check(mergedMetadata.getJSONArray("topics").length() == 2 && mergedMetadata.getJSONArray("merge_sources").length() == 3)

    println("Focal timing and recovery: exam phases, immediate/long restarts, durable focus checkpoints, repeated pauses, offline boundary replay, remote reconciliation, website planned sessions and manual merges passed.")
}
