@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/** A stored UTC instant as a local calendar date, e.g. "20 Oct 2026"; never shows raw ISO text. */
internal fun localDateLabel(iso: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { Instant.ofEpochMilli(timestamp(iso)).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }
        .getOrDefault(iso.take(10))

internal fun dueLabel(dueAt: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val due = runCatching { Instant.ofEpochMilli(timestamp(dueAt)).atZone(zone) }.getOrNull()
        ?: return "Unknown due date"
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), due.toLocalDate())
    return when {
        days < 0 -> "${-days}d overdue"
        due.toInstant().toEpochMilli() <= now -> "Due today"
        days == 0L -> "Later today · ${due.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        days == 1L -> "Due tomorrow"
        days < 30 -> "Due in ${days}d"
        else -> "Due ${due.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}"
    }
}

internal fun intervalLabel(schedule: MistakeSchedule): String {
    if (schedule.intervalDays < 1) {
        val minutes = (schedule.intervalDays * 24 * 60).roundToLong()
        return if (minutes < 60) "${minutes.coerceAtLeast(1)} min" else "${(minutes + 30) / 60} h"
    }
    val days = schedule.intervalDays.toLong()
    return when {
        days <= 0 -> "today"
        days == 1L -> "1 day"
        days < 30 -> "$days days"
        days < 365 -> "${days / 30} mo"
        else -> "${days / 365} yr"
    }
}

internal fun trimMark(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
