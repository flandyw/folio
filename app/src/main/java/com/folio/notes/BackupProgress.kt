package com.folio.notes

/** Counts describe the current phase; byte counts are uncompressed source data. */
internal object BackupProgress {
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KiB"
        else -> java.lang.String.format(java.util.Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024))
    }
}
