package dev.klaiber.cirrus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsVoice
import dev.klaiber.cirrus.di.AppContainer
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.components.PillStyle
import dev.klaiber.cirrus.ui.components.ScreenTopBar
import dev.klaiber.cirrus.ui.components.readingMeasure
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ElevenLabsModel
import dev.klaiber.cirrus.domain.model.SpeechEngine
import dev.klaiber.cirrus.domain.model.ThemeMode
import dev.klaiber.cirrus.domain.userMessage
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File

/**
 * What to call this build when it names itself.
 *
 * Read from the jar's manifest so a package cannot disagree with itself, with the current release
 * as the fallback for a `:desktop:run` — which has no manifest to read and is where this string is
 * least important anyway.
 */
private val AppVersion: String =
    AppContainer::class.java.`package`?.implementationVersion ?: "1.7.0"

/**
 * Everything configurable, in the sections `SettingSwitch.path` names.
 *
 * The section headings are load-bearing rather than decorative: `describe_settings` hands the model
 * "Settings → Tools → Memory", and a heading that has drifted sends the user looking for a row that
 * is not there. Renaming one here means renaming it in `SettingsCatalog` too.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onClose: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAgents: () -> Unit,
    onOpenMcpServers: () -> Unit,
    onRunSetup: () -> Unit,
    topInset: Dp = 0.dp,
    leadingInset: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository
    // Both are `StateFlow`s the container loads before the first window, so they are read without
    // an initial value — supplying one selects the plain-`Flow` overload and shows a default for
    // the first frame, which here means every switch on the page starting off and snapping to its
    // real position a frame later.
    val settings by repository.settings.collectAsState()
    val models by container.modelRepository.models.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Settings",
            onBack = onClose,
            topInset = topInset,
            leadingInset = leadingInset,
        )

        // Centred on a measure rather than filled. A settings row stretched across a 1180pt window
        // leaves its switch a hand's width from the label it belongs to, which is the one thing a
        // settings list must never do.
        LazyColumn(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Section(SettingsSection.MANAGE) {
                    LinkRow(
                        title = "Memory",
                        summary = "Browse, edit, pin and retire what Cirrus remembers about you.",
                        onClick = onOpenMemory,
                    )
                    LinkRow(
                        title = "Agents",
                        summary = "Prompts that run on a schedule and write their answers into a thread.",
                        onClick = onOpenAgents,
                    )
                    LinkRow(
                        title = "MCP servers",
                        summary = "Attach a Model Context Protocol server and use the tools it offers.",
                        onClick = onOpenMcpServers,
                    )
                    LinkRow(
                        title = "Run setup again",
                        summary = "The first-run wizard: host, key, model, and the optional extras.",
                        onClick = onRunSetup,
                    )
                }
            }

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
                    SwitchRow(
                        title = "Nightly memory pass",
                        summary = "Merges duplicate memories and retires what has been superseded. " +
                            "Nothing is deleted — retiring is archiving, and the memory screen " +
                            "restores anything.",
                        checked = settings.memoryConsolidationEnabled,
                        onChange = { scope.launch { repository.setMemoryConsolidationEnabled(it) } },
                    )
                    NumberRow(
                        title = "Nightly pass hour",
                        summary = "Local hour it runs at. Late enough that nobody is using the model.",
                        value = settings.memoryConsolidationHour,
                        onChange = { scope.launch { repository.setMemoryConsolidationHour(it) } },
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
                SpeechSection(container = container, settings = settings)
            }

            item {
                MusicSection(container = container, settings = settings)
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
                        title = "Suggested openers",
                        summary = "Four starter prompts on an empty chat, written by your own model.",
                        checked = settings.showStarterPrompts,
                        onChange = { scope.launch { repository.setShowStarterPrompts(it) } },
                    )
                    SwitchRow(
                        title = "Developer mode",
                        summary = "Surfaces the exact request sent for each turn.",
                        checked = settings.developerMode,
                        onChange = { scope.launch { repository.setDeveloperMode(it) } },
                    )
                }
            }

            item {
                AboutSection(container = container)
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

/**
 * Which voice reads an answer back, and the key the good one needs.
 *
 * The system voice is offered first and selected by default because it always works without
 * setting anything up — or rather, nearly always: unlike Android, a Linux desktop can genuinely
 * have no engine installed, which is why the row says what it found rather than assuming.
 */
@Composable
private fun SpeechSection(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository

    Section(SettingsSection.SPEECH) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Voice", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            SpeechEngine.entries.forEach { engine ->
                val chosen = engine == settings.speechEngine
                TextButton(onClick = { scope.launch { repository.setSpeechEngine(engine) } }) {
                    Text(
                        text = engine.label,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        color = if (chosen) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = settings.speechEngine.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            title = "Read aloud",
            summary = "Offers a speak button on each answer.",
            checked = settings.readAloudEnabled,
            onChange = { scope.launch { repository.setReadAloudEnabled(it) } },
        )
        SecretRow(
            title = "ElevenLabs API key",
            summary = "Needed only for the ElevenLabs voice. Without one, Cirrus falls back to " +
                "this computer's own engine rather than failing.",
            isSet = settings.hasElevenLabsKey,
            onSave = { scope.launch { repository.setElevenLabsKey(it) } },
            onClear = { scope.launch { repository.clearElevenLabsKey() } },
        )
        // The picker the Android build has had all along. `ElevenLabsClient.voices()` came across
        // with the rest of the client and then had nothing calling it, which left the desktop
        // build able to synthesise in exactly one voice — the account default — with no way to say
        // so and no way to change it.
        VoiceRow(container = container, settings = settings)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("ElevenLabs model", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = ElevenLabsModel.fromId(settings.elevenLabsModelId).description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var open by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { open = true }) {
                    Text(ElevenLabsModel.fromId(settings.elevenLabsModelId).label)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    ElevenLabsModel.entries.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.label) },
                            onClick = {
                                scope.launch { repository.setElevenLabsModel(model) }
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Spotify: a client ID, then a sign-in, then the switch.
 *
 * In that order because each step is useless without the one before it, and a switch that can be
 * turned on before there is an account behind it produces exactly the "on, but not set up yet"
 * state the catalogue had to grow a vocabulary for.
 */
@Composable
private fun MusicSection(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository

    var clientId by remember(settings.spotifyClientId) { mutableStateOf(settings.spotifyClientId) }
    var signingIn by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Section(SettingsSection.MUSIC) {
        Text(
            text = "Cirrus ships no Spotify client ID — an app you can unzip cannot keep a secret. " +
                "Create one at developer.spotify.com and add " +
                dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials.REDIRECT_URI +
                " as a redirect URI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = clientId.trim() != settings.spotifyClientId,
                onClick = { scope.launch { repository.setSpotifyClientId(clientId) } },
            ) {
                Text("Save client ID")
            }
            if (settings.hasSpotifyAccount) {
                TextButton(onClick = { scope.launch { container.spotifySession.signOut() } }) {
                    Text("Sign out")
                }
            } else {
                TextButton(
                    enabled = settings.spotifyClientId.isNotBlank() && !signingIn,
                    onClick = {
                        scope.launch {
                            signingIn = true
                            result = null
                            result = container.spotifySession.signIn().fold(
                                onSuccess = { name -> "Signed in as $name." },
                                onFailure = { error -> error.userMessage() },
                            )
                            signingIn = false
                        }
                    },
                ) {
                    Text("Connect Spotify")
                }
            }
            if (signingIn) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.height(16.dp).width(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Finish the sign-in in your browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        (result ?: settings.spotifyAccountName.takeIf { it.isNotBlank() }?.let { name ->
            if (settings.spotifyPremium) "Signed in as $name (Premium)." else "Signed in as $name."
        })?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwitchRow(
            title = "Spotify",
            summary = "Searching Spotify, your playlists and saved music, what is playing, and " +
                "playback control. Playback needs Premium; Spotify answers 403 without it.",
            checked = settings.spotifyEnabled,
            onChange = { scope.launch { repository.setSpotifyEnabled(it) } },
        )
    }
}

@Composable
private fun Section(section: SettingsSection, content: @Composable () -> Unit) {
    // The measure is applied once, here, rather than at each of the dozen call sites. Every
    // section is a column of label-and-control rows and every one of them wants the same width.
    Column(Modifier.readingMeasure().padding(bottom = 20.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

/** A row that goes somewhere, rather than changing something here. */
@Composable
private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
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
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * Which of the account's voices reads an answer back.
 *
 * The list is fetched on demand rather than when the section appears: it is a network call against
 * somebody's paid account, and most openings of this page are on the way to something else. Until
 * it has been asked for, the row says "Load voices" and Cirrus uses whatever the account's default
 * is — which is a working state, not a broken one, and worth saying so.
 */
@Composable
private fun VoiceRow(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<ElevenLabsVoice>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Voice", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = settings.elevenLabsVoiceName.ifBlank { "The account's default voice" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(
                enabled = settings.hasElevenLabsKey && !loading,
                onClick = {
                    if (voices.isNotEmpty()) {
                        expanded = true
                        return@TextButton
                    }
                    scope.launch {
                        loading = true
                        error = null
                        runCatching { container.elevenLabsClient.voices() }
                            .onSuccess {
                                voices = it
                                expanded = it.isNotEmpty()
                                if (it.isEmpty()) error = "That account has no voices on it."
                            }
                            .onFailure { error = it.userMessage() }
                        loading = false
                    }
                },
            ) {
                Text(if (voices.isEmpty()) "Load voices" else "Change")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(voice.name)
                                voice.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            scope.launch {
                                container.settingsRepository.setElevenLabsVoice(voice.id, voice.name)
                            }
                            expanded = false
                        },
                    )
                }
            }
        }
        if (loading) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(Modifier.height(16.dp).width(16.dp))
        }
    }
    error?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * What this build is, where it keeps its things, and the one irreversible button.
 *
 * The folder is named rather than merely alluded to, and there is a button that opens it, because
 * this is the build with no Keystore and no Room database — "on this computer only" is a claim the
 * user should be able to go and check. It is also the answer to backing Cirrus up, which on
 * Android is the system's problem and here is nobody's until somebody says where to copy from.
 */
@Composable
private fun AboutSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }

    Section(SettingsSection.ABOUT) {
        Text(
            text = "Cirrus $AppVersion for the desktop.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Conversations, memories, agents and keys are files in ${container.dataDir.path}. " +
                "Nothing is synced anywhere, and copying that folder is what backing Cirrus up means.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                label = "Open data folder",
                onClick = { revealInFileManager(container.dataDir) },
                style = PillStyle.Secondary,
            )
            PillButton(
                label = "Delete all conversations",
                onClick = { confirming = true },
                style = PillStyle.Secondary,
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete every conversation?") },
            text = {
                Text(
                    "Every thread and every message goes, including the ones agents wrote. " +
                        "Memories, agents and settings are left alone. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { container.conversationRepository.deleteAllConversations() }
                        confirming = false
                    },
                ) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Shows a folder in whatever this desktop calls its file manager.
 *
 * `Desktop.open` on a directory is the portable way in, and the two failures worth naming are both
 * "there is no desktop here" — a headless session, or a Linux box with no `xdg-open`. Neither is
 * worth an error dialog over a convenience button, so a failure simply does nothing rather than
 * interrupting somebody who can navigate to the path printed directly above it.
 */
private fun revealInFileManager(directory: File) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(directory)
        }
    }
}
