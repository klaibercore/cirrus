package dev.klaiber.cirrus.domain.tools.shell

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.klaiber.cirrus.BuildConfig
import dev.klaiber.cirrus.domain.tools.CirrusTool
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.int
import dev.klaiber.cirrus.domain.tools.github.intParam
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.tools.github.stringParam
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What time it is, answered properly.
 *
 * A model has no clock. Left to itself it either refuses to say, or — worse — answers from the date
 * its training stopped, which is wrong in a way that looks right. Every scheduling question, every
 * "how long until", every "is that this week?" depends on this being exact, so it is a tool rather
 * than something recovered from `date` through a shell: no process, no parsing, and a timezone the
 * app is certain of.
 */
@Singleton
class DateTimeTool @Inject constructor() : CirrusTool {

    override val name: String = "get_datetime"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "The current date and time on the user's phone, in their own timezone. Call " +
            "this before answering anything that depends on today — deadlines, \"how long until\", " +
            "\"what day is it\", anything you would otherwise guess at. You have no clock of your " +
            "own, and a date recalled from memory is usually months wrong. The offsets shift the " +
            "answer, so \"this time next week\" is offset_days 7 rather than arithmetic you do " +
            "yourself.",
    ) {
        stringParam(
            "timezone",
            "IANA zone such as Europe/Berlin. Defaults to the phone's own, which is almost always " +
                "what is wanted.",
        )
        intParam("offset_days", "Shift the answer by this many days. Negative for the past.")
        intParam("offset_hours", "Shift the answer by this many hours.")
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val zone = arguments.string("timezone")?.let { requested ->
            runCatching { ZoneId.of(requested) }.getOrNull()
                ?: return@shellTool errorJson(
                    "unknown timezone: $requested. Use an IANA name such as Europe/Berlin.",
                )
        } ?: ZoneId.systemDefault()

        val now = ZonedDateTime.now(zone)
            .plusDays((arguments.int("offset_days") ?: 0).toLong())
            .plusHours((arguments.int("offset_hours") ?: 0).toLong())

        buildJsonObject {
            put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("date", now.toLocalDate().toString())
            put("time", now.toLocalTime().withNano(0).toString())
            put("human", now.format(HUMAN))
            put("weekday", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            put("is_weekend", now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY)
            put("week_of_year", now.get(WeekFields.ISO.weekOfWeekBasedYear()))
            put("day_of_year", now.dayOfYear)
            put("days_in_month", YearMonth.from(now).lengthOfMonth())
            put("timezone", zone.id)
            put("utc_offset", now.offset.id)
            put("unix_seconds", now.toEpochSecond())
        }.toString()
    }

    private companion object {
        val HUMAN: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm", Locale.ENGLISH)
    }
}

/**
 * A month, laid out, plus the facts about one date that people actually ask for.
 *
 * The grid is there because "which Tuesdays are in March?" is a question about a shape, and a model
 * reading a laid-out month gets it right where one counting forwards from the first does not. The
 * single-date half is the other question — "how far away is the 14th?" — and it is answered against
 * the phone's clock rather than against a guess.
 */
@Singleton
class CalendarTool @Inject constructor() : CirrusTool {

    override val name: String = "show_calendar"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "A calendar month laid out as text, and the facts about a particular date: " +
            "which weekday it falls on, which ISO week it is in, and how many days away it is. " +
            "Use it for anything about the shape of a month — which day the 3rd is, how many " +
            "Mondays are left, when the last working day falls — rather than counting it out " +
            "yourself. Weeks start on Monday.",
    ) {
        intParam("month", "Month number, 1-12. Defaults to the current month.")
        intParam("year", "Four-digit year. Defaults to the current year.")
        stringParam(
            "date",
            "An ISO date (2026-08-14) to report on. Its month is shown, and the answer includes " +
                "how far it is from today.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val today = LocalDate.now(ZoneId.systemDefault())

        val subject = arguments.string("date")?.let { raw ->
            runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
                ?: return@shellTool errorJson("could not read date \"$raw\"; use the form 2026-08-14")
        }

        val month = subject?.let { YearMonth.from(it) }
            ?: YearMonth.of(
                arguments.int("year") ?: today.year,
                (arguments.int("month") ?: today.monthValue).coerceIn(1, 12),
            )

        buildJsonObject {
            put("month", month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            put("year", month.year)
            put("today", today.toString())
            put("grid", grid(month, today))
            put("days_in_month", month.lengthOfMonth())
            put("first_weekday", month.atDay(1).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            if (subject != null) {
                putJsonObject("date") {
                    put("date", subject.toString())
                    put("weekday", subject.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("iso_week", subject.get(WeekFields.ISO.weekOfWeekBasedYear()))
                    val days = ChronoUnit.DAYS.between(today, subject)
                    put("days_from_today", days)
                    put("relative", relative(days))
                }
            }
        }.toString()
    }

    /**
     * Monday-first, today in brackets.
     *
     * Four characters per column rather than three, so the brackets round today take the space of
     * the two padding spaces every other cell has. A marker that shifted the column under it would
     * put every weekday after it one place out, which is the one error a laid-out month must not
     * make.
     */
    private fun grid(month: YearMonth, today: LocalDate): String = buildString {
        appendLine(listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").joinToString("") { it.padEnd(4) })
        append(" ".repeat((month.atDay(1).dayOfWeek.value - 1) * 4))
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val cell = day.toString().padStart(2)
            append(if (date == today) "[$cell]" else "$cell  ")
            if (date.dayOfWeek == DayOfWeek.SUNDAY) appendLine()
        }
    }.trimEnd().lines().joinToString("\n") { it.trimEnd() }

    private fun relative(days: Long): String = when {
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days == -1L -> "yesterday"
        days > 0 -> "in $days days"
        else -> "${-days} days ago"
    }
}

/**
 * The phone, described in one shot.
 *
 * The equivalent of running `fastfetch`, except that every line comes from a platform API rather
 * than from parsing somebody's output: the shell an app gets on Android cannot see most of this,
 * and what it can see it reports inconsistently across manufacturers.
 *
 * The `shell` block is the part that earns its place. It says which of the allowed programs are
 * genuinely present on *this* device — Android's toybox varies by version and vendor — so the model
 * can find out rather than discovering it one failed command at a time.
 */
@Singleton
class SystemInfoTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: ShellWorkspace,
) : CirrusTool {

    override val name: String = "system_info"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "A summary of the phone Cirrus is running on: device and Android version, " +
            "CPU, memory, storage, battery, network, locale and timezone, plus which shell " +
            "programs this device actually has. Use it when the user asks about their device, " +
            "when an answer depends on how much room or battery is left, or before a run_command " +
            "session, to see what is available.",
    ) {}

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        buildJsonObject {
            putJsonObject("device") {
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("kernel", System.getProperty("os.version") ?: "unknown")
                put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                put("uptime", humanDuration(SystemClock.elapsedRealtime()))
            }
            putJsonObject("cpu") {
                put("cores", Runtime.getRuntime().availableProcessors())
            }
            putJsonObject("memory") {
                val info = ActivityManager.MemoryInfo()
                context.getSystemService<ActivityManager>()?.getMemoryInfo(info)
                put("total", humanBytes(info.totalMem))
                put("available", humanBytes(info.availMem))
                put("low", info.lowMemory)
            }
            putJsonObject("storage") {
                val stat = StatFs(Environment.getDataDirectory().absolutePath)
                val total = stat.blockCountLong * stat.blockSizeLong
                val free = stat.availableBlocksLong * stat.blockSizeLong
                put("total", humanBytes(total))
                put("free", humanBytes(free))
                put("used_percent", if (total > 0) ((total - free) * 100 / total).toInt() else 0)
            }
            battery()?.let { put("battery", it) }
            put("network", network())
            putJsonObject("locale") {
                val locale = context.resources.configuration.locales[0]
                put("language", locale.toLanguageTag())
                put("timezone", ZoneId.systemDefault().id)
            }
            putJsonObject("cirrus") {
                put("version", BuildConfig.VERSION_NAME)
                put("workspace_files", workspace.entries().count { !it.isDirectory })
                put("workspace_bytes", workspace.usedBytes())
                // Which jobs already have scratch files, so a model resuming a conversation can
                // reuse a topic rather than opening a second one for work it already started.
                val topics = workspace.topics()
                if (topics.isNotEmpty()) {
                    put(
                        "workspace_topics",
                        topics.joinToString(" ") { "${it.name}(${it.fileCount})" },
                    )
                }
            }
            putJsonObject("shell") {
                val present = CommandPolicy.allowedPrograms
                    .filter { program -> SHELL_DIRS.any { File(it, program).exists() } }
                    .sorted()
                put("available", present.joinToString(" "))
                put(
                    "missing",
                    (CommandPolicy.allowedPrograms - present.toSet()).sorted().joinToString(" "),
                )
            }
        }.toString()
    }

    private fun battery(): JsonElement? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return buildJsonObject {
            if (level >= 0 && scale > 0) put("percent", level * 100 / scale)
            put("charging", plugged != 0)
        }
    }

    private fun network(): String {
        val manager = context.getSystemService<ConnectivityManager>() ?: return "unknown"
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            ?: return "offline"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "connected"
        }
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ENGLISH, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.ENGLISH, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> "$bytes B"
    }

    private fun humanDuration(millis: Long): String {
        val minutes = millis / 60_000
        val days = minutes / 1_440
        val hours = (minutes % 1_440) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }

    private companion object {
        val SHELL_DIRS = listOf("/system/bin", "/system/xbin")
    }
}
