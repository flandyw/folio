package com.folio.notes

/** Calibrate samples before storing them so saved ink and exports retain the chosen response. */
object PenPressure {
    val sensitivityRange = .25f..3f
    val variationRange = 0f..2f

    fun sensitivity(value: Float) = if (value.isFinite()) value.coerceIn(sensitivityRange) else 1f
    fun variation(value: Float) = if (value.isFinite()) value.coerceIn(variationRange) else 1f

    fun sample(raw: Float, enabled: Boolean = true, sensitivity: Float = 1f, variation: Float = 1f): Float {
        if (!enabled) return 1f
        val pressure = if (raw.isFinite()) raw.coerceAtLeast(0f) else 1f
        val calibrated = (pressure * sensitivity(sensitivity)).coerceIn(.25f, 1.8f)
        return (1f + (calibrated - 1f) * variation(variation)).coerceIn(.25f, 1.8f)
    }
}
