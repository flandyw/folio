package com.folio.notes

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The one corner-radius scale for the app.
 *
 * Every rounded surface picks a tier from here instead of a bare `N.dp` literal, so chips,
 * cards, grouped rows, sheets and the editor chrome stay visually related as the UI grows.
 * The ladder follows the Material 3 shape scale; the sizes between `large` and `panel` exist
 * because Folio groups content into card-in-card layouts that stock M3 tokens don't cover.
 *
 * `small -> medium -> large -> extraLarge -> panel`
 *
 * Use the `Radius` values only when a shape needs per-corner radii; otherwise use the
 * ready-made [RoundedCornerShape]s so the common case stays a single word.
 */
object FolioShapes {
    /** 8dp: thumbnails, badges and other small chips. */
    val smallRadius = 8.dp

    /** 12dp: inputs and compact surfaces nested inside a card. */
    val mediumRadius = 12.dp

    /** 16dp: the default card, list row and dialog body radius. */
    val largeRadius = 16.dp

    /** 24dp: prominent cards, grouped settings blocks and hero surfaces. */
    val extraLargeRadius = 24.dp

    /** 28dp: top-level panels, bottom sheets and the editor chrome. */
    val panelRadius = 28.dp

    val small = RoundedCornerShape(smallRadius)
    val medium = RoundedCornerShape(mediumRadius)
    val large = RoundedCornerShape(largeRadius)
    val extraLarge = RoundedCornerShape(extraLargeRadius)
    val panel = RoundedCornerShape(panelRadius)

    /** 2dp hairline ends (dividers and rules). */
    val hairline = RoundedCornerShape(2.dp)

    /** 3dp rounded progress and scroll bars. */
    val bar = RoundedCornerShape(3.dp)
}
