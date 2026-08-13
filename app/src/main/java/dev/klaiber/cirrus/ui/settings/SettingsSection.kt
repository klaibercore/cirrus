package dev.klaiber.cirrus.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Settings, grouped by what someone came here to do.
 *
 * The screen used to be one scroll of thirty controls, ordered by when each was written. Finding
 * the context-window slider meant reading past the GitHub token; changing the theme meant scrolling
 * past everything. Grouping costs one tap and buys a screen you can scan, and it leaves somewhere
 * obvious to put the next thing — which is how the old screen got that long in the first place.
 *
 * Memory and Agents are destinations of their own rather than sections: both are content you
 * browse and edit, not switches you flip.
 */
enum class SettingsSection(
    val title: String,
    val summary: String,
    val icon: ImageVector,
) {
    CONNECTION(
        title = "Connection",
        summary = "Host, API key, default model",
        icon = Icons.Outlined.Cloud,
    ),
    GENERATION(
        title = "Chats",
        summary = "Sampling defaults, context window, titles",
        icon = Icons.Outlined.Tune,
    ),
    TOOLS(
        title = "Tools",
        summary = "Web search, notifications, limits",
        icon = Icons.Outlined.Bolt,
    ),
    INTEGRATIONS(
        title = "GitHub and MCP",
        summary = "Repositories, and servers you have attached",
        icon = Icons.Outlined.Terminal,
    ),
    VOICE(
        title = "Voice",
        summary = "Dictation, and reading answers aloud",
        icon = Icons.Outlined.RecordVoiceOver,
    ),
    APPEARANCE(
        title = "Appearance",
        summary = "Theme, colour, markdown",
        icon = Icons.Outlined.Palette,
    ),
    DIAGNOSTICS(
        title = "Diagnostics",
        summary = "Generation stats and the request inspector",
        icon = Icons.Outlined.Insights,
    ),
    DATA(
        title = "Data",
        summary = "Everything stored on this device",
        icon = Icons.Outlined.Storage,
    ),
    ;

    companion object {
        fun fromRoute(value: String?): SettingsSection =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CONNECTION
    }
}

/** The two entries that open a screen of their own rather than a group of switches. */
enum class SettingsDestination(val title: String, val summary: String, val icon: ImageVector) {
    MEMORY(
        title = "Memory",
        summary = "What Cirrus remembers between conversations",
        icon = Icons.Outlined.Psychology,
    ),
    AGENTS(
        title = "Agents",
        summary = "Prompts that run on a schedule",
        icon = Icons.Outlined.Schedule,
    ),
}
