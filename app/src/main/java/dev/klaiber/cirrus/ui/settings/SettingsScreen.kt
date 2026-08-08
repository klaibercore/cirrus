package dev.klaiber.cirrus.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.domain.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
        ) {
            SectionHeader("Connection")
            ApiKeyField(
                hasKey = state.settings.hasApiKey,
                status = state.connectionStatus,
                onSave = viewModel::saveApiKey,
                onClear = viewModel::clearApiKey,
                onTest = viewModel::testConnection,
            )

            Spacer(Modifier.height(12.dp))
            BaseUrlField(
                baseUrl = state.settings.baseUrl,
                onSave = viewModel::setBaseUrl,
            )

            SectionHeader("Defaults")
            ModelDropdownRow(
                selected = state.settings.defaultModel,
                models = state.models.map { it.name },
                onSelect = viewModel::setDefaultModel,
            )
            SwitchRow(
                title = "Web tools by default",
                subtitle = "Enable web search and page fetch for new conversations",
                checked = state.settings.toolsEnabledByDefault,
                onCheckedChange = viewModel::setToolsDefault,
            )
            SwitchRow(
                title = "Auto-title conversations",
                subtitle = "Ask the model for a short title after the first reply",
                checked = state.settings.autoTitleConversations,
                onCheckedChange = viewModel::setAutoTitle,
            )
            SwitchRow(
                title = "Send on enter",
                subtitle = "Otherwise enter inserts a newline and you tap send",
                checked = state.settings.sendOnEnter,
                onCheckedChange = viewModel::setSendOnEnter,
            )
            StepperRow(
                title = "Context messages",
                subtitle = if (state.settings.contextMessageLimit == 0) {
                    "Sending the full thread every turn"
                } else {
                    "Sending the last ${state.settings.contextMessageLimit} messages"
                },
                value = state.settings.contextMessageLimit.toFloat(),
                range = 0f..100f,
                steps = 19,
                format = { if (it.toInt() == 0) "all" else it.toInt().toString() },
                onChange = { viewModel.setContextMessageLimit(it.toInt()) },
            )

            SectionHeader("Appearance")
            ThemeSelector(
                selected = state.settings.themeMode,
                onSelect = viewModel::setThemeMode,
            )
            SwitchRow(
                title = "Dynamic color",
                subtitle = "Follow the system wallpaper palette on Android 12+",
                checked = state.settings.useDynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
            SwitchRow(
                title = "Render markdown",
                subtitle = "Turn off to read raw model output verbatim",
                checked = state.settings.renderMarkdown,
                onCheckedChange = viewModel::setRenderMarkdown,
            )

            SectionHeader("Diagnostics")
            SwitchRow(
                title = "Show generation stats",
                subtitle = "Tokens per second, token counts and latency under each reply",
                checked = state.settings.showStats,
                onCheckedChange = viewModel::setShowStats,
            )
            SwitchRow(
                title = "Developer mode",
                subtitle = "Capture and display the exact request JSON for every turn",
                checked = state.settings.developerMode,
                onCheckedChange = viewModel::setDeveloperMode,
            )

            SectionHeader("Tools")
            StepperRow(
                title = "Search results",
                subtitle = "How many results web_search returns per call",
                value = state.settings.webSearchMaxResults.toFloat(),
                range = 1f..10f,
                steps = 8,
                format = { it.toInt().toString() },
                onChange = { viewModel.setWebSearchMaxResults(it.toInt()) },
            )
            StepperRow(
                title = "Max tool rounds",
                subtitle = "Upper bound on back-and-forth tool calls in one turn",
                value = state.settings.maxToolIterations.toFloat(),
                range = 1f..20f,
                steps = 18,
                format = { it.toInt().toString() },
                onChange = { viewModel.setMaxToolIterations(it.toInt()) },
            )

            SectionHeader("Data")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDeleteDialog = true }
                    .padding(vertical = 14.dp),
            ) {
                Column {
                    Text(
                        text = "Delete all conversations",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Everything is stored on this device only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Cirrus ${state.versionName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete everything?") },
            text = { Text("All conversations and messages will be permanently removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllConversations()
                        showDeleteDialog = false
                    },
                ) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ApiKeyField(
    hasKey: Boolean,
    status: ConnectionStatus,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(if (hasKey) "Replace API key" else "Ollama API key") },
            placeholder = { Text("ollama api key") },
            singleLine = true,
            // The key is a secret: masked by default, revealable for typo-checking.
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (visible) "Hide key" else "Show key",
                    )
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) {
                Text("Save key")
            }
            OutlinedButton(onClick = onTest, enabled = hasKey) { Text("Test") }
            if (hasKey) {
                TextButton(onClick = onClear) { Text("Remove") }
            }
        }

        Spacer(Modifier.height(6.dp))
        ConnectionStatusRow(hasKey = hasKey, status = status)
    }
}

@Composable
private fun ConnectionStatusRow(hasKey: Boolean, status: ConnectionStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            ConnectionStatus.Idle -> Text(
                text = if (hasKey) {
                    "A key is stored, encrypted with a device-bound key."
                } else {
                    "Create one at ollama.com/settings/keys."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConnectionStatus.Testing -> {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Testing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ConnectionStatus.Success -> {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Connected — reached ${status.model}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is ConnectionStatus.Failure -> {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun BaseUrlField(baseUrl: String, onSave: (String) -> Unit) {
    var draft by remember(baseUrl) { mutableStateOf(baseUrl) }

    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Host") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Point at a local instance (http://10.0.2.2:11434) to use your own hardware.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (draft != baseUrl) {
            Spacer(Modifier.height(6.dp))
            Button(onClick = { onSave(draft) }) { Text("Apply host") }
        }
    }
}

@Composable
private fun ModelDropdownRow(
    selected: String,
    models: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Default model", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = selected.ifBlank { "Not set" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 8.dp)) {
                models.forEach { model ->
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (model == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(model)
                                expanded = false
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                    )
                }
                if (models.isEmpty()) {
                    Text(
                        text = "No models loaded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = format(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}
