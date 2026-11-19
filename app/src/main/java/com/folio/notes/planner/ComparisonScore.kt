package com.folio.notes.planner

internal fun parseComparisonScore(text: String): Double? = text.trim().toDoubleOrNull()
    ?.takeIf { it.isFinite() && it in 0.0..100.0 }
