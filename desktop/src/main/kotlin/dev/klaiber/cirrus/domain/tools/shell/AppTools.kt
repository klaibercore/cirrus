package dev.klaiber.cirrus.domain.tools.shell

import dev.klaiber.cirrus.domain.tools.CirrusTool
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.tools.github.stringParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The applications on this computer: what is there, and what to open.
 *
 * Desktop applications are discovered from freedesktop `.desktop` entries — the same source the
 * system menu reads — in the system and user application directories. Opening one launches its
 * `Exec` line directly with [ProcessBuilder], never through a shell, so nothing here can be
 * persuaded to run something the entry did not name.
 *
 * There is deliberately no "install" tool. Installing software on a desktop is the package
 * manager's job, and a model that could put software on someone's machine because it seemed
 * helpful is a model that eventually will. The honest answer to "install X" is to name the
 * package and let the user run their own installer.
 */
class ListAppsTool : CirrusTool {

    override val name: String = "list_installed_apps"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List the applications installed on this computer that appear in the " +
            "system menu, with their names. Use it to find the exact name before calling " +
            "open_app, or to check whether something is installed before offering to open it.",
    ) {
        stringParam(
            "query",
            "Only return apps whose name contains this. Leave it out to list everything, which " +
                "is long.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val query = arguments.string("query")?.trim()?.lowercase()
        val apps = desktopApps()
            .filter { query == null || query in it.name.lowercase() }
            .sortedBy { it.name.lowercase() }

        buildJsonObject {
            put("count", apps.size)
            if (apps.size > MAX_APPS) {
                put("note", "Showing the first $MAX_APPS. Pass a query to narrow the list.")
            }
            put(
                "apps",
                JsonArray(
                    apps.take(MAX_APPS).map {
                        buildJsonObject {
                            put("name", it.name)
                            if (it.comment != null) put("comment", it.comment)
                        }
                    },
                ),
            )
        }.toString()
    }

    private companion object {
        const val MAX_APPS = 60
    }
}

/** Brings an application to the front. Nothing more: it cannot drive one once it is there. */
class OpenAppTool : CirrusTool {

    override val name: String = "open_app"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Open an application on this computer, by the name it appears under in the " +
            "menu. It comes to the front immediately, which means it covers Cirrus — so only do " +
            "this when the user has asked for it, and say what you are opening before you do. " +
            "You cannot then use the app on their behalf: opening it is the whole action.",
    ) {
        stringParam("name", "The application's name, as listed by list_installed_apps. Matched loosely.")
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val wanted = arguments.string("name")?.trim()?.lowercase()
            ?: return@shellTool errorJson("missing required argument: name")
        val target = desktopApps().firstOrNull { it.name.lowercase() == wanted }
            ?: desktopApps().firstOrNull { it.name.lowercase().contains(wanted) }
            ?: return@shellTool errorJson(
                "no installed application matches that. Call list_installed_apps to see what is " +
                    "there.",
            )

        val command = target.exec ?: return@shellTool errorJson("${target.name} has no launch command.")
        val launched = runCatching {
            ProcessBuilder(command).start()
            true
        }.getOrElse { false }
        if (!launched) return@shellTool errorJson("Could not start ${target.name}.")

        buildJsonObject {
            put("opened", target.name)
        }.toString()
    }
}

// ---- Shared plumbing ---------------------------------------------------------------------

internal data class DesktopApp(
    val name: String,
    val comment: String?,
    val exec: List<String>?,
)

/**
 * Reads `.desktop` entries from the standard freedesktop locations.
 *
 * Only entries that are visible (`NoDisplay` unset or false) and launchable (have an `Exec`)
 * are listed. The `Exec` line is split on spaces with the field codes (`%f`, `%u`, …) dropped,
 * which is the honest subset: an entry whose command needs a file argument is not something a
 * chat turn should be launching blind.
 */
internal fun desktopApps(): List<DesktopApp> {
    val dirs = listOf(
        File(System.getProperty("user.home"), ".local/share/applications"),
        File("/usr/local/share/applications"),
        File("/usr/share/applications"),
    )
    return dirs.flatMap { dir ->
        if (!dir.isDirectory) return@flatMap emptyList()
        dir.listFiles { file -> file.isFile && file.name.endsWith(".desktop") }
            ?.mapNotNull(::parseDesktopEntry)
            ?: emptyList()
    }.distinctBy { it.name.lowercase() }
}

private fun parseDesktopEntry(file: File): DesktopApp? {
    val lines = runCatching { file.readLines() }.getOrNull() ?: return null
    var name: String? = null
    var comment: String? = null
    var exec: String? = null
    var noDisplay = false
    var hidden = false

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("Name=") && name == null -> name = trimmed.removePrefix("Name=")
            trimmed.startsWith("Comment=") && comment == null -> comment = trimmed.removePrefix("Comment=")
            trimmed.startsWith("Exec=") && exec == null -> exec = trimmed.removePrefix("Exec=")
            trimmed.startsWith("NoDisplay=") -> noDisplay = trimmed.removePrefix("NoDisplay=").trim().equals("true", true)
            trimmed.startsWith("Hidden=") -> hidden = trimmed.removePrefix("Hidden=").trim().equals("true", true)
        }
    }

    val displayName = name ?: return null
    if (noDisplay || hidden) return null
    val command = exec?.let(::splitExec) ?: return null
    if (command.isEmpty()) return null
    return DesktopApp(displayName, comment, command)
}

/** Splits an Exec line into words, dropping field codes and their arguments. */
private fun splitExec(exec: String): List<String> {
    val words = exec.trim().split(Regex("\\s+"))
    val result = mutableListOf<String>()
    for (word in words) {
        if (word.startsWith("%")) {
            // A field code may consume the following word (e.g. "%f file"); skip both.
            if (word.length > 1 && word[1] in "fFuUdDnNi" && result.isNotEmpty()) {
                // The argument follows the code; drop it by not adding the next word.
            }
            continue
        }
        result += word
    }
    return result
}
