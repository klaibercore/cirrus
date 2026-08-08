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
