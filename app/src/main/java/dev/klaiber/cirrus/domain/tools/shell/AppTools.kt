package dev.klaiber.cirrus.domain.tools.shell

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The apps on the phone: what is there, what to open, and what to install.
 *
 * Installing deserves a word, because it is the one thing here that reaches outside Cirrus. Nothing
 * in this file installs anything. [InstallAppTool] opens the store's page for a package and stops;
 * the install itself is Android's own flow, with Android's own confirmation, decided by the person
 * holding the phone. That is not a limitation worked around — it is the design. A model that could
 * put software on someone's phone because it seemed helpful is a model that will eventually put
 * software on someone's phone because it seemed helpful.
 *
 * The same reasoning caps what this can do about command-line programs. Since API 29 Android
 * refuses to execute a binary an app has downloaded into its own data directory, so there is no
 * honest way to `apt install` anything into Cirrus. What there *is* is a real terminal app —
 * Termux and its like — which the user installs through the store like any other app, and which
 * carries its own package manager. [InstallAppTool] can offer them that; it cannot be that.
 */
@Singleton
class ListAppsTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : CirrusTool {

    override val name: String = "list_installed_apps"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List the apps installed on the phone that have a launcher icon, with their " +
            "package names. Use it to find the exact package name before calling open_app, or to " +
            "check whether something is installed before offering to install it.",
    ) {
        stringParam(
            "query",
            "Only return apps whose name or package contains this. Leave it out to list " +
                "everything, which is long.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val query = arguments.string("query")?.trim()?.lowercase()
        val apps = context.launchableApps()
            .filter { query == null || query in it.label.lowercase() || query in it.packageName.lowercase() }
            .sortedBy { it.label.lowercase() }

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
                            put("name", it.label)
                            put("package", it.packageName)
                            if (it.system) put("system", true)
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

/** Brings an app to the front. Nothing more: it cannot drive one once it is there. */
@Singleton
class OpenAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : CirrusTool {

    override val name: String = "open_app"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Open an app on the phone, by package name or by the name the user calls " +
            "it. It comes to the front immediately, which means it covers Cirrus — so only do " +
            "this when the user has asked for it, and say what you are opening before you do. " +
            "You cannot then use the app on their behalf: opening it is the whole action.",
    ) {
        stringParam("package", "Exact package name, such as com.android.calculator2.")
        stringParam("name", "The app's name, if you do not know the package. Matched loosely.")
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val target = context.resolveApp(arguments.string("package"), arguments.string("name"))
            ?: return@shellTool errorJson(
                "no installed app matches that. Call list_installed_apps to see what is there.",
            )

        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: return@shellTool errorJson("${target.label} has no screen that can be opened.")

        context.startForeground(intent)?.let { return@shellTool it }

        buildJsonObject {
            put("opened", target.label)
            put("package", target.packageName)
        }.toString()
    }
}

/**
 * Hands the user a store page. It is default-off, and says so where the model can read it.
 */
@Singleton
class InstallAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : CirrusTool {

    override val name: String = "install_app"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Open the Play Store page for an app so the user can install it. This does " +
            "NOT install anything: it opens the listing, and the user decides. Say what you are " +
            "about to open and why before you call it, and only call it when they have asked for " +
            "software — never as a suggestion they did not invite.\n\n" +
            "Prefer small, well-known, single-purpose apps. If they want a command-line program, " +
            "the answer is a terminal app such as Termux (package com.termux), which brings its " +
            "own package manager: Android does not let Cirrus install command-line software into " +
            "itself. Check with list_installed_apps first — offering to install something that " +
            "is already there wastes everybody's time.",
    ) {
        stringParam("package", "Package name to open the listing for, such as com.termux.")
        stringParam("query", "Search the store instead, when you do not know the package name.")
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val packageName = arguments.string("package")
        val query = arguments.string("query")
        if (packageName == null && query == null) {
            return@shellTool errorJson("give either a package or a query")
        }

        if (packageName != null && context.isInstalled(packageName)) {
            return@shellTool buildJsonObject {
                put("already_installed", true)
                put("package", packageName)
                put("note", "It is already on the phone. Use open_app instead of installing it.")
            }.toString()
        }

        val uri = if (packageName != null) {
            "market://details?id=$packageName"
        } else {
            "market://search?q=${Uri.encode(query)}&c=apps"
        }
        val web = if (packageName != null) {
            "https://play.google.com/store/apps/details?id=$packageName"
        } else {
            "https://play.google.com/store/search?q=${Uri.encode(query)}&c=apps"
        }

        val failure = context.startForeground(Intent(Intent.ACTION_VIEW, uri.toUri()))
            // A device without the Play Store still has a browser, and the web listing installs
            // just as well through it.
            ?: return@shellTool storeOpened(packageName, query)
        val fallback = context.startForeground(Intent(Intent.ACTION_VIEW, web.toUri()))
            ?: return@shellTool storeOpened(packageName, query)

        errorJson("$failure $fallback")
    }

    private fun storeOpened(packageName: String?, query: String?): String = buildJsonObject {
        put("opened_store_page", packageName ?: query.orEmpty())
        put(
            "status",
            "The store page is now on screen. Nothing has been installed — tell the user to tap " +
                "Install if they want it, and that they can come straight back.",
        )
    }.toString()
}

// ---- Shared plumbing ---------------------------------------------------------------------

internal data class InstalledApp(val label: String, val packageName: String, val system: Boolean)

internal fun Context.launchableApps(): List<InstalledApp> {
    val manager = packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return manager.queryIntentActivities(intent, 0).mapNotNull { resolved ->
        val info = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
        InstalledApp(
            label = resolved.loadLabel(manager).toString(),
            packageName = info.packageName,
            system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }.distinctBy { it.packageName }
}

/** Exact package first, then an exact label, then a contains match — in that order of confidence. */
internal fun Context.resolveApp(packageName: String?, name: String?): InstalledApp? {
    val apps = launchableApps()
    packageName?.let { wanted ->
        apps.firstOrNull { it.packageName.equals(wanted, ignoreCase = true) }?.let { return it }
    }
    val wanted = name?.trim()?.lowercase() ?: return null
    return apps.firstOrNull { it.label.lowercase() == wanted }
        ?: apps.firstOrNull { it.label.lowercase().contains(wanted) }
}

internal fun Context.isInstalled(packageName: String): Boolean = runCatching {
    packageManager.getPackageInfo(packageName, 0)
}.isSuccess

/**
 * Starts an activity, or explains why it could not — as a message addressed to the model.
 *
 * Returns null on success. Android refuses activity starts from the background, so a scheduled
 * agent calling this at three in the morning gets a refusal rather than a screen the sleeping user
 * would find in the morning. That is the right outcome; it just has to be said out loud, or the
 * model reports success for something that visibly did not happen.
 */
internal fun Context.startForeground(intent: Intent): String? {
    if (!isAppInForeground()) {
        return "Cirrus is not on screen, and Android does not allow an app in the background to " +
            "open a screen. Tell the user what to open and let them do it."
    }
    return runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    }.getOrElse { "Android refused to open it: ${it.message}" }
}

private fun Context.isAppInForeground(): Boolean {
    val manager = getSystemService<ActivityManager>() ?: return false
    val process = manager.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }
    return process?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}
