package com.folio.notes

import org.json.JSONObject

/**
 * Official VCAA past papers, read from the dataset Focal publishes (`public/vcaa-exam-resources.json`,
 * copied into `assets/` unchanged; refresh it by copying Focal's file again). Pure: no Android imports.
 */
data class VcaaExam(
    val studyName: String,
    val label: String,
    val url: String,
    val year: Int?,
    /** "VCAA" or "VCAA NHT", the same source names PDF import detects. */
    val provider: String,
    val type: ExamType,
    /** Examiner reports and assessment guides for this paper, best match first. */
    val reports: List<Pair<String, String>>,
) {
    /** The exam details a downloaded copy is imported with. */
    val tags: ExamTags get() {
        val subject = VcaaLibrary.subjectFor(studyName)
        return ExamTags(subject = subject, subjectText = if (subject == null) studyName else "", year = year,
            company = provider, type = type, marksTotal = VcaaMarks.total(subject, type))
    }
    val title: String get() = smartImportedNotebookName(tags, label)
}

object VcaaLibrary {
    private val aliases = mapOf(
        "mathematical methods" to VceSubject.MATHS_METHODS, "specialist mathematics" to VceSubject.SPECIALIST_MATHS,
        "general mathematics" to VceSubject.GENERAL_MATHS, "further mathematics" to VceSubject.GENERAL_MATHS,
    )

    /** Exact names only: a study Folio has no colour band for keeps its own name as text. */
    fun subjectFor(studyName: String): VceSubject? = studyName.trim().lowercase().let { name ->
        aliases[name] ?: VceSubject.entries.firstOrNull { it != VceSubject.OTHER && it.label.lowercase() == name }
    }

    private fun paperNumber(label: String): Int? =
        (Regex("(?i)\\b(?:exam(?:ination)?|paper)\\s*([1-9])\\b").find(label)
            ?: Regex("(?i)\\b([1-9])\\s+(?:exam(?:ination)?|paper|external assessment report|assessment guide)\\b").find(label))
            ?.groupValues?.get(1)?.toInt()

    fun parse(json: String): List<VcaaExam> {
        val studies = JSONObject(json).getJSONArray("studies")
        val seen = mutableSetOf<String>()
        val names = (0 until studies.length()).map { studies.getJSONObject(it).optString("studyName") }
        return (0 until studies.length()).flatMap { index ->
            val study = studies.getJSONObject(index)
            val page = study.optString("pageUrl")
            val nht = Regex("(?i)(?:^|[-/])nht(?:[-/]|$)").containsMatchIn(page)
            val resources = study.getJSONArray("resources").let { array -> (0 until array.length()).map(array::getJSONObject) }
            resources.filter { it.optString("kind") == "exam" && !Regex("(?i)\\btranscript\\b").containsMatchIn(it.optString("label")) &&
                it.optString("url").substringBefore('?').endsWith(".pdf", ignoreCase = true) }
                .mapNotNull { resource ->
                    val label = resource.optString("label")
                    val url = resource.optString("url")
                    if (!seen.add(url)) return@mapNotNull null
                    val year = resource.optInt("year").takeIf { it in 1900..2099 }
                    // The NHT page lists every subject, so the paper's own label names it.
                    val studyName = if (!nht) study.getString("studyName") else
                        Regex("(?i)^\\d{4}\\s+(?:VCE\\s+)?(.+?)(?:\\s+[1-9])?\\s+(?:written\\s+)?exam(?:ination)?(?:\\s+[1-9])?$")
                            .find(label)?.groupValues?.get(1)?.trim()
                            // "EAL" on the NHT page is "English as an Additional Language (EAL)" everywhere else.
                            ?.let { name -> names.firstOrNull { it.endsWith("($name)") } ?: name } ?: study.getString("studyName")
                    val paper = paperNumber(label)
                    val reports = resources.filter { it.optString("kind") == "report" }.mapNotNull { report ->
                        val reportLabel = report.optString("label")
                        val reportYear = report.optInt("year").takeIf { it > 0 }
                        val reportPaper = paperNumber(reportLabel)
                        if (reportYear != year || (paper != null && reportPaper != null && paper != reportPaper) ||
                            (nht && !reportLabel.contains(studyName, ignoreCase = true))) null
                        else Triple(reportLabel, report.optString("url"), (if (reportPaper == paper) 10 else 0) +
                            if (Regex("(?i)assessment guide").containsMatchIn(reportLabel)) 2 else 0)
                    }.sortedByDescending { it.third }.map { it.first to it.second }.distinctBy { it.second }
                    VcaaExam(studyName, label, url, year, if (nht) "VCAA NHT" else "VCAA",
                        when (paper) { 1 -> ExamType.EXAM_1; 2 -> ExamType.EXAM_2; else -> ExamType.EXAM }, reports)
                }
        }.sortedWith(compareBy<VcaaExam> { it.studyName.lowercase() }.thenByDescending { it.year ?: 0 }
            .thenBy { it.provider }.thenBy { it.type.ordinal })
    }

    /** A notebook that already holds this paper, matched on its exam details rather than its name. */
    fun imported(exam: VcaaExam, notes: List<Notebook>): Notebook? {
        val tags = exam.tags
        return notes.firstOrNull { note -> note.exam.company.equals(tags.company, true) && note.exam.year == tags.year &&
            note.exam.type == tags.type && note.exam.subjectLabel.equals(tags.subjectLabel, true) }
    }
}

enum class VcaaSort(val label: String) {
    NEWEST("Newest first"), OLDEST("Oldest first"), SUBJECT("Subject A–Z"),
}

enum class VcaaAvailability(val label: String) {
    ALL("All papers"), NOT_IMPORTED("Not imported yet"), IMPORTED("In your library"),
}

/**
 * The catalogue prepared once for browsing: lowercase search keys, newest-first order, per-study
 * counts and the key each paper's imported notebook is matched on. Building it is the only slow
 * part, so the app does it once per process ([VcaaCatalog]); every query afterwards is a scan over
 * a few thousand short strings.
 */
class VcaaIndex(exams: List<VcaaExam>) {
    /** Newest first, then by study, so a result list needs no sorting of its own. */
    val exams: List<VcaaExam> = exams.sortedWith(compareByDescending<VcaaExam> { it.year ?: 0 }
        .thenBy { it.studyName.lowercase() }.thenBy { it.provider }.thenBy { it.type.ordinal })
    private val keys: Array<String> = Array(this.exams.size) { i ->
        this.exams[i].let { "${it.studyName} ${it.year ?: ""} ${it.provider} ${it.type.label} ${it.label}" }.lowercase()
    }
    private val studyKeys: Array<String> = Array(this.exams.size) { this.exams[it].studyName.lowercase() }
    /** What [VcaaLibrary.imported] compares, folded into one string so ownership is a set lookup. */
    private val ownedKeys: Array<String> = Array(this.exams.size) { ownedKey(this.exams[it].tags) }

    val studyCounts: Map<String, Int> = this.exams.groupingBy { it.studyName }.eachCount()
    val studies: List<String> = studyCounts.keys.sortedBy { it.lowercase() }
    val years: List<Int> = this.exams.mapNotNull { it.year }.distinct().sortedDescending()
    val providers: List<String> = this.exams.map { it.provider }.distinct().sorted()
    val types: List<ExamType> = this.exams.map { it.type }.distinct().sortedBy { it.ordinal }

    /**
     * Papers matching every word of [query] (anywhere in study, year, provider, paper or title),
     * within [study], [year] and [type] when set. Words that match a study's name rank that study
     * first when searching all subjects.
     */
    fun search(
        query: String, study: String?, year: Int?, type: ExamType?,
        provider: String? = null, reportsOnly: Boolean = false,
        availability: VcaaAvailability = VcaaAvailability.ALL,
        owned: Map<String, Notebook> = emptyMap(), sort: VcaaSort = VcaaSort.NEWEST,
    ): List<VcaaExam> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hits = ArrayList<Int>()
        for (i in exams.indices) {
            val exam = exams[i]
            if (study != null && exam.studyName != study) continue
            if (year != null && exam.year != year) continue
            if (type != null && exam.type != type) continue
            if (provider != null && exam.provider != provider) continue
            if (reportsOnly && exam.reports.isEmpty()) continue
            val imported = ownedKeys[i] in owned
            if (availability == VcaaAvailability.IMPORTED && !imported) continue
            if (availability == VcaaAvailability.NOT_IMPORTED && imported) continue
            val key = keys[i]
            if (words.all { key.contains(it) }) hits += i
        }
        // Stable sort: a study whose name holds a typed word comes before the rest, newest first within each.
        if (sort == VcaaSort.NEWEST && study == null && words.isNotEmpty()) hits.sortBy { i -> if (words.any { studyKeys[i].contains(it) }) 0 else 1 }
        val found = hits.map { exams[it] }
        return when (sort) {
            VcaaSort.NEWEST -> found
            VcaaSort.OLDEST -> found.sortedWith(compareBy<VcaaExam> { it.year ?: Int.MAX_VALUE }
                .thenBy { it.studyName.lowercase() }.thenBy { it.provider }.thenBy { it.type.ordinal })
            VcaaSort.SUBJECT -> found.sortedWith(compareBy<VcaaExam> { it.studyName.lowercase() }
                .thenByDescending { it.year ?: 0 }.thenBy { it.provider }.thenBy { it.type.ordinal })
        }
    }

    /** Imported notebooks keyed for lookups by [ownedBy]. */
    fun owned(notes: List<Notebook>): Map<String, Notebook> {
        val map = HashMap<String, Notebook>()
        for (note in notes) map.getOrPut(ownedKey(note.exam.company, note.exam.year, note.exam.type, note.exam.subjectLabel)) { note }
        return map
    }

    fun ownedBy(exam: VcaaExam, owned: Map<String, Notebook>): Notebook? = owned[ownedKey(exam.tags)]

    private fun ownedKey(tags: ExamTags) = ownedKey(tags.company, tags.year, tags.type, tags.subjectLabel)
    private fun ownedKey(company: String, year: Int?, type: ExamType?, subject: String) =
        "${company.lowercase()}|$year|$type|${subject.lowercase()}"

    companion object {
        /** Studies the user already has notebooks for, so they lead the subject list. */
        fun yoursFirst(studies: List<String>, notes: List<Notebook>): List<String> {
            val yours = notes.map { it.exam.subjectLabel.lowercase() }.toSet()
            return studies.sortedWith(compareBy<String> { name ->
                if (name.lowercase() in yours || VcaaLibrary.subjectFor(name)?.label?.lowercase() in yours) 0 else 1 }.thenBy { it.lowercase() })
        }
    }
}
