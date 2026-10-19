package com.folio.notes.mistakes

import com.folio.notes.NotePage
import com.folio.notes.PageLayers
import com.folio.notes.TextBox

/** A response is ordinary page text, so journals, backups, exports and history all keep it. */
internal object MistakeTypedResponse {
    fun find(page: NotePage, reviewId: String): TextBox? =
        page.texts.firstOrNull { it.id == "mistake-response-$reviewId" }

    fun box(page: NotePage, reviewId: String): TextBox {
        find(page, reviewId)?.let { return it }
        // Keep existing working clear when someone switches to typing halfway through a review.
        val right = maxOf(
            page.strokes.maxOfOrNull { stroke -> stroke.points.maxOfOrNull { it.x } ?: 0f } ?: 0f,
            page.texts.maxOfOrNull { it.x + it.width } ?: 0f,
            page.images.maxOfOrNull { it.x + it.width } ?: 0f,
        )
        return TextBox(id = "mistake-response-$reviewId", x = maxOf(48f, right + 32f), y = 48f,
            width = 640f, size = 26f, layer = PageLayers.initialActive(page.layers))
    }
}
