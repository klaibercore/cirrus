package dev.klaiber.cirrus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.di.AppContainer
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ThemeMode
import dev.klaiber.cirrus.domain.userMessage
import kotlinx.coroutines.launch

/**
 * Everything configurable, in the sections `SettingSwitch.path` names.
 *
 * The section headings are load-bearing rather than decorative: `describe_settings` hands the model
 * "Settings → Tools → Memory", and a heading that has drifted sends the user looking for a row that
 * is not there. Renaming one here means renaming it in `SettingsCatalog` too.
 */
@Composable
fun SettingsScreen(container: AppContainer, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository
    val settings by repository.settings.collectAsState(AppSettings())
    val models by container.modelRepository.models.collectAsState(emptyList())

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to chat")
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                ConnectionSection(container = container, settings = settings)
            }

            item {
                Section(SettingsSection.MODEL) {
                    ModelRow(
                        models = models.map { it.name },
                        selected = settings.defaultModel,
                        onSelect = { scope.launch { repository.setDefaultModel(it) } },
                    )
                    SwitchRow(
                        title = "Auto-title conversations",
                        summary = "Ask the model for a short title after the first exchange.",
                        checked = settings.autoTitleConversations,
                        onChange = { scope.launch { repository.setAutoTitle(it) } },
                    )
                    NumberRow(
                        title = "Context messages",
                        summary = "How many earlier messages to replay. Zero sends the whole thread.",
                        value = settings.contextMessageLimit,
                        onChange = { scope.launch { repository.setContextMessageLimit(it) } },
                    )
                }
            }

            item {
                Section(SettingsSection.TOOLS) {
                    SwitchRow(
                        title = "Tools on by default",
                        summary = "Whether a new conversation starts with web search and GitHub offered.",
                        checked = settings.toolsEnabledByDefault,
                        onChange = { scope.launch { repository.setToolsEnabledByDefault(it) } },
                    )
                    SwitchRow(
                        title = "Memory",
                        summary = "Remembering things about you between conversations, and recalling them.",
                        checked = settings.memoryEnabled,
                        onChange = { scope.launch { repository.setMemoryEnabled(it) } },
                    )
                    SwitchRow(
                        title = "Notifications",
                        summary = "Letting the model put something on the desktop notification tray.",
                        checked = settings.notificationToolEnabled,
                        onChange = { scope.launch { repository.setNotificationToolEnabled(it) } },
                    )
                    SwitchRow(
                        title = "Shell and everyday tools",
                        summary = "The date and time, a calendar month, this computer's details, and " +
                            "safe shell commands in a private scratch folder.",
                        checked = settings.shellToolsEnabled,
                        onChange = { scope.launch { repository.setShellToolsEnabled(it) } },
                    )
                    SwitchRow(
                        title = "Apps",
                        summary = "Listing applications on this computer and opening one.",
                        checked = settings.appControlEnabled,
                        onChange = { scope.launch { repository.setAppControlEnabled(it) } },
                    )
                    SwitchRow(
                        title = "Allow write actions",
                        summary = "Tools that change something outside Cirrus and cannot be undone " +
                            "from inside it — opening a GitHub issue, or committing a file.",
                        checked = settings.writeToolsAllowed,
                        onChange = { scope.launch { repository.setWriteToolsAllowed(it) } },
                    )
                    NumberRow(
                        title = "Web search results",
                        summary = "How many results a search returns. More costs context.",
                        value = settings.webSearchMaxResults,
                        onChange = { scope.launch { repository.setWebSearchMaxResults(it) } },
                    )
                    NumberRow(
                        title = "Tool rounds per turn",
                        summary = "Bounds a model that would otherwise call tools forever.",
                        value = settings.maxToolIterations,
                        onChange = { scope.launch { repository.setMaxToolIterations(it) } },
                    )
                }
            }

            item {
                Section(SettingsSection.GITHUB) {
                    SecretRow(
                        title = "Personal access token",
                        summary = "Kept in Cirrus's own data directory. A token with no scopes still " +
                            "reads public repositories.",
                        isSet = settings.hasGitHubToken,
                        onSave = { scope.launch { repository.setGitHubToken(it) } },
                        onClear = { scope.launch { repository.clearGitHubToken() } },
                    )
                    SwitchRow(
                        title = "GitHub tools",
                        summary = "Reading repositories, code, issues and pull requests.",
                        checked = settings.gitHubToolsEnabled,
                        onChange = { scope.launch { repository.setGitHubToolsEnabled(it) } },
                    )
                }
            }

            item {
                Section(SettingsSection.APPEARANCE) {
                    ThemeRow(
                        selected = settings.themeMode,
                        onSelect = { scope.launch { repository.setThemeMode(it) } },
                    )
                    SwitchRow(
                        title = "Render markdown",
                        summary = "Formatted answers rather than the raw text the model sent.",
                        checked = settings.renderMarkdown,
                        onChange = { scope.launch { repository.setRenderMarkdown(it) } },
                    )
                    SwitchRow(
                        title = "Show generation stats",
                        summary = "Tokens and speed under each answer.",
                        checked = settings.showStats,
                        onChange = { scope.launch { repository.setShowStats(it) } },
                    )
                    SwitchRow(
                        title = "Send on Enter",
                        summary = "Enter sends and Shift+Enter starts a line, rather than the reverse.",
                        checked = settings.sendOnEnter,
                        onChange = { scope.launch { repository.setSendOnEnter(it) } },
                    )
                    SwitchRow(
                        title = "Developer mode",
                        summary = "Surfaces the exact request sent for each turn.",
                        checked = settings.developerMode,
                        onChange = { scope.launch { repository.setDeveloperMode(it) } },
                    )
                }
            }
        }
    }
}

/**
 * The connection, and proof that it works.
 *
 * The host and key are saved *before* the catalogue is fetched, because the credential holder the
 * HTTP layer reads is fed from the same store — testing an unsaved key would test the old one.
 */
@Composable
private fun ConnectionSection(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository

    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Section(SettingsSection.CONNECTION) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; result = null },
            label = { Text("Ollama host") },
            placeholder = { Text("https://ollama.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; result = null },
            label = { Text(if (settings.hasApiKey) "API key (saved)" else "API key") },
            placeholder = { Text("Leave blank for a local host") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !testing,
                onClick = {
                    scope.launch {
                        testing = true
                        result = null
                        repository.setBaseUrl(baseUrl)
                        if (apiKey.isNotBlank()) {
                            repository.setApiKey(apiKey)
                            apiKey = ""
                        }
                        result = container.modelRepository.refresh().fold(
                            onSuccess = { models -> "Connected — ${models.size} models available." },
                            onFailure = { error -> error.userMessage() },
                        )
                        testing = false
                    }
                },
            ) {
                Text("Save and test")
            }
            if (settings.hasApiKey) {
                TextButton(onClick = { scope.launch { repository.clearApiKey() } }) {
                    Text("Clear key")
                }
            }
            if (testing) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.height(16.dp).width(16.dp))
            }
        }
        result?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(section: SettingsSection, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberRow(
    title: String,
    summary: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    // The field holds text rather than the number, so clearing it to type a new one does not
    // momentarily save a zero.
    var text by remember(value) { mutableStateOf(value.toString()) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { entry ->
                text = entry.filter(Char::isDigit).take(3)
                text.toIntOrNull()?.let(onChange)
            },
            singleLine = true,
            modifier = Modifier.width(96.dp),
        )
    }
}

@Composable
private fun SecretRow(
    title: String,
    summary: String,
    isSet: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var entry by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it },
                placeholder = { Text(if (isSet) "Saved — type to replace" else "Not set") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f).widthIn(max = 420.dp),
            )
            TextButton(
                enabled = entry.isNotBlank(),
                onClick = { onSave(entry); entry = "" },
            ) {
                Text("Save")
            }
            if (isSet) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun ModelRow(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Default model", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "What a new conversation starts on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(onClick = { open = true }) {
                Text(selected.ifBlank { "None selected" }, maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No models — test the connection first") },
                        onClick = { open = false },
                    )
                }
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = { onSelect(model); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        ThemeMode.entries.forEach { mode ->
            val chosen = mode == selected
            TextButton(onClick = { onSelect(mode) }) {
                Text(
                    text = mode.label,
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                    color = if (chosen) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
