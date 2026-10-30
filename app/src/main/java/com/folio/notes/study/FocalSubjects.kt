package com.folio.notes.study

import com.folio.notes.exam.VceSubject
import com.folio.notes.model.Notebook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Focal's built-in subject IDs are stable sync IDs, not Folio enum names. */
data class FocalSubject(val id: String, val name: String)

object FocalSubjects {
    private val separators = Regex("[^a-z0-9]+")
    val builtIn = listOf(
        FocalSubject("eng", "English"), FocalSubject("eng-lang", "English Language"),
        FocalSubject("lit", "Literature"), FocalSubject("mm", "Mathematical Methods"),
        FocalSubject("sm", "Specialist Mathematics"), FocalSubject("gm", "General Mathematics"),
        FocalSubject("csl", "Chinese Second Language"), FocalSubject("pe", "Physical Education"),
        FocalSubject("chem", "Chemistry"), FocalSubject("phys", "Physics"),
        FocalSubject("bio", "Biology"), FocalSubject("psych", "Psychology"),
        FocalSubject("hist", "History"), FocalSubject("geo", "Geography"),
        FocalSubject("econ", "Economics"), FocalSubject("bm", "Business Management")
    )
    private val folioIds = mapOf(
        VceSubject.GENERAL_MATHS to "gm", VceSubject.MATHS_METHODS to "mm",
        VceSubject.SPECIALIST_MATHS to "sm", VceSubject.PHYSICS to "phys",
        VceSubject.CHEMISTRY to "chem", VceSubject.BIOLOGY to "bio",
        VceSubject.PHYSICAL_EDUCATION to "pe", VceSubject.PSYCHOLOGY to "psych",
        VceSubject.ENGLISH to "eng", VceSubject.LITERATURE to "lit",
        VceSubject.ECONOMICS to "econ"
    )

    /** The subject's Folio cover band, so a study block wears the same colour as its notebooks. */
    fun color(id: String?): Long? = folioIds.entries.firstOrNull { it.value == id }?.key?.color

    fun suggest(note: Notebook, subjects: List<FocalSubject> = builtIn): String? {
        folioIds[note.exam.subject]?.let { return it }
        return find(note.exam.subjectText.ifBlank { note.title }, subjects)
    }

    fun find(text: String, subjects: List<FocalSubject> = builtIn): String? {
        val source = text.trim()
        if (source.isBlank()) return null
        val exact = subjects.firstOrNull { it.id.equals(source, true) || it.name.equals(source, true) }
        if (exact != null) return exact.id
        val normalized = source.lowercase().replace(separators, " ").trim()
        // Longest names win: English Language before English, Specialist before Mathematics.
        return subjects.sortedByDescending { it.name.length }.firstOrNull { subject ->
            val name = subject.name.lowercase().replace(separators, " ").trim()
            name.length >= 5 && " $normalized ".contains(" $name ")
        }?.id ?: folioIds[VceSubject.match(source)]
    }
}

/** The subject studied from [at] until the next switch, as Focal's `metadata.subjectSwitches`. */
data class FocalSubjectSwitch(val at: Long, val subjectId: String)

/**
 * A session studies one subject at a time; switching subject mid-session is recorded in `metadata.subjectSwitches`
 * and `subject_id` is always the latest subject, exactly as Focal's `studySessionSwitches`. A list whose last subject
 * is not `subject_id` was overruled by a client that only edits `subject_id`, so it is ignored.
 */
internal fun focalSubjectSwitches(entry: FocalStudyEntry): List<FocalSubjectSwitch> {
    val main = entry.subjectId ?: return emptyList()
    val raw = listOfNotNull(entry.metadata, entry.planDraft ?: entry.remotePayload).firstNotNullOfOrNull { json ->
        runCatching {
            val root = JSONObject(json)
            val metadata = root.optJSONObject("metadata") ?: root
            metadata.optJSONArray("subjectSwitches") ?: metadata.optJSONObject("legacy_metadata")?.optJSONArray("subjectSwitches")
        }.getOrNull()
    } ?: return emptyList()
    val switches = (0 until raw.length()).mapNotNull { index -> runCatching {
        val item = raw.getJSONObject(index)
        FocalSubjectSwitch(Instant.parse(item.getString("at")).toEpochMilli(), item.getString("subjectId").takeIf(String::isNotBlank)!!)
    }.getOrNull() }.sortedBy { it.at }
    return if (switches.lastOrNull()?.subjectId == main) switches else emptyList()
}

/** Cuts [blocks] where the subject switched, so every piece has one subject. Time before the first switch takes its subject. */
internal fun focalSubjectSpans(entry: FocalStudyEntry, blocks: List<FocalStudyInterval>, now: Long): List<Pair<FocalStudyInterval, String?>> {
    val switches = focalSubjectSwitches(entry)
    if (switches.isEmpty()) return blocks.map { it to entry.subjectId }
    fun subjectAt(at: Long) = (switches.lastOrNull { it.at <= at } ?: switches.first()).subjectId
    return blocks.flatMap { block ->
        val end = block.endAt ?: now
        val points = listOf(block.startAt) + switches.map { it.at }.filter { it > block.startAt && it < end } + end
        points.zipWithNext().map { (from, to) -> FocalStudyInterval(from, to) to subjectAt(from) }
            .fold(mutableListOf<Pair<FocalStudyInterval, String?>>()) { pieces, piece ->
                val last = pieces.lastOrNull()
                if (last?.second == piece.second) pieces[pieces.lastIndex] = last.first.copy(endAt = piece.first.endAt) to last.second
                else pieces += piece
                pieces
            }
    }
}

/** Every subject the session's blocks cover, in the order they were studied. */
internal fun focalSessionSubjects(entry: FocalStudyEntry, now: Long = System.currentTimeMillis()): List<String> =
    focalSubjectSpans(entry, focalSessionBlocks(entry) + entry.intervals.filter { it.endAt == null }, now)
        .mapNotNull { it.second }.distinct().ifEmpty { listOfNotNull(entry.subjectId) }

/** Records a switch to [subjectId] at [at]. A change within a minute of the last one corrects it rather than ending it. */
internal fun focalWithSubjectSwitch(switches: List<FocalSubjectSwitch>, at: Long, subjectId: String): List<FocalSubjectSwitch> {
    val kept = switches.lastOrNull()?.takeIf { at - it.at < 60_000L }?.let { switches.dropLast(1) } ?: switches
    return if (kept.lastOrNull()?.subjectId == subjectId) kept else kept + FocalSubjectSwitch(at, subjectId)
}

internal fun focalSubjectSwitchesJson(switches: List<FocalSubjectSwitch>): JSONArray = JSONArray().also { array ->
    switches.forEach { array.put(JSONObject().put("at", Instant.ofEpochMilli(it.at).toString()).put("subjectId", it.subjectId)) }
}

/** The session's subject names joined for a label, or null when it has none. */
internal fun focalSubjectNames(entry: FocalStudyEntry, subjects: List<FocalSubject>): String? =
    focalSessionSubjects(entry).map { id -> subjects.firstOrNull { it.id == id }?.name ?: id }.joinToString(" · ").ifBlank { null }
