package dev.klaiber.cirrus.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.domain.model.ToolInvocation
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.markdown.MonospaceBlock
import java.util.Locale

/**
 * Everything the model did before it answered, as one thing.
 *
 * A turn that reads three files and searches twice used to put five separate outlined cards between
 * the question and the answer, each as prominent as the reply itself. That is the wrong weight: the
 * tool calls are provenance, and provenance belongs behind one line you can open, not in front of
 * the thing you asked for. So a turn with more than one call gets a single panel — "5 steps", the
 * tools it used, how long they took — and the calls themselves live inside it.
 *
 * A lone call is left as its own row, deliberately. Wrapping one call in a group header means
 * reading its name twice, and one card between question and answer was never the problem.
 *
 * Open while it runs, shut when it lands, which is [ThinkingSection]'s rule and for the same reason:
 * there has to be something to watch while nothing is being said, and nothing to step over once
 * there is an answer to read. The `remember` key does the closing — when the last call completes the
 * key changes, and the state resets to collapsed.
 */
@Composable
fun ToolActivity(invocations: List<ToolInvocation>, modifier: Modifier = Modifier) {
    if (invocations.isEmpty()) return

    if (invocations.size == 1) {
        OutlinedPanel(modifier = modifier.fillMaxWidth()) {
            ToolRow(invocations.first())
        }
        return
    }

    val running = invocations.any { !it.isComplete }
    var expanded by remember(running) { mutableStateOf(running) }

    OutlinedPanel(modifier = modifier.fillMaxWidth()) {
        Column {
            GroupHeader(
                invocations = invocations,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            AnimatedVisibility(visible = expanded) {
                Column {
                    invocations.forEach { invocation ->
                        // A rule rather than a nested card: a bordered panel inside a bordered
                        // panel is two edges saying the same thing, and the reference design gets
                        // its separation from hairlines everywhere else too.
                        Hairline()
                        ToolRow(invocation)
                    }
                }
            }
        }
    }
}

/** The one line the group collapses to. */
@Composable
private fun GroupHeader(
    invocations: List<ToolInvocation>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val failures = invocations.count { it.errorMessage != null }
    val running = invocations.count { !it.isComplete }
    val summary = remember(invocations) { summarizeTools(invocations.map { it.name }) }
    val total = remember(invocations) { invocations.mapNotNull { it.durationMs }.sum() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    running > 0 -> "Working — ${invocations.size} steps"
                    failures > 0 -> "${invocations.size} steps, $failures failed"
                    else -> "${invocations.size} steps"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            // A failure the group is hiding has to be visible on the line that hides it.
            failures > 0 -> Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "A tool failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )

            running > 0 -> PulsingDot()

            total > 0 -> Text(
                text = formatDuration(total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Hide steps" else "Show steps",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One call: its name and arguments on a line, the whole exchange behind a tap.
 *
 * Unboxed, so the same row works on its own inside a panel and stacked inside a group.
 */
@Composable
private fun ToolRow(invocation: ToolInvocation) {
    var expanded by remember { mutableStateOf(false) }
    val summary = remember(invocation.argumentsJson) { summarizeArguments(invocation.argumentsJson) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = toolIcon(invocation.name),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = invocation.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                invocation.errorMessage != null -> Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = "Tool failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )

                !invocation.isComplete -> PulsingDot()

                else -> invocation.durationMs?.let { duration ->
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Text(
                    text = "Arguments",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                MonospaceBlock(invocation.argumentsJson, Modifier.fillMaxWidth())
                invocation.resultJson?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Result",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    MonospaceBlock(result.take(MAX_TOOL_RESULT_PREVIEW), Modifier.fillMaxWidth())
                }
                invocation.errorMessage?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Which tools ran, said the way a person would.
 *
 * Names in the order they were first called, repeats folded into a count, and a cap on how many are
 * named — the line has one line's worth of room, and "and 3 more" is more use than a list ellipsed
 * mid-word. Pure, and tested: this is the only part of the group a reader is guaranteed to see.
 */
internal fun summarizeTools(names: List<String>, limit: Int = MAX_NAMED_TOOLS): String {
    if (names.isEmpty()) return ""

    val counted = LinkedHashMap<String, Int>()
    names.forEach { name -> counted[name] = (counted[name] ?: 0) + 1 }

    val shown = counted.entries.take(limit).joinToString(" · ") { (name, count) ->
        val label = name.replace('_', ' ')
        if (count > 1) "$label ×$count" else label
    }
    val hidden = counted.size - minOf(counted.size, limit)
    return if (hidden > 0) "$shown · and $hidden more" else shown
}

/** Milliseconds while they are still readable as milliseconds, seconds after that. */
internal fun formatDuration(millis: Long): String = if (millis < 1_000) {
    "$millis ms"
} else {
    // Explicitly English, like every other formatted number in the app: a decimal comma here would
    // be the only one on the screen.
    String.format(Locale.ENGLISH, "%.1f s", millis / 1000.0)
}

/**
 * A glyph per family of tool.
 *
 * Not decoration: a collapsed group opens onto a stack of rows that all look alike, and the icon is
 * what lets someone find the shell command among four web searches without reading any of them.
 * Matched on the name because that is all a row has — an MCP tool is whatever its server called it.
 */
internal fun toolIconName(name: String): String = when {
    // Ordered, and the order matters: "search" appears in half of these families, so the specific
    // ones have to be asked first or spotify_search comes out as a web search.
    name == "run_command" || name.contains("workspace") || name == "system_info" -> "shell"
    name == "get_datetime" || name == "show_calendar" -> "time"
    name.contains("memory") || name.contains("remember") || name.contains("recall") -> "memory"
    name.contains("spotify") || name.contains("music") || name.contains("playlist") ||
        name.contains("playback") -> "music"
    name.startsWith("web_") || name.contains("search") -> "web"
    else -> "tool"
}

private fun toolIcon(name: String): ImageVector = when (toolIconName(name)) {
    "web" -> Icons.Outlined.Search
    "shell" -> Icons.Outlined.Terminal
    "time" -> Icons.Outlined.Schedule
    "memory" -> Icons.Outlined.Bookmark
    "music" -> Icons.Outlined.MusicNote
    // GitHub and MCP both land here, and both are somebody else's code being called.
    else -> Icons.Outlined.Code
}

/** Extracts a one-line hint from a tool's argument JSON without a full parse. */
internal fun summarizeArguments(argumentsJson: String): String =
    argumentsJson
        .removePrefix("{")
        .removeSuffix("}")
        .replace("\"", "")
        .replace(":", ": ")
        .take(120)

private const val MAX_TOOL_RESULT_PREVIEW = 4_000

private const val MAX_NAMED_TOOLS = 3
