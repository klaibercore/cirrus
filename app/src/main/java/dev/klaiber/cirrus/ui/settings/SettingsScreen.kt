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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsVoice
import dev.klaiber.cirrus.domain.model.ElevenLabsModel
import dev.klaiber.cirrus.domain.model.SpeechEngine
import dev.klaiber.cirrus.domain.model.ThemeMode
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.klaiber.cirrus.ui.components.SectionLabel
import dev.klaiber.cirrus.ui.components.HelpBadge
import dev.klaiber.cirrus.ui.components.HelpTooltip
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape

/**
 * The settings hub.
 *
 * Eight groups and two destinations, instead of one scroll of thirty controls. The old screen was
 * ordered by when each setting was written, so finding the context-window slider meant reading past
 * the GitHub token. Grouping costs one tap and buys a screen you can scan — and it leaves an
 * obvious place to put the next thing, which is how the old one got that long.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAgents: () -> Unit,
    onRunSetup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 40.dp),
        ) {
            ConnectionSummary(
                hasKey = state.settings.hasApiKey,
                host = state.settings.baseUrl,
                model = state.settings.defaultModel,
                onClick = { onOpenSection(SettingsSection.CONNECTION) },
            )

            SectionLabel("What Cirrus knows")
            HubCard {
                HubRow(
                    icon = SettingsDestination.MEMORY.icon,
                    title = SettingsDestination.MEMORY.title,
                    summary = if (state.memoryCount > 0) {
                        "${state.memoryCount} remembered"
                    } else {
                        SettingsDestination.MEMORY.summary
                    },
                    onClick = onOpenMemory,
                )
                HubDivider()
                HubRow(
                    icon = SettingsDestination.AGENTS.icon,
                    title = SettingsDestination.AGENTS.title,
                    summary = if (state.agentCount > 0) {
                        "${state.agentCount} scheduled"
                    } else {
                        SettingsDestination.AGENTS.summary
                    },
                    onClick = onOpenAgents,
                )
            }

            SectionLabel("Settings")
            HubCard {
                SettingsSection.entries.forEachIndexed { index, section ->
                    if (index > 0) HubDivider()
                    HubRow(
                        icon = section.icon,
                        title = section.title,
                        summary = section.summary,
                        onClick = { onOpenSection(section) },
                    )
                }
            }

            SectionLabel("Getting set up")
            HubCard {
                // The wizard is where the connection is proved rather than merely typed, which
                // makes it the right answer to "it stopped working" as well as to "I am new here".
                HubRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Run setup again",
                    summary = "Walk through the host, key and model, and test the connection",
                    onClick = onRunSetup,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Cirrus ${state.versionName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The one thing worth showing without a tap: whether Cirrus can reach a model at all. */
@Composable
private fun ConnectionSummary(hasKey: Boolean, host: String, model: String, onClick: () -> Unit) {
    val connected = hasKey || !host.contains("ollama.com")

    // Connected is the ordinary case, so it gets the ordinary treatment: an outlined row like every
    // other. Only the failure is tinted. A green-equivalent "all is well" panel spends the reader's
    // attention on the state that needed none of it.
    OutlinedPanel(
        onClick = onClick,
        shape = LargeContainerShape,
        color = if (connected) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        borderColor = if (connected) {
            MaterialTheme.colorScheme.outlineVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        val onContainer = if (connected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (connected) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.ErrorOutline
                },
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (connected) "Connected" else "No API key yet",
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                )
                Text(
                    text = buildString {
                        append(host.removePrefix("https://").removePrefix("http://"))
                        if (model.isNotBlank()) append(" · ").append(model)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        onContainer
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A group of rows as one bordered object, hairline-separated — the reference site's list idiom. */
@Composable
private fun HubCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun HubDivider() {
    Hairline(startIndent = 56.dp)
}

@Composable
private fun HubRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One group of controls, on its own screen.
 *
 * Every control here is the one that was already there; only where it lives has changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    onOpenMcpServers: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section.title) },
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
            when (section) {
                SettingsSection.CONNECTION -> {

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

            
                }

                SettingsSection.GENERATION -> {

                ModelDropdownRow(
                    selected = state.settings.defaultModel,
                    models = state.models.map { it.name },
                    onSelect = viewModel::setDefaultModel,
                )
                SwitchRow(
                    title = "Web tools by default",
                    subtitle = "Enable web search and page fetch for new conversations",
                    help = "Lets the model run web searches and fetch pages mid-answer. It decides " +
                        "when to call them, and each call is an extra round trip to your host. You " +
                        "can still flip this per conversation from the composer.",
                    checked = state.settings.toolsEnabledByDefault,
                    onCheckedChange = viewModel::setToolsDefault,
                )
                SwitchRow(
                    title = "Auto-title conversations",
                    subtitle = "Name threads from their content, and keep the name current",
                    help = "Cirrus names a new thread from its first exchange, then re-summarises " +
                        "it as the conversation grows — at most once every 30 minutes, so a long " +
                        "session costs a handful of short requests rather than one per turn. " +
                        "Rename a thread yourself and it is never overwritten.",
                    checked = state.settings.autoTitleConversations,
                    onCheckedChange = viewModel::setAutoTitle,
                )
                SwitchRow(
                    title = "Suggested openers",
                    subtitle = "Four things to try on an empty conversation",
                    help = "A blank composer asks a question it does not answer. The suggestions " +
                        "are matched to what you have switched on — nothing offers to read your " +
                        "repositories unless a GitHub token is configured. Turn them off once " +
                        "you know what you want to type.",
                    checked = state.settings.showStarterPrompts,
                    onCheckedChange = viewModel::setShowStarterPrompts,
                )
                SwitchRow(
                    title = "Send on enter",
                    subtitle = "Otherwise enter inserts a newline and you tap send",
                    help = "Turns the keyboard's return key into send. Handy for short back-and-forth " +
                        "chats, awkward when you write multi-line prompts.",
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
                    help = "How much of the thread is replayed with every turn. A smaller number " +
                        "means cheaper, faster requests but a shorter memory: the model literally " +
                        "cannot see what fell outside the window. \"All\" sends everything and lets " +
                        "the model's own context window do the truncating.",
                    value = state.settings.contextMessageLimit.toFloat(),
                    range = 0f..100f,
                    steps = 19,
                    format = { if (it.toInt() == 0) "all" else it.toInt().toString() },
                    onChange = { viewModel.setContextMessageLimit(it.toInt()) },
                )

            
                }

                SettingsSection.INTEGRATIONS -> {

                GitHubTokenField(
                    hasToken = state.settings.hasGitHubToken,
                    onSave = viewModel::saveGitHubToken,
                    onClear = viewModel::clearGitHubToken,
                )
                SwitchRow(
                    title = "GitHub tools",
                    subtitle = "Let the model read your repositories, issues and pull requests",
                    help = "Adds tools the model can call mid-answer: list repositories, search " +
                        "code, read files, and read issues and pull requests — private ones " +
                        "included, as far as your token reaches. Requests go to api.github.com and " +
                        "nowhere else, and your Ollama key is never sent there.",
                    checked = state.settings.gitHubToolsEnabled,
                    onCheckedChange = viewModel::setGitHubToolsEnabled,
                    enabled = state.settings.hasGitHubToken,
                )
                SwitchRow(
                    title = "Allow write actions",
                    subtitle = "Opening issues, commenting, posting reviews, committing files",
                    help = "Off by default, and worth leaving off. Reading is recoverable; opening " +
                        "an issue or approving a pull request is public and decided by a model " +
                        "rather than by you. With this off, the write tools are not even offered, " +
                        "so the model cannot try.",
                    checked = state.settings.gitHubWritesAllowed,
                    onCheckedChange = viewModel::setGitHubWritesAllowed,
                    enabled = state.settings.hasGitHubToken && state.settings.gitHubToolsEnabled,
                )

            

                NavigationRow(
                    title = "MCP servers",
                    subtitle = mcpSubtitle(state.mcpServerCount, state.mcpToolCount),
                    help = "Attach a Model Context Protocol server and its tools become available " +
                        "to the model alongside Cirrus's own. Each server is reached and asked what " +
                        "it offers before it is saved, and its token is only ever sent to it.",
                    onClick = onOpenMcpServers,
                )

            
                }

                SettingsSection.VOICE -> {

                SwitchRow(
                    title = "Voice input",
                    subtitle = "Show a microphone in the composer",
                    help = "Dictate instead of typing. Android's speech recogniser transcribes what " +
                        "you say straight into the message box, where you can edit it before " +
                        "sending. Ollama's chat API carries no audio field, so what reaches the " +
                        "model is the transcript, not the recording.",
                    checked = state.settings.voiceInputEnabled,
                    onCheckedChange = viewModel::setVoiceInputEnabled,
                )
                SwitchRow(
                    title = "Keep dictation on device",
                    subtitle = "Use the offline recogniser when one is installed",
                    help = "Prefers Android's on-device recogniser, so your voice never leaves the " +
                        "phone. If the device has no offline model for your language, Cirrus falls " +
                        "back to the network recogniser. Needs Android 13 or newer.",
                    checked = state.settings.preferOnDeviceRecognition,
                    onCheckedChange = viewModel::setPreferOnDeviceRecognition,
                    enabled = state.settings.voiceInputEnabled,
                )
                SwitchRow(
                    title = "Read answers aloud",
                    subtitle = "Show a speak button under finished replies",
                    help = "Adds a control that reads a reply out. What gets spoken is not the raw " +
                        "markdown: code blocks are announced rather than dictated, links are read as " +
                        "\"link\", tables are read as heading-and-value pairs, and maths is spoken as " +
                        "words — x squared, not x two.",
                    checked = state.settings.readAloudEnabled,
                    onCheckedChange = viewModel::setReadAloudEnabled,
                )
                if (state.settings.readAloudEnabled) {
                    SpeechEngineSelector(
                        selected = state.settings.speechEngine,
                        onSelect = viewModel::setSpeechEngine,
                    )
                    if (state.settings.speechEngine == SpeechEngine.ELEVENLABS) {
                        ElevenLabsKeyField(
                            hasKey = state.settings.hasElevenLabsKey,
                            onSave = viewModel::saveElevenLabsKey,
                            onClear = viewModel::clearElevenLabsKey,
                        )
                        if (state.settings.hasElevenLabsKey) {
                            VoicePicker(
                                selectedName = state.settings.elevenLabsVoiceName,
                                voices = voices,
                                status = voiceStatus,
                                onLoad = viewModel::loadVoices,
                                onSelect = viewModel::setElevenLabsVoice,
                            )
                            ElevenLabsModelPicker(
                                selected = ElevenLabsModel.fromId(state.settings.elevenLabsModelId),
                                onSelect = viewModel::setElevenLabsModel,
                            )
                        }
                    }
                }

            
                }

                SettingsSection.APPEARANCE -> {

                ThemeSelector(
                    selected = state.settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                SwitchRow(
                    title = "Render markdown",
                    subtitle = "Turn off to read raw model output verbatim",
                    help = "Formats replies: headings, lists, tables, and syntax-highlighted code " +
                        "blocks. Off shows exactly the characters the model produced, asterisks and " +
                        "backticks included — useful when you are debugging a prompt's formatting.",
                    checked = state.settings.renderMarkdown,
                    onCheckedChange = viewModel::setRenderMarkdown,
                )

            
                }

                SettingsSection.DIAGNOSTICS -> {

                SwitchRow(
                    title = "Show generation stats",
                    subtitle = "Tokens per second, token counts and latency under each reply",
                    help = "Adds a line under each reply with output speed, prompt and response " +
                        "token counts, and time to the first token. The numbers come from the " +
                        "server's own timings, so they measure the host, not your connection.",
                    checked = state.settings.showStats,
                    onCheckedChange = viewModel::setShowStats,
                )
                SwitchRow(
                    title = "Developer mode",
                    subtitle = "Capture and display the exact request JSON for every turn",
                    help = "Stores the exact JSON body sent for each turn and shows it under the " +
                        "reply — system prompt, context window, options and tool definitions " +
                        "included. Nothing extra is sent; it only records what already went out.",
                    checked = state.settings.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                )

            
                }

                SettingsSection.TOOLS -> {

                SwitchRow(
                    title = "Shell and everyday tools",
                    subtitle = "The clock, the calendar, this phone's details, and safe commands",
                    help = "Gives the model four things it otherwise has to guess at: what the " +
                        "date and time are, how a month is laid out, what this phone is, and a " +
                        "shell for the small mechanical jobs — counting, sorting, checksums. The " +
                        "shell runs in a scratch folder inside Cirrus's own cache and can reach " +
                        "nothing outside it: absolute paths, \"..\" and command substitution are " +
                        "refused before anything runs, and only a fixed list of programs is " +
                        "allowed at all. Nothing here touches the network, so it is offered " +
                        "whatever the per-conversation tools switch says.",
                    checked = state.settings.shellToolsEnabled,
                    onCheckedChange = viewModel::setShellToolsEnabled,
                )
                SwitchRow(
                    title = "Apps",
                    subtitle = "List what is installed, open an app, offer to install one",
                    help = "Off by default, because this is the one local tool that acts rather " +
                        "than answers — opening an app puts it in front of whatever you were " +
                        "reading. It cannot install anything by itself: the most it can do is " +
                        "open a store page, where Android asks you, as it always does.",
                    checked = state.settings.appControlEnabled,
                    onCheckedChange = viewModel::setAppControlEnabled,
                )
                StepperRow(
                    title = "Search results",
                    subtitle = "How many results web_search returns per call",
                    help = "More results give the model more to work with, but each one is pasted " +
                        "into the conversation and eats context the model could be using to think.",
                    value = state.settings.webSearchMaxResults.toFloat(),
                    range = 1f..10f,
                    steps = 8,
                    format = { it.toInt().toString() },
                    onChange = { viewModel.setWebSearchMaxResults(it.toInt()) },
                )
                StepperRow(
                    title = "Max tool rounds",
                    subtitle = "Upper bound on back-and-forth tool calls in one turn",
                    help = "A model can search, read the results, then search again. This caps how " +
                        "many of those rounds one turn may take before Cirrus stops the loop, so a " +
                        "model that keeps searching forever cannot run up your bill.",
                    value = state.settings.maxToolIterations.toFloat(),
                    range = 1f..20f,
                    steps = 18,
                    format = { it.toInt().toString() },
                    onChange = { viewModel.setMaxToolIterations(it.toInt()) },
                )
                }

                SettingsSection.DATA -> {

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
                }
            }
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

/**
 * Which engine speaks.
 *
 * Both are always offered, even without a key: seeing that ElevenLabs exists is how anyone
 * discovers they can have a voice that does not sound like a satnav. Picking it reveals the key
 * field rather than nagging beforehand.
 */
@Composable
private fun SpeechEngineSelector(selected: SpeechEngine, onSelect: (SpeechEngine) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        LabelWithHelp(
            label = "Voice engine",
            help = "The device engine is free, works offline and is installed already. " +
                "ElevenLabs sounds dramatically better and is worth it for long answers, but " +
                "every sentence spoken costs characters from your account there. Cirrus falls " +
                "back to the device engine whenever the key is missing.",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SpeechEngine.entries.forEachIndexed { index, engine ->
                SegmentedButton(
                    selected = engine == selected,
                    onClick = { onSelect(engine) },
                    shape = SegmentedButtonDefaults.itemShape(index, SpeechEngine.entries.size),
                    label = { Text(engine.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ElevenLabsKeyField(hasKey: Boolean, onSave: (String) -> Unit, onClear: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    Column(Modifier.padding(top = 8.dp)) {
        LabelWithHelp(
            label = "ElevenLabs API key",
            help = "From elevenlabs.io/app/settings/api-keys. Stored on this device only, " +
                "encrypted with the same device-bound key as everything else here, and sent " +
                "only to api.elevenlabs.io — never to Ollama, and your Ollama key is never sent " +
                "to ElevenLabs.",
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(if (hasKey) "Replace key" else "ElevenLabs key") },
            placeholder = { Text("sk_…") },
            singleLine = true,
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
            shape = ContainerShape,
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
            if (hasKey) {
                TextButton(onClick = onClear) { Text("Remove") }
            }
        }
        if (!hasKey) {
            Text(
                text = "Without a key, read-aloud quietly uses the device engine instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun VoicePicker(
    selectedName: String,
    voices: List<ElevenLabsVoice>,
    status: VoiceStatus,
    onLoad: () -> Unit,
    onSelect: (ElevenLabsVoice) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.padding(top = 12.dp)) {
        LabelWithHelp(
            label = "Voice",
            help = "Every voice on your ElevenLabs account, including ones you cloned or made " +
                "yourself. Cirrus uses the default voice until you pick one.",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .clickable {
                    if (voices.isEmpty()) onLoad() else expanded = true
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedName.ifBlank { "Default voice" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (status == VoiceStatus.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (voices.isEmpty()) "Load voices" else "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
                        onSelect(voice)
                        expanded = false
                    },
                )
            }
        }
        (status as? VoiceStatus.Failure)?.let {
            Text(
                text = it.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ElevenLabsModelPicker(
    selected: ElevenLabsModel,
    onSelect: (ElevenLabsModel) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        LabelWithHelp(
            label = "Synthesis model",
            help = "How the audio is made. Flash starts talking soonest, which is what matters " +
                "when you are waiting to hear an answer; the others sound better but keep you " +
                "waiting longer before the first word.",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ElevenLabsModel.entries.forEachIndexed { index, model ->
                SegmentedButton(
                    selected = model == selected,
                    onClick = { onSelect(model) },
                    shape = SegmentedButtonDefaults.itemShape(index, ElevenLabsModel.entries.size),
                    label = { Text(model.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
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
        LabelWithHelp(
            label = "API key",
            help = "Your Ollama API key, needed for the hosted API at ollama.com. It is stored " +
                "only on this device, encrypted with a key that lives in the Android Keystore " +
                "and never leaves it. A local Ollama instance usually needs no key at all.",
        )
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
            shape = ContainerShape,
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
private fun GitHubTokenField(
    hasToken: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    Column {
        LabelWithHelp(
            label = "Personal access token",
            help = "A fine-grained or classic GitHub token. Classic tokens need the `repo` " +
                "scope to reach private repositories; a fine-grained token needs read access " +
                "to Contents, Issues and Pull requests, plus write on those you want the model " +
                "to be able to change. Create one at github.com/settings/tokens. It is stored " +
                "on this device only, encrypted with a device-bound key.",
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(if (hasToken) "Replace token" else "GitHub token") },
            placeholder = { Text("github_pat_… or ghp_…") },
            singleLine = true,
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
                        contentDescription = if (visible) "Hide token" else "Show token",
                    )
                }
            },
            shape = ContainerShape,
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
                Text("Save token")
            }
            if (hasToken) {
                TextButton(onClick = onClear) { Text("Remove") }
            }
        }

        Text(
            text = if (hasToken) {
                "A token is stored, encrypted with a device-bound key."
            } else {
                "Without a token the GitHub tools stay hidden from the model."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun BaseUrlField(baseUrl: String, onSave: (String) -> Unit) {
    var draft by remember(baseUrl) { mutableStateOf(baseUrl) }

    Column {
        LabelWithHelp(
            label = "Host",
            help = "Where every request goes. Use https://ollama.com for the hosted API, or " +
                "http://<address>:11434 for an Ollama instance on your own machine — your " +
                "hardware, your models, no key. A trailing /api is stripped automatically.",
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Host") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            shape = ContainerShape,
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
            HelpBadge(
                title = "Default model",
                text = "The model new conversations start with. Existing threads keep whatever " +
                    "they were created with, so changing this never rewrites your history. Pick " +
                    "per conversation from the model name in the title bar.",
            )
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
    Column {
        LabelWithHelp(
            label = "Theme",
            help = "\"Follow system\" tracks Android's own light/dark setting, including any " +
                "schedule or battery-saver rule you have set. The other two pin Cirrus " +
                "regardless of what the rest of the phone is doing.",
        )
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
}

/** Caption above a field, with the question mark that explains it. */
@Composable
private fun LabelWithHelp(label: String, help: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        HelpBadge(title = label, text = help)
    }
}

/**
 * A setting with its own explanation.
 *
 * [subtitle] says what the switch does in a handful of words; [help] is the paragraph behind the
 * question mark, for the "…but what does that actually change?" question the subtitle cannot
 * answer without turning the list into an essay.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA

    HelpTooltip(title = title, text = help) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
            HelpBadge(title = title, text = help)
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

/** A row that leads somewhere else, rather than changing something in place. */
@Composable
private fun NavigationRow(
    title: String,
    subtitle: String,
    help: String,
    onClick: () -> Unit,
) {
    HelpTooltip(title = title, text = help) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HelpBadge(title = title, text = help)
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Says what attaching servers has actually bought you, which is a tool count, not a server count. */
private fun mcpSubtitle(serverCount: Int, toolCount: Int): String = when {
    serverCount == 0 -> "None attached"
    toolCount == 0 -> "$serverCount attached · no tools available"
    else -> {
        val servers = if (serverCount == 1) "1 server" else "$serverCount servers"
        val tools = if (toolCount == 1) "1 tool" else "$toolCount tools"
        "$servers · $tools offered to the model"
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    help: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    HelpTooltip(title = title, text = help) {
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
                HelpBadge(title = title, text = help)
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
}

/** Matches Material's disabled content opacity without pulling in the full token set. */
private const val DISABLED_ALPHA = 0.38f
