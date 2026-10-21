package com.folio.notes.app

/**
 * The destinations beside the shelf that can be switched off in Settings. Library and Settings are
 * not listed: the shelf is home and Settings is how this is undone. Stored as the *hidden* names,
 * so a destination added later shows up for everyone until they turn it off. Pure: no Android imports.
 */
enum class SidebarItem(val label: String, val summary: String) {
    MISTAKES("Mistakes", "Review and practise questions you got wrong"),
    STUDY("Study", "Timer, sessions and the day timeline"),
    PROGRESS("Progress", "Marks, results and upcoming exams"),
    MUSIC("Music", "Sheet music shelf and reader"),
    VCAA("VCAA exams", "Search and import official past papers");

    companion object {
        const val PREF_KEY = "navigation.hidden"

        /** Unknown names (from a newer or older build) are ignored. */
        fun hidden(raw: String?): Set<SidebarItem> =
            raw.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }.toSet()

        fun encode(hidden: Set<SidebarItem>): String = entries.filter { it in hidden }.joinToString(",") { it.name }

        /** Whether [item] gets a place in the sidebar: switched on, or the pane being looked at right now. */
        fun shown(item: SidebarItem, hidden: Set<SidebarItem>, active: Boolean) = item !in hidden || active
    }
}
