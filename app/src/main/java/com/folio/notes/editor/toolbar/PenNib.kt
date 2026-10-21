package com.folio.notes.editor.toolbar

import kotlin.math.abs

/**
 * One-tap pen characters for the pen's tool options. A nib is only a bundle of settings that already
 * exist — the pen's pressure response ([ToolOptions]) plus the global *Thinner when fast* and *Soft
 * stroke ends* preferences — so choosing one stores nothing new and needs no format change. Pure, so
 * the matching rule is cheap to exercise.
 */
enum class PenNib(
    val label: String,
    val pressure: Boolean, val curve: Float, val sensitivity: Float, val variation: Float,
    val speedWidth: Boolean, val taper: Float,
) {
    /** Even width whatever the pressure: diagrams, maths, neat print. */
    FINELINER("Fineliner", false, 1f, 1f, 1f, false, 0f),
    /** A little swell under a firm press, crisp ends. */
    BALLPOINT("Ballpoint", true, 1.5f, .9f, .5f, false, 0f),
    /** Pressure and speed both shape the line, with softly inked ends. */
    FOUNTAIN("Fountain", true, 1f, 1f, 1.2f, true, .35f),
    /** A light touch already lays down full width; wide swing and long tapers. */
    BRUSH("Brush", true, .7f, 1.3f, 2f, true, .8f);

    fun applyTo(options: ToolOptions): ToolOptions = options.copy(pressure = pressure, pressureCurve = curve,
        pressureSensitivity = sensitivity, pressureVariation = variation)

    companion object {
        private fun near(a: Float, b: Float) = abs(a - b) < .005f

        /** The nib the current settings match, or null once anything has been fine-tuned by hand. */
        fun matching(options: ToolOptions, speedWidth: Boolean, taper: Float): PenNib? = entries.firstOrNull { nib ->
            nib.pressure == options.pressure && nib.speedWidth == speedWidth && near(nib.taper, taper) &&
                (!nib.pressure || (near(nib.curve, options.pressureCurve) && near(nib.sensitivity, options.pressureSensitivity) &&
                    near(nib.variation, options.pressureVariation)))
        }
    }
}
