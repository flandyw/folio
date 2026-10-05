package com.folio.notes

/**
 * The printed design on a notebook's decorative cover.
 *
 * A notebook still stores one `cover` int. The low byte is the colour index (built-ins, then the
 * user's own colours), exactly as before; the next byte is the design. Every cover saved before
 * designs existed has a zero there, so it simply wears the default design in its old colour, and
 * no migration or version bump is needed. An unknown design byte (written by a newer build) falls
 * back to the default design rather than failing. Pure on purpose: no Android or Compose imports.
 */
enum class CoverStyle(val label: String) {
    FOLIO("Folio"),
    HORIZON("Horizon"),
    BLUEPRINT("Blueprint"),
    BAND("Band"),
    ARCH("Arch"),
    DOTTED("Dotted"),
    BLOCK("Block"),
    RULED("Ruled");

    companion object {
        private const val COLOR_MASK = 0xFF
        private const val STYLE_SHIFT = 8

        fun styleOf(cover: Int): CoverStyle = entries.getOrElse((cover ushr STYLE_SHIFT) and 0xFF) { FOLIO }

        fun colorIndex(cover: Int): Int = cover and COLOR_MASK

        fun pack(style: CoverStyle, colorIndex: Int): Int = (style.ordinal shl STYLE_SHIFT) or colorIndex.coerceIn(0, COLOR_MASK)

        fun withColor(cover: Int, colorIndex: Int): Int = pack(styleOf(cover), colorIndex)

        fun withStyle(cover: Int, style: CoverStyle): Int = pack(style, colorIndex(cover))
    }
}
