package dev.klaiber.cirrus.ui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Relative day grouping used by the conversation drawer. */
enum class DateBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("Previous 7 days"),
    THIS_MONTH("Previous 30 days"),
    OLDER("Older"),
}

fun bucketFor(epochMillis: Long, now: Long = System.currentTimeMillis()): DateBucket {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val dayMillis = TimeUnit.DAYS.toMillis(1)
    return when {
        epochMillis >= startOfToday -> DateBucket.TODAY
        epochMillis >= startOfToday - dayMillis -> DateBucket.YESTERDAY
        epochMillis >= startOfToday - dayMillis * 7 -> DateBucket.THIS_WEEK
        epochMillis >= startOfToday - dayMillis * 30 -> DateBucket.THIS_MONTH
        else -> DateBucket.OLDER
    }
}

fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))

/** Nanoseconds as reported by Ollama, rendered as a compact duration. */
fun formatNanos(nanos: Long): String {
    val millis = nanos / 1_000_000.0
    return when {
        millis < 1_000 -> "${millis.toInt()} ms"
        millis < 60_000 -> "%.1f s".format(millis / 1_000)
        else -> "%d:%02d".format((millis / 60_000).toInt(), ((millis % 60_000) / 1_000).toInt())
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * When something next happens, said the way a person would say it.
 *
 * "in 15 hours" is arithmetic; "tomorrow at 07:30" is an answer. The two disagree constantly —
 * anything scheduled for the morning is always some awkward number of hours away — and the clock
 * time is the half people check a schedule for.
 */
fun formatWhen(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (epochMillis - now) / 60_000
    if (minutes < 1) return "any moment"
    if (minutes < 60) return "in $minutes min"

    val clock = formatTime(epochMillis)
    val days = calendarDaysBetween(now, epochMillis)
    return when {
        days <= 0L -> "today at $clock"
        days == 1L -> "tomorrow at $clock"
        days < 7L -> "${SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(epochMillis))} at $clock"
        else -> SimpleDateFormat("d MMM 'at' HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }
}

/** Whole calendar days from one instant to another, ignoring the time of day within each. */
private fun calendarDaysBetween(from: Long, to: Long): Long {
    fun startOfDay(at: Long) = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    // Rounded rather than truncated: a daylight-saving day is 23 or 25 hours long, and integer
    // division of that by 24 loses or invents a day exactly twice a year.
    return Math.round((startOfDay(to) - startOfDay(from)) / TimeUnit.DAYS.toMillis(1).toDouble())
}

/** A run's wall-clock length, in the coarsest unit that is still informative. */
fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 1 -> "under a second"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

/**
 * How long ago, in the shortest form that is still honest.
 *
 * Deliberately coarse: "3 days ago" is what someone wants to know about a memory, and a timestamp
 * to the minute invites a precision the underlying data does not have.
 */
fun formatRelative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - epochMillis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> if (hours == 1L) "an hour ago" else "$hours hours ago"
        days < 7 -> if (days == 1L) "yesterday" else "$days days ago"
        days < 30 -> "${days / 7} ${if (days / 7 == 1L) "week" else "weeks"} ago"
        days < 365 -> "${days / 30} ${if (days / 30 == 1L) "month" else "months"} ago"
        else -> "over a year ago"
    }
}
