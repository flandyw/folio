package com.folio.notes.study

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** A server timestamp and the device's elapsed clock when it arrived, so server time can be estimated between replies. */
internal data class FocalServerClockAnchor(val serverMillis: Long, val elapsedMillis: Long)

internal fun focalEstimatedServerNow(anchor: FocalServerClockAnchor?, elapsedNow: Long, wallNow: Long): Long =
    anchor?.let { it.serverMillis + (elapsedNow - it.elapsedMillis).coerceAtLeast(0L) } ?: wallNow

internal fun focalObserveServerClock(
    anchor: FocalServerClockAnchor?, serverMillis: Long, elapsedNow: Long
): FocalServerClockAnchor {
    // ponytail: retain the least delayed server estimate. Backward drift correction would need clock slewing.
    val projected = focalEstimatedServerNow(anchor, elapsedNow, serverMillis)
    return FocalServerClockAnchor(maxOf(serverMillis, projected), elapsedNow)
}

internal fun focalApplyCustomSubject(
    current: MutableMap<String, FocalSubject>, rowId: String, operation: String, payload: JSONObject?
) {
    when (operation) {
        "delete" -> current.remove(rowId)
        "put" -> payload?.let { row ->
        val id = row.optString("id").ifBlank { rowId }
        val name = row.optString("name")
            if (id.isNotBlank() && name.isNotBlank()) current[id] = FocalSubject(id, name)
        }
    }
}

internal data class FocalSessionCommandTiming(val occurredAt: String?, val elapsedMs: Long)

internal fun focalElapsedSince(
    previousElapsed: Long?, previousBootCount: Int, currentElapsed: Long, currentBootCount: Int,
    fallbackElapsed: Long = 0L
): Long = if (previousElapsed != null && previousBootCount >= 0 && previousBootCount == currentBootCount)
    (currentElapsed - previousElapsed).coerceIn(0L, 604_800_000L)
else fallbackElapsed.coerceIn(0L, 604_800_000L)

internal fun focalRecoveryElapsed(activeMillis: Long, canonicalAccumulatedMillis: Long): Long =
    (activeMillis - canonicalAccumulatedMillis).coerceIn(0L, 604_800_000L)

internal fun focalElapsedFromServerBoundary(boundary: String?, serverNow: Long?): Long? {
    if (boundary == null || serverNow == null) return null
    val boundaryMillis = runCatching { Instant.parse(boundary).toEpochMilli() }.getOrNull() ?: return null
    val elapsed = serverNow - boundaryMillis
    return elapsed.takeIf { it in 0L..604_800_000L }
}

private fun focalJsonCanonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
        "${JSONObject.quote(key)}:${focalJsonCanonical(value.opt(key))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index -> focalJsonCanonical(value.opt(index)) }
    JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

private fun focalProgressChanged(
    entry: FocalStudyEntry,
    pending: JSONObject?,
    canonical: JSONObject?,
    expectedRevision: Long,
    deviceId: String
): Boolean {
    val candidate = focalStudyCommand(entry, "save_progress", expectedRevision, deviceId, entry.changeId)
    val previousMetadata = pending?.optJSONObject("metadata") ?: canonical?.optJSONObject("metadata")
        ?: canonical?.let { JSONObject().put("legacy_metadata", focalLegacyMetadata(it)) }
        ?: return true
    val candidateMetadata = candidate.optJSONObject("metadata") ?: JSONObject()
    if (focalJsonCanonical(candidateMetadata) != focalJsonCanonical(previousMetadata)) return true
    for (field in listOf("kind", "phase", "title")) {
        if (candidate.optString(field) != (pending ?: canonical)?.optString(field)) return true
    }
    val candidateSubject = candidate.opt("subject_id")
    val previousSubject = (pending ?: canonical)?.opt("subject_id")
    return candidateSubject != previousSubject
}

/**
 * The commands implied by the difference between the server's view of this session and the
 * local entry. The durable active intervals carry offline timer boundaries; while online the
 * diff is just one lifecycle command per button press. No separate command queue is needed.
 */
internal fun focalCommandsFor(
    entry: FocalStudyEntry,
    deviceId: String,
    timingForAction: (String, Long?, Long?) -> FocalSessionCommandTiming? = { _, _, _ -> null }
): List<JSONObject> {
    if (entry.synced) return emptyList()
    val desired = focalDesiredState(entry)
    val canonical = entry.remotePayload?.let { runCatching { JSONObject(it) }.getOrNull() }
    // A timeline edit replaces the server's blocks (and done flag) in one save, as Focal does.
    entry.pendingBlocks?.let { blocks ->
        if (canonical != null && !entry.deleted) return listOf(focalStudyCommand(entry, "save_progress", entry.revision, deviceId)
            .put("blocks", focalBlocksJson(blocks)).put("completed", entry.completed))
    }
    // Past study is evidence, not a synthetic start/complete timer replay.
    if (canonical == null && entry.kind == "study" && entry.completed && !entry.deleted &&
        entry.intervals.isEmpty() && entry.activeMillis > 0L) {
        val start = maxOf(entry.startedAt, entry.endedAt - entry.activeMillis)
        return listOf(focalStudyCommand(entry, "log", entry.revision, deviceId).put("blocks",
            JSONArray().put(JSONObject().put("start", Instant.ofEpochMilli(start).toString())
                .put("end", Instant.ofEpochMilli(entry.endedAt).toString()))))
    }
    var currentState = focalCanonicalState(entry.remotePayload)
    var currentPhase = canonical?.optString("phase")?.takeIf { it in setOf("focus", "reading", "writing") }
    val commands = mutableListOf<JSONObject>()
    var expected = entry.revision
    var first = true
    fun append(action: String, at: Long? = null, previousAt: Long? = null) {
        val id = if (first) entry.changeId else UUID.randomUUID().toString()
        first = false
        val command = focalStudyCommand(entry, action, expected++, deviceId, id, timingForAction(action, at, previousAt))
        commands += command
        when (action) {
            "start", "resume" -> currentState = "running"
            "pause" -> currentState = "paused"
            "complete" -> currentState = "completed"
            "cancel" -> currentState = "cancelled"
            "create" -> currentState = "planned"
            "phase_change" -> currentPhase = command.optString("phase")
        }
    }
    // Folio already persists each active interval. Reconstruct only the boundaries missing
    // from the canonical segments, even when the final state happens to equal the server's.
    // A server row without segments may be a legacy/imported row: use the normal diff there.
    val segments = canonical?.optJSONArray("segments")
    val intervals = entry.intervals
    if (entry.kind == "study" && intervals.isNotEmpty() &&
        (canonical == null || segments != null) && currentState !in setOf("completed", "cancelled")) {
        // A scheduled session's segments are its planned slot, not timer boundaries.
        val count = if (currentState == "planned") 0 else segments?.length() ?: 0
        // Another client may have added segments that Folio has never observed locally.
        // A final-state diff cannot reconcile two incompatible timelines safely.
        if (count > intervals.size || (currentState in setOf("running", "paused") && count == 0))
            error("This session has server timer boundaries Folio cannot match to its local intervals")
        if (intervals.zipWithNext().all { (a, b) ->
                a.endAt != null && a.endAt >= a.startAt && b.startAt >= a.endAt
            } && intervals.all { it.endAt == null || it.endAt >= it.startAt }) {
            var index = if (currentState == null || currentState == "planned") 0 else count - 1
            var boundary = when (currentState) {
                null, "planned" -> null
                "running" -> intervals.getOrNull(index)?.startAt
                "paused" -> intervals.getOrNull(index)?.endAt
                else -> null
            }
            if (currentState in setOf("running", "paused") && boundary == null)
                error("This session's last server boundary cannot be matched to a local interval")
            if (currentState == null || currentState == "planned" || count > 0) {
                if (currentState == null || currentState == "planned") {
                    append("start", intervals[0].startAt, null)
                    boundary = intervals[0].startAt
                }
                while (index < intervals.size) {
                    val interval = intervals[index]
                    if (currentState == "running") {
                        val end = interval.endAt ?: break
                        val terminal = index == intervals.lastIndex && desired in setOf("completed", "cancelled") &&
                            entry.endedAt == end
                        append(if (terminal) if (desired == "cancelled") "cancel" else "complete" else "pause", end, boundary)
                        boundary = end
                        if (terminal) return commands
                    }
                    if (index == intervals.lastIndex) break
                    index++
                    val start = intervals[index].startAt
                    append("resume", start, boundary)
                    boundary = start
                }
                if (desired in setOf("completed", "cancelled") && currentState !in setOf("completed", "cancelled"))
                    append(if (desired == "cancelled") "cancel" else "complete", entry.endedAt, boundary)
                if (commands.isNotEmpty()) return commands
            }
        }
    }
    if (currentState == null) {
        when (desired) {
            "planned" -> append("create")
            "running" -> append("start")
            "paused" -> { append("start"); append("pause") }
            "completed" -> {
                if (entry.activeMillis > 0L) append("start") else append("create")
                append("complete")
            }
            "cancelled" -> {
                if (entry.activeMillis > 0L) append("start") else append("create")
                append("cancel")
            }
        }
    } else if (currentState !in setOf("completed", "cancelled") || desired == currentState ||
        (currentState == "completed" && desired == "cancelled")) {
        // A completed session is no longer terminal: it can be deleted. Only a deleted one is final.
        when {
            desired == "cancelled" && currentState != "cancelled" -> append("cancel")
            desired == "completed" && currentState != "completed" -> append("complete")
            desired == "running" && currentState == "planned" -> append("start")
            desired == "running" && currentState == "paused" -> append("resume")
            desired == "paused" && currentState == "planned" -> { append("start"); append("pause") }
            desired == "paused" && currentState == "running" -> append("pause")
            currentState == desired && currentState in setOf("running", "paused") && currentPhase != focalEntryPhase(entry) -> append("phase_change")
            currentState == desired && focalProgressChanged(entry, null, canonical, expected - 1, deviceId) -> append("save_progress")
            currentState == desired -> Unit
        }
    }
    return commands
}

internal fun focalBlocksJson(blocks: List<FocalStudyInterval>): JSONArray = JSONArray().also { array ->
    blocks.forEach { array.put(JSONObject().put("start", Instant.ofEpochMilli(it.startAt).toString())
        .put("end", Instant.ofEpochMilli(requireNotNull(it.endAt)).toString())) }
}

private fun focalEntryPhase(entry: FocalStudyEntry): String =
    entry.examPhaseBeforePause?.takeIf { entry.paused && it in setOf("reading", "writing") }
        ?: entry.examPhase?.takeIf { it in setOf("reading", "writing") }
        ?: if (entry.kind == "exam") "writing" else "focus"

private fun focalLegacyMetadata(raw: JSONObject?): JSONObject {
    if (raw == null) return JSONObject()
    val result = JSONObject()
    val keys = listOf(
        "integrations", "reflection", "createdVia", "description", "topics", "examtrack", "folio",
        "provider", "examYear", "paper", "marks", "readingMinutes", "writingMinutes", "workspaceItems",
        "unit", "scheduledAt", "durationMinutes", "maxScore"
    )
    val nested = raw.optJSONObject("metadata")
    keys.forEach { key ->
        when {
            raw.has(key) -> result.put(key, raw.get(key))
            nested?.has(key) == true -> result.put(key, nested.get(key))
        }
    }
    return result
}

internal fun focalStudyCommand(
    entry: FocalStudyEntry,
    action: String,
    expectedRevision: Long,
    deviceId: String,
    mutationId: String = entry.changeId,
    timing: FocalSessionCommandTiming? = null
): JSONObject {
    val raw = (entry.planDraft ?: entry.remotePayload)?.let { runCatching { JSONObject(it) }.getOrNull() }
    val canonicalMetadata = raw?.optJSONObject("metadata")
    val metadata = if (canonicalMetadata != null) JSONObject(canonicalMetadata.toString())
        else JSONObject().put("legacy_metadata", focalLegacyMetadata(raw))
    val folio = metadata.optJSONObject("folio") ?: JSONObject().also { metadata.put("folio", it) }
    folio.put("kind", if (entry.kind == "exam") "exam" else "focus")
        .put("notebook_id", entry.notebookId ?: JSONObject.NULL)
        .put("notebook_title", entry.notebookTitle ?: JSONObject.NULL)
    val phase = entry.examPhaseBeforePause?.takeIf { it in setOf("reading", "writing") }
        ?: entry.examPhase?.takeIf { it in setOf("reading", "writing") }
        ?: if (entry.kind == "exam") "writing" else "focus"
    folio.put("phase_before_pause", if (entry.paused) phase else JSONObject.NULL)
    if (entry.completed && entry.intervals.isEmpty() && entry.activeMillis > 0L)
        folio.put("reported_active_ms", entry.activeMillis)
    val reflection = metadata.optJSONObject("reflection") ?: JSONObject().also { metadata.put("reflection", it) }
    if (entry.notes.isBlank()) reflection.remove("notes") else reflection.put("notes", entry.notes)
    if (entry.confidence == null) reflection.remove("confidence") else reflection.put("confidence", entry.confidence)
    return JSONObject().put("mutation_id", mutationId).put("session_id", entry.id)
        .put("expected_revision", expectedRevision).put("action", action).put("device_id", deviceId)
        .put("app", "folio").put("kind", if (entry.kind == "exam") "exam" else "focus")
        .put("phase", phase).put("title", entry.title)
        .put("subject_id", entry.subjectId ?: JSONObject.NULL).put("metadata", metadata)
        .also { command ->
            if (action in setOf("create", "save_progress") && entry.planned) {
                command.put("blocks", JSONArray().also { blocks -> focalPlannedSlots(entry).forEach { slot ->
                    blocks.put(JSONObject().put("start", Instant.ofEpochMilli(slot.startAt).toString())
                        .put("end", Instant.ofEpochMilli(requireNotNull(slot.endAt)).toString()))
                } })
            }
            if (action in setOf("start", "pause", "resume", "phase_change", "complete", "cancel")) {
                command.put("occurred_at", timing?.occurredAt ?: JSONObject.NULL)
                    .put("elapsed_since_previous_ms", timing?.elapsedMs ?: 0L)
            }
        }
}

private fun JSONObject.focalStamp(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

/**
 * The lifecycle word for a canonical session row. Rows no longer store a state: they carry a
 * `completed` flag, and running, paused and scheduled ("planned") are derived from the open
 * interval, the pause stamp and the absence of a start. `cancelled_at` marks a deleted session.
 * A row cached by an older build still carries its own `state`, which wins.
 */
internal fun focalStateOf(value: JSONObject): String? = value.focalStamp("state") ?: when {
    !value.has("completed") -> null
    value.focalStamp("cancelled_at") != null -> "cancelled"
    value.optBoolean("completed") -> "completed"
    value.focalStamp("segment_started_at") != null -> "running"
    value.focalStamp("paused_at") != null -> "paused"
    else -> "planned"
}

internal fun focalCanonicalState(payload: String?): String? = payload?.let { raw -> runCatching {
    val value = JSONObject(raw)
    focalStateOf(value)
        ?: when (value.optJSONObject("execution")?.optString("state") ?: value.optString("status")) {
            "in-progress", "running" -> if (value.optJSONObject("integrations")?.optJSONObject("folio")?.optString("phase") == "paused") "paused" else "running"
            "completed" -> "completed"
            "planned" -> "planned"
            else -> null
        }
}.getOrNull() }

internal fun focalDesiredState(entry: FocalStudyEntry): String = when {
    entry.deleted -> "cancelled"
    entry.completed -> "completed"
    entry.planned -> "planned"
    entry.paused -> "paused"
    else -> "running"
}

internal fun focalMutationCanRebase(action: String, state: String?): Boolean = when (action) {
    "create" -> false
    "start" -> state == "planned"
    "pause" -> state == "running"
    "resume" -> state == "paused"
    "phase_change", "save_progress" -> state in setOf("running", "paused")
    "complete" -> state in setOf("planned", "running", "paused")
    "cancel" -> state in setOf("planned", "running", "paused", "completed")
    else -> false
}

/**
 * A timed boundary cannot be moved onto a newer server timeline. A scheduled session has no timeline
 * yet (its segments are the planned slot), so starting it early, even with a stale revision, rebases.
 */
internal fun focalRebaseTimingConflict(command: JSONObject, state: String?): Boolean =
    state != "planned" && (!command.isNull("occurred_at") || command.has("elapsed_since_previous_ms"))

internal fun focalMutationSatisfied(action: String, state: String?, phase: String?, requestedPhase: String? = null): Boolean = when (action) {
    "create" -> state != null
    "start", "resume" -> state == "running"
    "pause" -> state == "paused"
    "complete", "log" -> state == "completed"
    "cancel" -> state == "cancelled"
    "phase_change" -> phase == requestedPhase
    else -> false
}

/** What one publish pass learned from the server about the session it just wrote. */
internal data class FocalPublishAck(
    val sent: Boolean,
    val canonical: JSONObject?,
    val mutationIds: List<String>
)

/**
 * The remote sessions that somebody else moved. A row is external when the feed carries a change
 * id the local entry has not seen, the server's revision moved past the local one, this device did
 * not author that change id, and the same change was not already reported. Re-reading one change
 * is not a new event, so a retried sync cannot turn one remote edit into a stream of notices.
 */
internal fun focalExternalChanges(
    local: List<FocalStudyEntry>,
    remote: List<FocalStudyEntry>,
    ownChangeIds: Set<String> = emptySet(),
    alreadyNoticed: Map<String, String> = emptyMap()
): List<FocalStudyEntry> {
    val previous = local.associateBy { it.id }
    return remote.filter { incoming ->
        val old = previous[incoming.id]
        incoming.changeId !in ownChangeIds &&
            alreadyNoticed[incoming.id] != incoming.changeId &&
            old?.changeId != incoming.changeId &&
            (old == null || incoming.revision > old.revision)
    }
}

internal fun focalEntryFromCanonical(id: String, payload: JSONObject, changeId: String, user: String): FocalStudyEntry? = runCatching {
    val state = focalStateOf(payload).orEmpty()
    val kind = payload.optString("kind")
    val metadata = payload.optJSONObject("metadata") ?: JSONObject()
    val legacy = metadata.optJSONObject("legacy_metadata")
    val folio = metadata.optJSONObject("folio") ?: legacy?.optJSONObject("integrations")?.optJSONObject("folio")
    fun epoch(key: String): Long? = payload.optString(key).takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val segments = payload.optJSONArray("segments") ?: JSONArray()
    val intervals = (0 until segments.length()).mapNotNull { index -> runCatching {
        val segment = segments.getJSONObject(index)
        val started = Instant.parse(segment.getString("started_at")).toEpochMilli()
        FocalStudyInterval(started, segment.optString("ended_at").takeIf { it.isNotBlank() && it != "null" }
            ?.let { Instant.parse(it).toEpochMilli() })
    }.getOrNull() }
        .let { parsed ->
            val runningStart = epoch("segment_started_at")
            if (state == "running" && runningStart != null && parsed.none { it.endAt == null })
                parsed + FocalStudyInterval(runningStart, null)
            // A scheduled session's segment is its planned slot, not time that was studied.
            else if (state == "planned") emptyList()
            else parsed
        }
    val reflection = metadata.optJSONObject("reflection") ?: legacy?.optJSONObject("reflection")
    val phase = payload.optString("phase").takeIf { it in setOf("reading", "writing") }
    val started = epoch("started_at") ?: epoch("created_at") ?: 0L
    val ended = epoch("completed_at") ?: epoch("cancelled_at") ?: epoch("paused_at") ?: epoch("updated_at") ?: started
    val examtrack = legacy?.optJSONObject("integrations")?.optJSONObject("examtrack")
    FocalStudyEntry(
        id = id, changeId = changeId, notebookId = folio?.optString("notebook_id")?.takeIf { it.isNotBlank() && it != "null" },
        title = payload.optString("title", "Study session"), subjectId = payload.optString("subject_id").takeIf { it.isNotBlank() && it != "null" },
        kind = if (kind in setOf("exam", "sac")) "exam" else "study", startedAt = started, endedAt = ended,
        activeMillis = payload.optLong("accumulated_active_ms",
            folio?.optLong("reported_active_ms", 0L) ?: 0L).coerceAtLeast(0L),
        notes = reflection?.optString("notes").orEmpty(), confidence = reflection?.optInt("confidence")?.takeIf { it in 1..5 },
        userId = user, synced = true, revision = payload.optLong("revision"), completed = state == "completed",
        planned = state == "planned", deleted = state == "cancelled", paused = state == "paused",
        examPhase = phase, examPhaseBeforePause = metadata.optJSONObject("folio")?.optString("phase_before_pause")
            ?.takeIf { it in setOf("reading", "writing") }
            ?: metadata.optJSONObject("examtrack")?.optString("phaseBeforePause")?.takeIf { it in setOf("reading", "writing") }
            ?: examtrack?.optString("phaseBeforePause")?.takeIf { it in setOf("reading", "writing") },
        intervals = intervals, remotePayload = payload.toString(),
        notebookTitle = folio?.optString("notebook_title")?.takeIf { it.isNotBlank() && it != "null" }
    )
}.getOrNull()
