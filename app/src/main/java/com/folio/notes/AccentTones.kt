package com.folio.notes

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Turns one user-chosen colour into a whole accent family.
 *
 * The rule is deliberately simple: every role keeps the *lightness* it already had in the
 * active scheme and only borrows the accent's hue and saturation. Lightness is what carries
 * contrast, so the `on…` pairings, the tonal steps and the ink-on-paper balance survive the
 * change — a teal accent cannot quietly produce white-on-white buttons. Surfaces, outlines and
 * the error family are left alone so paper still looks like paper.
 *
 * Free of Android and Compose UI types (only `Color`/`ColorScheme` values) so the tone maths
 * can be exercised on the JVM or in a REPL.
 */
object AccentTones {
    /** Tertiary sits a little off the accent's hue, so it reads as a neighbour rather than a clone. */
    private const val TERTIARY_HUE_SHIFT = 34f

    /** Saturation multipliers, tuned so containers stay quiet and accents stay vivid. */
    private const val PRIMARY_SAT = 1f
    private const val CONTAINER_SAT = 0.42f
    private const val SECONDARY_SAT = 0.55f
    private const val SECONDARY_CONTAINER_SAT = 0.34f
    private const val TERTIARY_SAT = 0.78f
    private const val TERTIARY_CONTAINER_SAT = 0.38f

    /** Lightness nudged per step while chasing a contrast target, and how long to keep trying. */
    private const val LIGHTNESS_STEP = 0.01f
    private const val MAX_CONTRAST_STEPS = 100

    fun hueOf(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val delta = high - low
        if (delta == 0f) return 0f
        val hue = when (high) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    fun saturationOf(color: Color): Float {
        val high = max(color.red, max(color.green, color.blue))
        val low = min(color.red, min(color.green, color.blue))
        return if (high <= 0f) 0f else (high - low) / high
    }

    fun lightnessOf(color: Color): Float =
        (max(color.red, max(color.green, color.blue)) + min(color.red, min(color.green, color.blue))) / 2f

    fun hsl(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
    }

    /** The accent as a plain colour, with the saturation slider's 0 meaning "leave it grey". */
    fun of(hue: Float, saturation: Float, lightness: Float): Color =
        hsl(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))

    /** Re-tints [scheme] around [accent]; a null accent returns the scheme untouched. */
    fun tinted(scheme: ColorScheme, accent: Color?): ColorScheme {
        if (accent == null) return scheme
        val hue = hueOf(accent)
        val sat = saturationOf(accent)
        /**
         * Borrows the accent's hue and saturation but keeps the pairing readable: the role
         * starts at its own lightness and walks away from its `on…` colour until the contrast it
         * had in the original scheme is restored. Hue therefore cannot quietly cost contrast,
         * which a lightness-only tint does as soon as a vivid accent meets a mid-tone container.
         */
        fun role(base: Color, on: Color, hueShift: Float = 0f, satScale: Float = 1f): Color =
            withContrast(base, on, hue + hueShift, sat * satScale, contrast(base, on))
        return scheme.copy(
            primary = role(scheme.primary, scheme.onPrimary, satScale = PRIMARY_SAT),
            primaryContainer = role(scheme.primaryContainer, scheme.onPrimaryContainer, satScale = CONTAINER_SAT),
            secondary = role(scheme.secondary, scheme.onSecondary, satScale = SECONDARY_SAT),
            secondaryContainer = role(scheme.secondaryContainer, scheme.onSecondaryContainer, satScale = SECONDARY_CONTAINER_SAT),
            tertiary = role(scheme.tertiary, scheme.onTertiary, TERTIARY_HUE_SHIFT, TERTIARY_SAT),
            tertiaryContainer = role(scheme.tertiaryContainer, scheme.onTertiaryContainer, TERTIARY_HUE_SHIFT, TERTIARY_CONTAINER_SAT),
        )
    }

    /** How much lighter or darker [base] has to be to keep its contrast against [on]. */
    private fun withContrast(base: Color, on: Color, hue: Float, sat: Float, minimum: Float): Color {
        var lightness = lightnessOf(base)
        val away = if (lightnessOf(on) >= 0.5f) -1f else 1f
        var result = hsl(hue, sat, lightness, base.alpha)
        var steps = 0
        while (contrast(result, on) < minimum && steps < MAX_CONTRAST_STEPS) {
            lightness = (lightness + away * LIGHTNESS_STEP).coerceIn(0f, 1f)
            result = hsl(hue, sat, lightness, base.alpha)
            steps++
        }
        return result
    }

    /** WCAG relative luminance of an sRGB colour. */
    fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.coerceIn(0f, 1f).toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** WCAG contrast ratio between two opaque colours, 1…21. */
    fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val high = max(la, lb)
        val low = min(la, lb)
        return ((high + 0.05) / (low + 0.05)).toFloat()
    }

    /** `#RRGGBB`, or null when [raw] is blank or not six hex digits. */
    fun parseHex(raw: String?): Color? {
        val text = raw?.trim()?.removePrefix("#") ?: return null
        if (text.length != 6) return null
        val value = text.toLongOrNull(16) ?: return null
        return Color(0xFF000000L or value)
    }

    /** Six-digit hex without the `#`, matching how colours are stored in preferences. */
    fun hex(color: Color): String =
        String.format(java.util.Locale.ROOT, "%06X", colorToArgb(color) and 0xFFFFFF)

    /** Compose's own ARGB conversion, spelled out so this file needs no extra imports. */
    fun colorToArgb(color: Color): Int {
        val a = (color.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        val r = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}