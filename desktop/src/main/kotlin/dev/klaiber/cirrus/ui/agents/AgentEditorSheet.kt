package dev.klaiber.cirrus.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.components.CirrusSheet
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentTemplate
import dev.klaiber.cirrus.ui.components.HelpBadge
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.components.PillStyle
import dev.klaiber.cirrus.ui.theme.ContainerShape
import java.time.DayOfWeek

/**
 * Everything an agent is, before it exists.
 *
 * Kept as one value rather than eight parameters because the same shape is produced by three
 * different things — a blank sheet, a template, and an agent being edited — and a seven-argument
 * callback is where the wrong two arguments quietly swap places.
 */
data class AgentDraft(
    val name: String = "",
    val prompt: String = "",
    val model: String? = null,
    val minuteOfDay: Int = DEFAULT_MINUTE,
    val days: Set<DayOfWeek> = Agent.WEEKDAYS,
    val toolsEnabled: Boolean = true,
    val notifyOnFinish: Boolean = true,
    val keepRuns: Int = Agent.DEFAULT_KEEP_RUNS,
) {
    val isValid: Boolean get() = name.isNotBlank() && prompt.isNotBlank() && days.isNotEmpty()
}

fun Agent.toDraft() = AgentDraft(
    name = name,
    prompt = prompt,
    model = model,
    minuteOfDay = minuteOfDay,
    days = days,
    toolsEnabled = toolsEnabled,
    notifyOnFinish = notifyOnFinish,
    keepRuns = keepRuns,
)

fun Agent.applying(draft: AgentDraft) = copy(
    name = draft.name,
    prompt = draft.prompt,
    model = draft.model,
    minuteOfDay = draft.minuteOfDay,
    days = draft.days,
    toolsEnabled = draft.toolsEnabled,
    notifyOnFinish = draft.notifyOnFinish,
    keepRuns = draft.keepRuns,
)

fun AgentTemplate.toDraft() = AgentDraft(
    name = name,
    prompt = prompt,
    minuteOfDay = minuteOfDay,
    days = days,
    toolsEnabled = toolsEnabled,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentEditorSheet(
    initial: AgentDraft,
    isNew: Boolean,
    models: List<String>,
    defaultModel: String,
    onSave: (AgentDraft) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var draft by remember(initial) { mutableStateOf(initial) }

    val timeState = rememberTimePickerState(
        initialHour = initial.minuteOfDay / 60,
        initialMinute = initial.minuteOfDay % 60,
        is24Hour = true,
    )

    CirrusSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                // The sheet is taller than a phone once the time picker is in it.
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (isNew) "New agent" else "Edit agent",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                placeholder = { Text("Morning briefing") },
                singleLine = true,
                shape = ContainerShape,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft.prompt,
                onValueChange = { draft = draft.copy(prompt = it) },
                label = { Text("What should it do?") },
                placeholder = {
                    Text("Search for anything new on Kotlin coroutines and summarise it in five bullets.")
                },
                supportingText = {
                    Text("Nobody can answer a follow-up question at 07:30 — say what format you want.")
                },
                minLines = 3,
                maxLines = 8,
                shape = ContainerShape,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("When", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            // TimeInput, not the dial: the dial is half a phone tall inside a sheet that
            // also has a prompt, a day picker and a model list to get through.
            TimeInput(state = timeState)

            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in draft.days,
                        onClick = {
                            draft = draft.copy(
                                days = if (day in draft.days) draft.days - day else draft.days + day,
                            )
                        },
                        label = {
                            Text(day.name.take(2).lowercase().replaceFirstChar(Char::uppercase))
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayPreset("Weekdays", Agent.WEEKDAYS, draft.days) { draft = draft.copy(days = it) }
                DayPreset("Weekend", Agent.WEEKEND, draft.days) { draft = draft.copy(days = it) }
                DayPreset(
                    label = "Every day",
                    days = DayOfWeek.entries.toSet(),
                    selected = draft.days,
                ) { draft = draft.copy(days = it) }
            }

            Spacer(Modifier.height(16.dp))
            Text("Model", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = draft.model == null,
                    onClick = { draft = draft.copy(model = null) },
                    label = {
                        Text("Default${defaultModel.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}")
                    },
                )
                models.take(MAX_MODEL_CHIPS).forEach { option ->
                    FilterChip(
                        selected = draft.model == option,
                        onClick = { draft = draft.copy(model = option) },
                        label = { Text(option) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Let it use tools",
                subtitle = "Web search, GitHub, memory and anything else switched on",
                checked = draft.toolsEnabled,
                onCheckedChange = { draft = draft.copy(toolsEnabled = it) },
            )
            ToggleRow(
                title = "Notify me when it finishes",
                subtitle = "The first line of the answer appears on the lock screen",
                checked = draft.notifyOnFinish,
                onCheckedChange = { draft = draft.copy(notifyOnFinish = it) },
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep the last", style = MaterialTheme.typography.labelLarge)
                HelpBadge(
                    title = "Kept runs",
                    text = "An agent's answers are threads of their own, kept off the " +
                        "conversation list. Older ones are deleted after each run — unless you " +
                        "have replied to one, which turns it into an ordinary conversation.",
                )
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KEEP_CHOICES.forEach { count ->
                    FilterChip(
                        selected = draft.keepRuns == count,
                        onClick = { draft = draft.copy(keepRuns = count) },
                        label = { Text("$count runs") },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(
                    label = "Save",
                    onClick = {
                        onSave(draft.copy(minuteOfDay = timeState.hour * 60 + timeState.minute))
                    },
                    enabled = draft.isValid,
                )
                PillButton(label = "Cancel", onClick = onDismiss, style = PillStyle.Secondary)
                Spacer(Modifier.weight(1f))
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayPreset(
    label: String,
    days: Set<DayOfWeek>,
    selected: Set<DayOfWeek>,
    onPick: (Set<DayOfWeek>) -> Unit,
) {
    FilterChip(
        selected = selected == days,
        onClick = { onPick(days) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 08:00 — early enough to be waiting for you, late enough that the machine is likely awake. */
internal const val DEFAULT_MINUTE = 8 * 60
private const val MAX_MODEL_CHIPS = 6
private val KEEP_CHOICES = listOf(3, 10, 30)
