package com.folio.notes.backup

import com.folio.notes.model.Notebook

/** What a set of backup exclusions still leaves protected. Pure, so it stays cheap to test. */
internal data class BackupExclusionImpact(
    val protectedNotebooks: Int, val protectedPages: Int,
    val excludedNotebooks: Int, val excludedPages: Int,
) {
    val totalNotebooks: Int get() = protectedNotebooks + excludedNotebooks

    /** Every notebook is excluded, so library backups would save none of them. */
    val nothingProtected: Boolean get() = totalNotebooks > 0 && protectedNotebooks == 0

    val protectedSummary: String get() = when {
        totalNotebooks == 0 -> "No notebooks to back up yet."
        nothingProtected -> "No notebooks are protected by library backups."
        else -> "${count(protectedNotebooks, "notebook")} (${count(protectedPages, "page")}) still protected by library backups."
    }

    val excludedSummary: String get() =
        if (excludedNotebooks == 0) "Nothing excluded."
        else "${count(excludedNotebooks, "notebook")} (${count(excludedPages, "page")}) excluded of $totalNotebooks."

    companion object {
        fun of(notes: List<Notebook>, excludedIds: Set<String>): BackupExclusionImpact {
            val (excluded, kept) = notes.partition { it.id in excludedIds }
            return BackupExclusionImpact(kept.size, kept.sumOf { it.pages.size }, excluded.size, excluded.sumOf { it.pages.size })
        }

        private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"
    }
}
