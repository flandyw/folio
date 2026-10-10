package com.folio.notes

/**
 * What a long-press on the editor's share button does instead of opening the export menu.
 * OFF keeps the long-press inert, so the button behaves exactly as it always has.
 */
enum class ShareShortcut(val label: String) {
    OFF("Off"),
    NOTEBOOK_PDF("Share notebook as PDF"),
    PAGE_PNG("Share this page as image"),
    PAGE_PDF("Share this page as PDF");

    companion object {
        val DEFAULT = OFF

        fun safeValueOf(name: String?): ShareShortcut =
            try { valueOf(name ?: "") } catch (_: Exception) { DEFAULT }
    }
}
