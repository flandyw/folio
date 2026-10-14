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
                    }.sortedByDescending { it.third }.take(2).map { it.first to it.second }
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
