package com.folio.notes.study

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Planned slots are display context, never evidence of time worked. */
internal fun focalPlannedSlots(entry: FocalStudyEntry): List<FocalStudyInterval> = runCatching {
    if (entry.planned) entry.pendingBlocks?.let { return it }
    val payload = JSONObject(entry.planDraft ?: entry.remotePayload ?: return emptyList())
    val segments = payload.optJSONArray("segments") ?: JSONArray()
    val metadata = payload.optJSONObject("metadata")
    val blocks = metadata?.optJSONObject("schedule")?.optJSONArray("blocks")
        ?: metadata?.optJSONObject("legacy_metadata")?.optJSONObject("schedule")?.optJSONArray("blocks")
    val rows = if (entry.planned && segments.length() > 0) segments else blocks ?: JSONArray()
    (0 until rows.length()).mapNotNull { index -> runCatching {
        val row = rows.getJSONObject(index)
        val start = Instant.parse(row.optString("started_at", row.optString("start"))).toEpochMilli()
        val end = Instant.parse(row.optString("ended_at", row.optString("end"))).toEpochMilli()
        FocalStudyInterval(start, end).takeIf { end > start && end - start <= MAX_REPORTED_SESSION_MILLIS }
    }.getOrNull() }.sortedBy { it.startAt }
}.getOrDefault(emptyList())

/** The time a stopped session occupies: its plan, or the study it recorded. */
internal fun focalSessionBlocks(entry: FocalStudyEntry): List<FocalStudyInterval> = when {
    entry.planned -> focalPlannedSlots(entry)
    else -> entry.pendingBlocks ?: entry.intervals.filter { it.endAt != null }.ifEmpty {
        listOf(FocalStudyInterval(entry.endedAt - entry.activeMillis, entry.endedAt))
    }
}

/** Unsent timer boundaries must sync before they can be replaced by timeline edits. */
internal fun focalCanEditBlocks(entry: FocalStudyEntry): Boolean = entry.kind == "study" &&
    !entry.deleted && !entry.active && (entry.remotePayload != null || entry.intervals.isEmpty()) &&
    !(entry.planned && entry.planDraft != null && entry.synced)

/** Replaces one displayed block, keeping the other blocks and their break gaps. */
internal fun focalReplaceSessionBlock(entry: FocalStudyEntry, index: Int, block: FocalStudyInterval): List<FocalStudyInterval> =
    focalSessionBlocks(entry).mapIndexed { i, original -> if (i == index) block else original }

internal fun focalBlockEdit(current: FocalStudyEntry, blocks: List<FocalStudyInterval>,
                            completed: Boolean, now: Long): FocalStudyEntry? {
    val sorted = blocks.sortedBy { it.startAt }
    if (!focalCanEditBlocks(current) || sorted.isEmpty() || sorted.size > 100 ||
        sorted.any { val end = it.endAt; end == null || end <= it.startAt || end - it.startAt > MAX_REPORTED_SESSION_MILLIS } ||
        sorted.zipWithNext().any { (a, b) -> requireNotNull(a.endAt) > b.startAt } ||
        (completed && requireNotNull(sorted.last().endAt) > now)) return null
    val local = current.remotePayload == null
    if (local && completed && sorted.size > 1) return null
    val end = requireNotNull(sorted.last().endAt)
    // Keep a plan's metadata in step with its blocks, including plans not yet created remotely.
    val edited = if (current.planned && !completed) {
        val (description, topics) = focalPlanDetails(current)
        runCatching { focalPlanEdit(current, current.title, current.subjectId, description, topics, sorted) }.getOrNull()
            ?: return null
    } else current
    return edited.copy(changeId = UUID.randomUUID().toString(), synced = false,
        completed = completed, planned = !completed, startedAt = sorted.first().startAt, endedAt = end,
        activeMillis = if (completed) sorted.sumOf { requireNotNull(it.endAt) - it.startAt } else 0L,
        intervals = if (completed && !local) sorted else emptyList(),
        pendingBlocks = if (local && completed) null else sorted)
}

/** Every block of [sessions] in order, with overlapping or touching blocks joined so shared time counts once. */
internal fun focalMergedBlocks(sessions: List<FocalStudyEntry>): List<FocalStudyInterval> {
    val merged = mutableListOf<FocalStudyInterval>()
    sessions.flatMap(::focalSessionBlocks).filter { it.endAt != null && it.endAt > it.startAt }.sortedBy { it.startAt }.forEach { block ->
        val last = merged.lastOrNull()
        if (last != null && block.startAt <= requireNotNull(last.endAt))
            merged[merged.lastIndex] = last.copy(endAt = maxOf(requireNotNull(last.endAt), requireNotNull(block.endAt)))
        else merged += block
    }
    return merged
}

/** Why [sessions] cannot merge, or null. Focal's `study_session_merge` checks the same rules again. */
internal fun focalMergeRefusal(sessions: List<FocalStudyEntry>): String? {
    val first = sessions.firstOrNull() ?: return "Select at least two sessions to merge."
    return when {
        sessions.size !in 2..100 || sessions.distinctBy { it.id }.size != sessions.size -> "Select 2–100 different sessions to merge."
        sessions.any { it.kind != "study" || it.active || it.deleted || it.paused } -> "Only stopped study sessions can be merged."
        sessions.any { it.subjectId != first.subjectId || it.completed != first.completed } ->
            "Select sessions with the same subject and done/planned status."
        else -> null
    }
}

/**
 * The `study_session_merge` request, as Focal builds it: the first session keeps its title and
 * takes every block plus the others' descriptions, topics and notes; the others are listed with
 * the revisions they were selected at, so a session that changed meanwhile refuses the merge.
 */
internal fun focalMergeRequest(sessions: List<FocalStudyEntry>, deviceId: String, mutationId: String): Pair<JSONObject, JSONArray> {
    val first = sessions.first()
    val blocks = focalMergedBlocks(sessions)
    val details = sessions.map { entry ->
        val metadata = (entry.planDraft ?: entry.remotePayload)?.let { runCatching { JSONObject(it).optJSONObject("metadata") }.getOrNull() } ?: JSONObject()
        val legacy = metadata.optJSONObject("legacy_metadata") ?: JSONObject()
        JSONObject(legacy.toString()).also { joined -> metadata.keys().forEach { joined.put(it, metadata.get(it)) } }
    }
    fun texts(pick: (JSONObject) -> String?) = details.mapNotNull { pick(it)?.trim()?.takeIf { it.isNotBlank() && it != "null" } }.distinct()
    val command = focalStudyCommand(first.copy(notes = (listOf(first.notes) + sessions.drop(1).map { it.notes })
        .map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n\n")), "save_progress", first.revision, deviceId, mutationId)
        .put("blocks", focalBlocksJson(blocks))
    val metadata = command.getJSONObject("metadata")
    metadata.put("description", texts { it.optString("description") }.joinToString("\n\n"))
        .put("topics", JSONArray(details.flatMap { d -> d.optJSONArray("topics")?.let { t -> (0 until t.length()).mapNotNull { t.optString(it).takeIf(String::isNotBlank) } }.orEmpty() }.distinct()))
        .put("merge_sources", JSONArray().also { array -> sessions.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("title", entry.title)
                .put("metadata", entry.remotePayload?.let { runCatching { JSONObject(it).optJSONObject("metadata") }.getOrNull() } ?: JSONObject()))
        } })
    if (first.planned) metadata.put("schedule", JSONObject().put("blocks", focalBlocksJson(blocks)))
    val sources = JSONArray().also { array -> sessions.forEach { array.put(JSONObject().put("id", it.id).put("revision", it.revision)) } }
    return command to sources
}

internal fun focalPlanDetails(entry: FocalStudyEntry): Pair<String, List<String>> = runCatching {
    val metadata = JSONObject(entry.planDraft ?: entry.remotePayload ?: return "" to emptyList()).optJSONObject("metadata")
    val legacy = metadata?.optJSONObject("legacy_metadata")
    val description = (metadata?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }
        ?: legacy?.optString("description")).orEmpty().takeUnless { it == "null" }.orEmpty()
    val topics = metadata?.optJSONArray("topics") ?: legacy?.optJSONArray("topics") ?: JSONArray()
    description to (0 until topics.length()).mapNotNull { topics.optString(it).trim().takeIf { it.isNotBlank() && it != "null" } }.distinct()
}.getOrDefault("" to emptyList())

/** Older website plans may have scheduled segments without a metadata schedule. */
internal fun focalPlanStartPayload(entry: FocalStudyEntry): String? = runCatching {
    val raw = JSONObject(entry.remotePayload ?: "{}")
    val metadata = raw.optJSONObject("metadata") ?: JSONObject().also { raw.put("metadata", it) }
    if (metadata.optJSONObject("schedule")?.optJSONArray("blocks") != null ||
        metadata.optJSONObject("legacy_metadata")?.optJSONObject("schedule")?.optJSONArray("blocks") != null) return null
    val slots = focalPlannedSlots(entry)
    if (slots.isEmpty()) return null
    metadata.put("schedule", JSONObject().put("blocks", JSONArray().also { blocks -> slots.forEach { slot ->
        blocks.put(JSONObject().put("start", Instant.ofEpochMilli(slot.startAt).toString())
            .put("end", Instant.ofEpochMilli(requireNotNull(slot.endAt)).toString()))
    } }))
    raw.toString()
}.getOrNull()

internal fun focalPlanEdit(original: FocalStudyEntry, title: String, subjectId: String?,
                           description: String, topics: List<String>, slots: List<FocalStudyInterval>): FocalStudyEntry {
    require(original.planned && !original.completed && !original.deleted && original.kind == "study") { "Only unstarted study plans can be edited." }
    require(title.trim().length in 1..512) { "Enter a title of 1–512 characters." }
    require(slots.size in 1..100) { "Enter 1–100 time blocks." }
    val sorted = slots.sortedBy { it.startAt }
    require(sorted.all { it.endAt != null && it.endAt > it.startAt && it.endAt - it.startAt <= MAX_REPORTED_SESSION_MILLIS } &&
        sorted.zipWithNext().all { (a, b) -> requireNotNull(a.endAt) <= b.startAt }) { "Time blocks must end after they start, last up to 24 hours and never overlap." }
    val raw = JSONObject(original.planDraft ?: original.remotePayload ?: "{}")
    val metadata = raw.optJSONObject("metadata") ?: JSONObject().also { raw.put("metadata", it) }
    val blocks = JSONArray().also { array -> sorted.forEach { slot -> array.put(JSONObject()
        .put("start", Instant.ofEpochMilli(slot.startAt).toString()).put("end", Instant.ofEpochMilli(requireNotNull(slot.endAt)).toString())) } }
    metadata.put("description", description.trim()).put("topics", JSONArray(topics.map(String::trim).filter(String::isNotBlank).distinct()))
        .put("subjectIds", JSONArray(listOfNotNull(subjectId))).put("schedule", JSONObject().put("blocks", blocks))
    // Keep legacy consumers on the same plan; canonical metadata remains the authoritative copy.
    metadata.optJSONObject("legacy_metadata")?.let { legacy ->
        listOf("description", "topics", "subjectIds", "schedule").forEach { legacy.put(it, metadata.get(it)) }
    }
    raw.put("title", title.trim()).put("subject_id", subjectId ?: JSONObject.NULL)
    raw.remove("segments")
    require(metadata.toString().toByteArray(Charsets.UTF_8).size <= 60_000) { "Plan details are too long. Shorten the notes or topics." }
    return original.copy(title = title.trim(), subjectId = subjectId, synced = false,
        changeId = UUID.randomUUID().toString(), planDraft = raw.toString(), pendingBlocks = null)
}
