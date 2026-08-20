package dev.klaiber.cirrus.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import dev.klaiber.cirrus.domain.model.AgentTemplate
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.components.PillStyle
import dev.klaiber.cirrus.ui.components.readingMeasure
import dev.klaiber.cirrus.ui.window.TitleBarHeight
import dev.klaiber.cirrus.ui.window.TrafficLightWidth
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.theme.Pill

private const val KEYS_URL = "https://ollama.com/settings/keys"
private const val SIGN_UP_URL = "https://ollama.com"
private const val DOWNLOAD_URL = "https://ollama.com/download"
private const val GITHUB_TOKEN_URL = "https://github.com/settings/tokens"
private const val ELEVENLABS_URL = "https://elevenlabs.io/app/settings/api-keys"

/**
 * The first five minutes.
 *
 * Cirrus cannot do anything at all until it can reach a model, and nothing on a blank chat screen
 * says how. The wizard's job is not to collect settings — settings can be collected later — but to
 * end on a request that demonstrably worked, so the first thing anybody types has somewhere to go.
 *
 * It can be skipped from any step, and skipping counts as finished. A wizard that reappears until
 * it gets its way is worse than no wizard at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    model: OnboardingModel,
) {
    val state by model.uiState.collectAsState()

    Scaffold(
        topBar = {
            Column {
            // The wizard is the one screen with no sidebar to hold the traffic lights, so it pays
            // the title-bar strip itself and starts the step counter clear of the close button.
            Spacer(Modifier.height(TitleBarHeight))
            TopAppBar(
                title = {
                    Text(
                        text = "Step ${state.stepNumber} of ${state.stepCount}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = TrafficLightWidth),
                    )
                },
                actions = {
                    TextButton(onClick = { model.finish(onFinished) }) {
                        Text(if (state.step == OnboardingStep.DONE) "Close" else "Skip")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            }
        },
        bottomBar = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Hairline()
                Row(
                    modifier = Modifier
                        .readingMeasure(WizardMeasure)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.step != state.visibleSteps.first()) {
                        PillButton(
                            label = "Back",
                            onClick = model::back,
                            style = PillStyle.Secondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    StepDots(current = state.stepNumber, total = state.stepCount)
                    Spacer(Modifier.weight(1f))
                    if (state.step == OnboardingStep.DONE) {
                        PillButton(
                            label = "Start chatting",
                            onClick = { model.finish(onFinished) },
                        )
                    } else {
                        PillButton(
                            label = "Continue",
                            onClick = model::next,
                            icon = Icons.AutoMirrored.Outlined.ArrowForward,
                            enabled = state.canAdvance,
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Centred rather than left-aligned. Android had no choice — a phone *is* the measure — but
        // a wizard set hard against the left edge of a 1180pt window puts a paragraph of forty
        // words next to eight hundred points of nothing, and reads as a page that failed to load.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Column(modifier = Modifier.readingMeasure(WizardMeasure).padding(horizontal = 20.dp)) {
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.HOST -> HostStep(state, model)
                OnboardingStep.KEY -> KeyStep(state, model)
                OnboardingStep.MODEL -> ModelStep(state, model)
                OnboardingStep.EXTRAS -> ExtrasStep(state, model)
                OnboardingStep.DONE -> DoneStep(state, model)
            }
            Spacer(Modifier.height(32.dp))
        }
        }
    }
}

/**
 * The wizard's own measure.
 *
 * Narrower than the transcript's, because every step here is a heading, a paragraph and one or two
 * fields — a form, in other words, and a form set as wide as a transcript makes its labels and its
 * inputs look unrelated.
 */
private val WizardMeasure = 620.dp

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current - 1) 8.dp else 6.dp)
                    .clip(Pill)
                    .background(
                        if (index == current - 1) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

/** A heading and a paragraph, set the same way on every step so the wizard reads as one thing. */
@Composable
private fun StepHeader(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(52.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Cirrus",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "A chat client for Ollama, on your desktop.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))

    FeatureRow(
        icon = Icons.Outlined.Bolt,
        title = "It can do things, not just answer",
        body = "Web search, GitHub and any MCP server you attach — each one switched on by you.",
    )
    FeatureRow(
        icon = Icons.Outlined.Schedule,
        title = "It can run without you",
        body = "Agents are prompts on a schedule. Their answers wait for you, out of the way of " +
            "your own conversations.",
    )
    FeatureRow(
        icon = Icons.Outlined.CheckCircle,
        title = "Your keys stay here",
        // Not "encrypted with a key held by this device", which is what the Android build says and
        // what this one said until somebody read both. There is no Keystore on the desktop to hold
        // such a key, and the key step a few screens along already admits it. A promise made on
        // the welcome screen and withdrawn on step four is worse than never making it.
        body = "Every secret is stored in Cirrus's own folder on this computer, and sent only to " +
            "the service it belongs to.",
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Two questions and you are done.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(modifier = Modifier.padding(bottom = 18.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HostStep(state: OnboardingUiState, model: OnboardingModel) {
    val uriHandler = LocalUriHandler.current

    StepHeader(
        title = "Where are your models?",
        body = "Ollama runs either as a hosted service or on a computer of your own. Both work " +
            "the same way from here, and you can change your mind later in Settings.",
    )

    ChoiceCard(
        selected = state.host == HostChoice.CLOUD,
        icon = Icons.Outlined.Cloud,
        title = "Ollama's hosted API",
        body = "Nothing to install. Needs a free account and an API key from ollama.com.",
        onClick = { model.setHost(HostChoice.CLOUD) },
    )
    Spacer(Modifier.height(10.dp))
    ChoiceCard(
        selected = state.host == HostChoice.LOCAL,
        icon = Icons.Outlined.Computer,
        title = "A computer on my network",
        body = "Ollama running at home. Nothing leaves your network, and usually no key at all.",
        onClick = { model.setHost(HostChoice.LOCAL) },
    )

    if (state.host == HostChoice.LOCAL) {
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = state.localUrl,
            onValueChange = model::setLocalUrl,
            label = { Text("Address") },
            placeholder = { Text(DEFAULT_LOCAL_URL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = ContainerShape,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "If Ollama is running on this computer, the default above is right. For " +
                "Ollama on another machine, use its address on your network and start it with " +
                "OLLAMA_HOST=0.0.0.0 so it accepts connections from other devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LinkButton("Install Ollama") { uriHandler.openUri(DOWNLOAD_URL) }

        Spacer(Modifier.height(16.dp))
        ProbeRow(state = state, onTest = model::testConnection)
    }
}

@Composable
private fun KeyStep(state: OnboardingUiState, model: OnboardingModel) {
    val uriHandler = LocalUriHandler.current
    var visible by remember { mutableStateOf(false) }

    StepHeader(
        title = "Your Ollama key",
        body = "Sign in at ollama.com, create a key, and paste it here. It is stored on this " +
            "computer only, in Cirrus's own data folder. Unlike the Android build there is no " +
            "Keystore to encrypt it with, so anything that can read your files can read the key.",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkButton("Create a key") { uriHandler.openUri(KEYS_URL) }
        LinkButton("ollama.com") { uriHandler.openUri(SIGN_UP_URL) }
    }

    Spacer(Modifier.height(18.dp))
    OutlinedTextField(
        value = state.apiKey,
        onValueChange = model::setApiKey,
        label = { Text(if (state.hasSavedKey) "Replace key" else "API key") },
        placeholder = { Text("paste your key") },
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

    if (state.hasSavedKey && state.apiKey.isBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A key is already saved on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    ProbeRow(state = state, onTest = model::testConnection)
}

/**
 * The one control that turns a form into a setup.
 *
 * Typing a key proves nothing; fetching the catalogue proves everything at once — the address, the
 * key, the network, and whether there is a single model to talk to at the other end.
 */
@Composable
private fun ProbeRow(state: OnboardingUiState, onTest: () -> Unit) {
    Column {
        PillButton(
            label = if (state.probe is ConnectionProbe.Reached) "Test again" else "Test connection",
            onClick = onTest,
            style = PillStyle.Secondary,
            enabled = state.probe !is ConnectionProbe.Trying,
        )
        Spacer(Modifier.height(12.dp))
        when (val probe = state.probe) {
            is ConnectionProbe.Untried -> Unit
            is ConnectionProbe.Trying -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(10.dp))
                Text("Asking the host what it has…", style = MaterialTheme.typography.bodySmall)
            }
            is ConnectionProbe.Reached -> StatusPanel(
                ok = true,
                title = if (probe.modelCount == 0) "Connected, but no models" else "Connected",
                body = when (probe.modelCount) {
                    0 -> "The host answered but has nothing installed. Pull one with " +
                        "\"ollama pull llama3.2\" and test again."
                    1 -> "One model is available."
                    else -> "${probe.modelCount} models are available."
                },
            )
            is ConnectionProbe.Failed -> StatusPanel(
                ok = false,
                title = "Could not reach it",
                body = probe.message,
            )
        }
    }
}

@Composable
private fun StatusPanel(ok: Boolean, title: String, body: String) {
    OutlinedPanel(
        shape = LargeContainerShape,
        color = if (ok) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        borderColor = if (ok) {
            MaterialTheme.colorScheme.outlineVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        val onContainer = if (ok) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = onContainer)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
        }
    }
}

@Composable
private fun ModelStep(state: OnboardingUiState, model: OnboardingModel) {
    StepHeader(
        title = "Pick a default model",
        body = "Every new conversation starts with this one. You can change it per conversation " +
            "from the title bar at any time.",
    )

    if (state.models.isEmpty()) {
        StatusPanel(
            ok = false,
            title = "No models yet",
            body = if (state.isCloud) {
                "Go back a step and test the connection — the list is filled in from the host."
            } else {
                "Nothing is installed on that host. Run \"ollama pull llama3.2\" on the computer " +
                    "running Ollama, then go back and test again."
            },
        )
        return
    }

    state.models.take(MAX_MODEL_ROWS).forEach { candidate ->
        ChoiceCard(
            selected = state.selectedModel == candidate.name,
            icon = null,
            title = candidate.name,
            body = listOfNotNull(candidate.parameterSize, candidate.quantization, candidate.family)
                .joinToString(" · ")
                .ifBlank { "Available on this host" },
            onClick = { model.selectModel(candidate.name) },
        )
        Spacer(Modifier.height(8.dp))
    }

    if (state.models.size > MAX_MODEL_ROWS) {
        Text(
            text = "…and ${state.models.size - MAX_MODEL_ROWS} more, in the model picker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExtrasStep(state: OnboardingUiState, model: OnboardingModel) {
    val uriHandler = LocalUriHandler.current
    var gitHubVisible by remember { mutableStateOf(false) }

    StepHeader(
        title = "Anything else?",
        body = "All optional, and all reachable later from Settings. Skip the lot if you would " +
            "rather just start talking.",
    )

    NotificationsCard()

    Spacer(Modifier.height(16.dp))
    Text("GitHub", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Lets the model read your issues, pull requests and code. Reading only — writing " +
            "stays off until you switch it on yourself.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    if (state.gitHubSaved) {
        SavedRow("A GitHub token is saved.")
    } else {
        OutlinedTextField(
            value = state.gitHubToken,
            onValueChange = model::setGitHubToken,
            label = { Text("Personal access token") },
            placeholder = { Text("github_pat_… or ghp_…") },
            singleLine = true,
            visualTransformation = if (gitHubVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { gitHubVisible = !gitHubVisible }) {
                    Icon(
                        imageVector = if (gitHubVisible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (gitHubVisible) "Hide token" else "Show token",
                    )
                }
            },
            shape = ContainerShape,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                label = "Save token",
                onClick = model::saveGitHubToken,
                enabled = state.gitHubToken.isNotBlank(),
            )
            LinkButton("Create one") { uriHandler.openUri(GITHUB_TOKEN_URL) }
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("Read answers aloud", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "This computer's own voice works with no key at all. An ElevenLabs key buys a better " +
            "one, and is worth skipping unless you already have it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    if (state.elevenLabsSaved) {
        SavedRow("An ElevenLabs key is saved.")
    } else {
        OutlinedTextField(
            value = state.elevenLabsKey,
            onValueChange = model::setElevenLabsKey,
            label = { Text("ElevenLabs key (optional)") },
            placeholder = { Text("sk_…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = ContainerShape,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                label = "Save key",
                onClick = model::saveElevenLabsKey,
                enabled = state.elevenLabsKey.isNotBlank(),
            )
            LinkButton("elevenlabs.io") { uriHandler.openUri(ELEVENLABS_URL) }
        }
    }
}

/**
 * Says what notifications are for, rather than asking for them.
 *
 * Android has a permission to request here, so the wizard requests it before the first surprise.
 * A desktop tray notification needs no grant — it either has a tray to draw in or it does not —
 * so this step is informational and the card says so.
 */
@Composable
private fun NotificationsCard() {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Notifications", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "So a long answer, or a scheduled agent, can tell you it has finished.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoneStep(state: OnboardingUiState, model: OnboardingModel) {
    StepHeader(
        title = "Ready",
        body = if (state.selectedModel.isNotBlank()) {
            "New conversations will use ${state.selectedModel}."
        } else {
            "Pick a model from the title bar whenever you are ready — everything else is set up."
        },
    )

    Text("Start with an agent?", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "A prompt that runs on a schedule and leaves the answer waiting for you. Its " +
            "threads stay on the agents screen, not in your conversation list. Pick one to " +
            "create it, or skip — you can add one any time.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentTemplate.All.filterNot { it.needsGitHub }.take(MAX_STARTER_AGENTS).forEach { template ->
            FilterChip(
                selected = state.starterTemplate == template,
                onClick = { model.chooseStarter(template) },
                label = { Text(template.name) },
            )
        }
    }

    state.starterTemplate?.let { template ->
        Spacer(Modifier.height(14.dp))
        OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = "${template.name} · %02d:%02d".format(
                        template.minuteOfDay / 60,
                        template.minuteOfDay % 60,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = template.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SavedRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    PillButton(
        label = label,
        onClick = onClick,
        style = PillStyle.Secondary,
        icon = Icons.Outlined.OpenInNew,
    )
}

/** One of a small set of mutually exclusive answers, as a card rather than a radio button. */
@Composable
private fun ChoiceCard(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    OutlinedPanel(
        onClick = onClick,
        shape = LargeContainerShape,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        borderColor = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private const val MAX_MODEL_ROWS = 8
private const val MAX_STARTER_AGENTS = 4
