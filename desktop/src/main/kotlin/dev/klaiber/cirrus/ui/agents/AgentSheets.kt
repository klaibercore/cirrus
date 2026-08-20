package dev.klaiber.cirrus.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.components.CirrusSheet
import dev.klaiber.cirrus.domain.model.AgentRun
import dev.klaiber.cirrus.domain.model.AgentRunStatus
import dev.klaiber.cirrus.domain.model.AgentRunTrigger
import dev.klaiber.cirrus.domain.model.AgentTemplate
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.util.formatDateTime
import dev.klaiber.cirrus.ui.util.formatDuration

/**
 * Every attempt this agent has made.
 *
 * The card above shows the last run, which answers "did it work this morning" and nothing else. A
 * schedule that has failed silently for a week looks identical from that one line, and this is the
 * screen where it stops looking identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHistorySheet(
    agentName: String,
    runs: List<AgentRun>,
    onOpenRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    CirrusSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(agentName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Run history",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (runs.isEmpty()) {
                Text(
                    text = "It has not run yet. When it does, every attempt is listed here — " +
                        "including the ones that failed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(runs, key = { it.id }) { run ->
                    RunRow(run = run, onOpen = { run.conversationId?.let(onOpenRun) })
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: AgentRun, onOpen: () -> Unit) {
    OutlinedPanel(
        onClick = if (run.conversationId != null) onOpen else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RunStatusIcon(run)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = run.summary?.takeIf { it.isNotBlank() }
                        ?: run.errorMessage
                        ?: if (run.isRunning) "Running now" else "Nothing to report",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(formatDateTime(run.startedAt))
                        if (run.trigger == AgentRunTrigger.MANUAL) append(" · run by hand")
                        run.durationMs?.let { append(" · ").append(formatDuration(it)) }
                        if (run.toolCalls > 0) {
                            append(" · ").append(run.toolCalls)
                            append(if (run.toolCalls == 1) " tool call" else " tool calls")
                        }
                        run.tokens?.let { append(" · ").append(it).append(" tokens") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (run.conversationId == null && !run.isRunning) {
                    Text(
                        text = "The thread it wrote has since been cleaned up.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunStatusIcon(run: AgentRun) {
    when {
        run.isRunning -> CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        run.status == AgentRunStatus.FAILED -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        else -> Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = "Succeeded",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Worked examples, offered before the blank editor is.
 *
 * "A prompt that runs on a schedule" describes the feature accurately and helps nobody start one.
 * Each of these opens the ordinary editor with its fields filled in, so the first thing anyone does
 * with a template is edit it — which is the point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTemplateSheet(
    templates: List<AgentTemplate>,
    onPick: (AgentTemplate) -> Unit,
    onStartBlank: () -> Unit,
    onDismiss: () -> Unit,
) {
    CirrusSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Start from", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Each one opens in the editor — change the wording, the time, or both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            templates.forEach { template ->
                OutlinedPanel(
                    onClick = { onPick(template) },
                    shape = LargeContainerShape,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(template.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = template.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "%02d:%02d".format(
                                    template.minuteOfDay / 60,
                                    template.minuteOfDay % 60,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            OutlinedPanel(
                onClick = onStartBlank,
                shape = LargeContainerShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Start from nothing",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}
