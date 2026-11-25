package com.folio.notes.planner

import org.json.JSONObject

/** One durable write at a time: a second tap while one is in flight is ignored, and [onBusy] always ends false, even if the write throws. */
internal class SingleFlight(private val onBusy: (Boolean) -> Unit = {}) {
    var busy = false
        private set

    /** Runs [action] unless another is running; false means this call was ignored. */
    suspend fun run(action: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true; onBusy(true)
        try { action() } finally { busy = false; onBusy(false) }
        return true
    }
}

/** The Score tab's typed inputs. A blank SAC percentile is optional; anything typed must be a 0–100 percentage. */
internal class ScoreInputs(val sacPercentile: Double?, val examWeight: Double?, val fixedWeight: Boolean, val sacError: String?, val weightError: String?) {
    val valid get() = sacError == null && weightError == null
}

internal fun scoreInputs(subject: String, sacText: String, weightText: String): ScoreInputs {
    val fixed = StudyScore.hasExamSplit(subject)
    val sac = parseComparisonScore(sacText)
    // A split exam's weight is fixed by predict, so the typed field is never read for it.
    val weight = if (fixed) StudyScore.defaultExamWeight(subject) else parseComparisonScore(weightText)
    return ScoreInputs(sac, weight, fixed,
        if (sacText.isNotBlank() && sac == null) "Enter a percentile from 0 to 100, or leave it blank." else null,
        if (weight == null) "Enter an exam weight from 0 to 100." else null)
}

/** One ATAR row as the estimator sees it: the [result] it adds, or [problem] saying why it is left out ([incomplete] when nothing is entered yet). */
internal class AtarRowCheck(val result: StudyScore.Result?, val problem: String?, val incomplete: Boolean = false)

internal fun checkAtarRow(row: JSONObject, year: Int, studies: List<StudyScore.Scaling>, predictions: Map<String, Double>): AtarRowCheck {
    val ref = studies.find { it.code == row.optString("code") }
        ?: return AtarRowCheck(null, "Not counted: choose a study that has $year scaling data.")
    val predicted = predictions[StudyScore.studyKey(ref.studyName)]?.takeIf { row.optBoolean("usePrediction") }
    val text = row.optString("raw").trim()
    if (predicted == null && text.isEmpty()) return AtarRowCheck(null, "Not counted yet: enter a raw study score.", incomplete = true)
    val raw = predicted ?: text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        ?: return AtarRowCheck(null, "Not counted: enter the raw score as a number, like 35.")
    val shown = raw.toString().removeSuffix(".0")
    val source = if (predicted != null) "The predicted score $shown" else "A raw score of $shown"
    if (raw !in StudyScore.SCALED_RAW) return AtarRowCheck(null, "Not counted: $source is outside the 20–50 range VTAC scales.")
    return StudyScore.interpolate(raw, ref.points)?.let { AtarRowCheck(StudyScore.Result(row.optString("id"), ref.code, ref.studyName, raw, it), null) }
        ?: AtarRowCheck(null, "Not counted: $source is outside the $year scaling table for ${ref.studyName}.")
}
