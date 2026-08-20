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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.components.CirrusSheet
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.ThinkMode
import dev.klaiber.cirrus.ui.components.HelpBadge
import dev.klaiber.cirrus.ui.components.HelpTooltip
import dev.klaiber.cirrus.ui.theme.ContainerShape

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
    CirrusSheet(
        onDismissRequest = onDismiss,
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

            SectionLabel(
                text = "Reasoning effort",
                help = "How long the model may think before it starts answering. Higher effort " +
                    "usually helps on maths, code and multi-step questions, and costs latency " +
                    "and tokens on everything else. Off skips the thinking phase entirely.",
            )
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
            SectionLabel(
                text = "System prompt",
                help = "Standing instructions prepended to every turn in this conversation — " +
                    "tone, format, persona, things to always or never do. It counts against the " +
                    "context window, so keep it tight.",
            )
            OutlinedTextField(
                value = systemPrompt.orEmpty(),
                onValueChange = { onSystemPromptChange(it.takeIf { text -> text.isNotBlank() }) },
                placeholder = { Text("Instructions applied to every turn") },
                shape = ContainerShape,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel(
                text = "Sampling",
                help = "How the next token is chosen from the model's probability distribution. " +
                    "Every switch here is off by default, which sends nothing at all for that " +
                    "field and lets the model apply its own tuned default — not the same thing " +
                    "as pinning the value you see.",
            )

            OverridableSlider(
                label = "Temperature",
                help = "Flattens or sharpens the probabilities. Low values (0.0–0.3) make the " +
                    "model pick the likeliest token nearly every time: repeatable, literal, best " +
                    "for code and extraction. High values (1.0+) let unlikely tokens through, " +
                    "which reads as creative until it reads as incoherent.",
                value = params.temperature,
                fallback = 0.8f,
                range = 0f..2f,
                format = { "%.2f".format(it) },
                onChange = { onParamsChange(params.copy(temperature = it)) },
            )
            OverridableSlider(
                label = "Top P",
                help = "Nucleus sampling: consider only the most likely tokens whose " +
                    "probabilities add up to this fraction, and ignore the long tail. 0.9 drops " +
                    "the rubbish while leaving room to vary; 1.0 disables the cut entirely.",
                value = params.topP,
                fallback = 0.9f,
                range = 0f..1f,
                format = { "%.2f".format(it) },
                onChange = { onParamsChange(params.copy(topP = it)) },
            )
            OverridableSlider(
                label = "Top K",
                help = "Keeps only the K likeliest tokens at each step, whatever their " +
                    "probabilities. A blunter instrument than Top P — a fixed K is too tight " +
                    "when the model is unsure and too loose when it is confident. 0 disables it.",
                value = params.topK?.toFloat(),
                fallback = 40f,
                range = 0f..200f,
                format = { it.toInt().toString() },
                onChange = { onParamsChange(params.copy(topK = it?.toInt())) },
            )
            OverridableSlider(
                label = "Min P",
                help = "Drops any token less likely than this fraction of the top token's " +
                    "probability. Scales with the model's confidence, so it tends to behave " +
                    "better than Top K at high temperature. Use one cut-off, not all three.",
                value = params.minP,
                fallback = 0.05f,
                range = 0f..1f,
                format = { "%.3f".format(it) },
                onChange = { onParamsChange(params.copy(minP = it)) },
            )
            OverridableSlider(
                label = "Repeat penalty",
                help = "Taxes tokens the model has already used. Above 1.0 discourages loops and " +
                    "verbatim repetition; push it too far and the model starts avoiding words it " +
                    "genuinely needs, which mangles code and lists.",
                value = params.repeatPenalty,
                fallback = 1.1f,
                range = 0.5f..2f,
                format = { "%.2f".format(it) },
                onChange = { onParamsChange(params.copy(repeatPenalty = it)) },
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel(
                text = "Context and limits",
                help = "Hard limits on the request. Leave blank to use whatever the model and " +
                    "host were configured with.",
            )

            NumberField(
                label = "Context window (num_ctx)",
                help = "How many tokens of prompt plus reply the model may hold at once. Larger " +
                    "windows remember more of the thread but cost memory on the host and slow " +
                    "the first token; asking for more than the model supports is an error.",
                value = params.numCtx,
                placeholder = "model default",
                onChange = { onParamsChange(params.copy(numCtx = it)) },
            )
            NumberField(
                label = "Max output tokens (num_predict)",
                help = "Cuts the reply off after this many tokens. Useful as a cost ceiling or " +
                    "to force brevity, but the model does not plan around it — it simply stops " +
                    "mid-sentence when it hits the limit.",
                value = params.numPredict,
                placeholder = "unlimited",
                onChange = { onParamsChange(params.copy(numPredict = it)) },
            )
            NumberField(
                label = "Seed",
                help = "Fixes the random number generator. With the same seed, same prompt and " +
                    "same parameters, the model produces the same reply — which is what you " +
                    "want when comparing two prompts and nothing else.",
                value = params.seed,
                placeholder = "random",
                onChange = { onParamsChange(params.copy(seed = it)) },
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel(
                text = "Stop sequences",
                help = "Strings that end the reply the moment they appear. The sequence itself " +
                    "is not included in the output. Handy for keeping a model inside a template " +
                    "or stopping it before it invents the next turn of the conversation.",
            )
            StopSequenceEditor(
                sequences = params.stop,
                onChange = { onParamsChange(params.copy(stop = it)) },
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel(
                text = "Structured output",
                help = "Constrains decoding so the reply is always valid JSON. Enter \"json\" " +
                    "for any JSON, or paste a full JSON schema to pin the exact shape. The " +
                    "model can no longer emit prose, so ask for the fields you want in the " +
                    "prompt too.",
            )
            OutlinedTextField(
                value = params.responseFormat.orEmpty(),
                onValueChange = {
                    onParamsChange(params.copy(responseFormat = it.takeIf { text -> text.isNotBlank() }))
                },
                placeholder = { Text("\"json\" or a JSON schema object") },
                shape = ContainerShape,
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
            SectionLabel(
                text = "Keep alive",
                help = "How long the host keeps this model loaded in memory after the reply. " +
                    "Longer means the next message starts instantly instead of waiting for a " +
                    "reload; 0 unloads immediately and frees the memory for something else. " +
                    "Only meaningful on a host you run yourself.",
            )
            OutlinedTextField(
                value = params.keepAlive.orEmpty(),
                onValueChange = {
                    onParamsChange(params.copy(keepAlive = it.takeIf { text -> text.isNotBlank() }))
                },
                placeholder = { Text("e.g. 10m, 1h, or 0 to unload immediately") },
                singleLine = true,
                shape = ContainerShape,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, help: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HelpBadge(title = text, text = help)
    }
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
    help: String,
    value: Float?,
    fallback: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float?) -> Unit,
) {
    HelpTooltip(title = label, text = help) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                )
                HelpBadge(title = label, text = help)
                Spacer(Modifier.weight(1f))
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
}

@Composable
private fun NumberField(
    label: String,
    help: String,
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
        trailingIcon = { HelpBadge(title = label, text = help) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = ContainerShape,
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
            shape = ContainerShape,
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
