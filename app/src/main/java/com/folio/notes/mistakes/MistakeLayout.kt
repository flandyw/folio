package com.folio.notes.mistakes

/** Uses the actual available window so split-screen and rotation follow the same rules. */
internal data class MistakeLayout(val columns: Int, val splitLibrary: Boolean, val splitReview: Boolean)

internal fun mistakeLayout(width: Int, height: Int): MistakeLayout {
    val landscape = width > height
    return MistakeLayout(
        columns = when { landscape && width >= 1200 -> 3; width >= 600 -> 2; else -> 1 },
        splitLibrary = landscape && width >= 1000,
        splitReview = landscape && width >= 840
    )
}
