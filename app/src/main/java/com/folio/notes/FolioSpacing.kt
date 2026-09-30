package com.folio.notes

import androidx.compose.ui.unit.dp

/**
 * The one spacing scale for the app.
 *
 * Feeds `padding`, `Arrangement.spacedBy` and `Spacer` gaps from a fixed set of steps so
 * screens share a rhythm instead of drifting value by value. The scale is the even ladder
 * the UI already leans on, kept explicit rather than t-shirt-sized because nine steps are
 * easier to reason about by their value than by a name:
 *
 * `2 -> 4 -> 6 -> 8 -> 10 -> 12 -> 16 -> 24 -> 32`
 *
 * Keep to these steps for new spacing; anything between them is a sign a screen picked a
 * number by feel.
 */
object FolioSpacing {
    val dp2 = 2.dp
    val dp4 = 4.dp
    val dp6 = 6.dp
    val dp8 = 8.dp
    val dp10 = 10.dp
    val dp12 = 12.dp
    val dp16 = 16.dp
    val dp24 = 24.dp
    val dp32 = 32.dp
}
