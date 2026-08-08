package dev.klaiber.cirrus.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.ThinkMode

/**
 * Full sampling control for the active conversation.
 *
 * Each knob is opt-in: a disabled row sends nothing at all for that field, so the model's own
 * default applies. That distinction matters because "temperature 0.8" and "unset" are different
 * requests, and silently pinning defaults would change behaviour across models.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParametersSheet(
    params: GenerationParams,
    systemPrompt: String?,
    supportsThinking: Boolean,
    onParamsChange: (GenerationParams) -> Unit,
    onSystemPromptChange: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Parameters",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onParamsChange(GenerationParams.Default) }) {
                    Text("Reset")
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionLabel("Reasoning effort")
            if (!supportsThinking) {
                Text(
                    text = "The selected model is not known to support reasoning. Sending an " +
                        "effort level anyway is harmless but usually ignored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThinkMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = params.thinkMode == mode,
                        onClick = { onParamsChange(params.copy(thinkMode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThinkMode.entries.size),
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("System prompt")
            OutlinedTextField(
                value = systemPrompt.orEmpty(),
                onValueChange = { onSystemPromptChange(it.takeIf { text -> text.isNotBlank() }) },
                placeholder = { Text("Instructions applied to every turn") },
                shape = RoundedCornerShape(14.dp),
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("Sampling")

            OverridableSlider(
                label = "Temperature",
                value = params.temperature,
                fallback = 0.8f,
                range = 0f..2f,
                format = { "%.2f".format(it) },
                onChange = { onParamsChange(params.copy(temperature = it)) },
            )
            OverridableSlider(
                label = "Top P",
                value = params.topP,
                fallback = 0.9f,
                range = 0f..1f,
                format = { "%.2f".format(it) },
                onChange = { onParamsChange(params.copy(topP = it)) },
            )
            OverridableSlider(
                label = "Top K",
                value = params.topK?.toFloat(),
                fallback = 40f,
                range = 0f..200f,
                format = { it.toInt().toString() },
                onChange = { onParamsChange(params.copy(topK = it?.toInt())) },
            )
            OverridableSlider(
                label = "Min P",
                value = params.minP,
                fallback = 0.05f,
                range = 0f..1f,
                format = { "%.3f".format(it) },
                onChange = { onParamsChange(params.copy(minP = it)) },
            )
            OverridableSlider(
                label = "Repeat penalty",
                value = params.repeatPenalty,
                fallback = 1.1f,
                range = 0.5f..2f,
                format = { "%.2f".format(it) },
                onChange = { onParamsChange(params.copy(repeatPenalty = it)) },
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Context and limits")

            NumberField(
                label = "Context window (num_ctx)",
                value = params.numCtx,
                placeholder = "model default",
                onChange = { onParamsChange(params.copy(numCtx = it)) },
            )
            NumberField(
                label = "Max output tokens (num_predict)",
                value = params.numPredict,
                placeholder = "unlimited",
                onChange = { onParamsChange(params.copy(numPredict = it)) },
            )
            NumberField(
                label = "Seed",
                value = params.seed,
                placeholder = "random",
                onChange = { onParamsChange(params.copy(seed = it)) },
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Stop sequences")
            StopSequenceEditor(
                sequences = params.stop,
                onChange = { onParamsChange(params.copy(stop = it)) },
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Structured output")
            OutlinedTextField(
                value = params.responseFormat.orEmpty(),
                onValueChange = {
                    onParamsChange(params.copy(responseFormat = it.takeIf { text -> text.isNotBlank() }))
                },
                placeholder = { Text("\"json\" or a JSON schema object") },
                shape = RoundedCornerShape(14.dp),
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Constrains decoding to valid JSON. Paste a full JSON schema to pin the shape.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Keep alive")
            OutlinedTextField(
                value = params.keepAlive.orEmpty(),
                onValueChange = {
                    onParamsChange(params.copy(keepAlive = it.takeIf { text -> text.isNotBlank() }))
                },
                placeholder = { Text("e.g. 10m, 1h, or 0 to unload immediately") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * A slider that can be switched off entirely.
 *
 * [fallback] is only the starting position when the override is first enabled; it is never sent
 * while the row is disabled.
 */
@Composable
private fun OverridableSlider(
    label: String,
    value: Float?,
    fallback: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float?) -> Unit,
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value?.let(format) ?: "default",
                style = MaterialTheme.typography.labelMedium,
                color = if (value == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Switch(
                checked = value != null,
                onCheckedChange = { enabled -> onChange(if (enabled) fallback else null) },
            )
        }
        Slider(
            value = value ?: fallback,
            onValueChange = { onChange(it) },
            valueRange = range,
            enabled = value != null,
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int?,
    placeholder: String,
    onChange: (Int?) -> Unit,
) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            onChange(text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull())
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopSequenceEditor(sequences: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf("") }

    Column {
        if (sequences.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                sequences.forEach { sequence ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(sequences - sequence) },
                        label = { Text(sequence) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Remove stop sequence $sequence",
                                modifier = Modifier.heightIn(max = 16.dp),
                            )
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Add a stop sequence, then press done") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (draft.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onChange(sequences + draft)
                            draft = ""
                        },
                    ) {
                        Text("Add")
                    }
                }
            },
        )
    }
}
