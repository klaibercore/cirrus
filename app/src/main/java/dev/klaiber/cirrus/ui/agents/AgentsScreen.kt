package dev.klaiber.cirrus.ui.agents

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRunStatus
import dev.klaiber.cirrus.ui.components.EmptyState
import dev.klaiber.cirrus.ui.util.formatRelative
import java.time.DayOfWeek
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape

/**
 * Prompts that run on a clock.
 *
 * Each row is one agent: what it does, when it next does it, and how the last run went. Tapping a
 * finished run opens the conversation it wrote, which is the same kind of thread as any other —
 * that is what makes an agent's output something you can actually work with rather than a log line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Agent?>(null) }
    var creating by remember { mutableStateOf(false) }

    // An agent that cannot notify is an agent whose answers nobody reads. The permission is only
    // ever asked for on the chat screen, at the first generation, so someone who never generated —
    // or said no once — would otherwise get silent runs with no explanation.
    val context = LocalContext.current
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Coming back from the system settings screen is the other way this becomes true.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "New agent")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!notificationsAllowed && state.agents.any { it.notifyOnFinish }) {
                item {
                    NotificationsBlockedCard(
                        onFix = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                                        Settings.EXTRA_APP_PACKAGE,
                                        context.packageName,
                                    ),
                                )
                            }
                        },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                                    Settings.EXTRA_APP_PACKAGE,
                                    context.packageName,
                                ),
                            )
                        },
                    )
                }
            }

            if (state.agents.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Schedule,
                        title = "No agents yet",
                        body = "An agent is a prompt that runs on its own schedule — a morning " +
                            "briefing, a Friday summary, a nightly check on something you care " +
                            "about. It writes its answer into a normal conversation and can " +
                            "notify you when it is worth reading.",
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            }

            items(state.agents, key = { it.id }) { agent ->
                AgentCard(
                    agent = agent,
                    onToggle = { viewModel.setEnabled(agent, it) },
                    onRunNow = { viewModel.runNow(agent) },
                    onEdit = { editing = agent },
                    onOpenRun = { agent.lastConversationId?.let(onOpenConversation) },
                )
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (creating) {
        AgentEditorSheet(
            agent = null,
            models = state.models,
            defaultModel = state.defaultModel,
            onSave = { name, prompt, model, minute, days, tools, notify ->
                viewModel.create(name, prompt, model, minute, days, tools, notify)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    editing?.let { agent ->
        AgentEditorSheet(
            agent = agent,
            models = state.models,
            defaultModel = state.defaultModel,
            onSave = { name, prompt, model, minute, days, tools, notify ->
                viewModel.update(
                    agent.copy(
                        name = name,
                        prompt = prompt,
                        model = model,
                        minuteOfDay = minute,
                        days = days,
                        toolsEnabled = tools,
                        notifyOnFinish = notify,
                    ),
                )
                editing = null
            },
            onDelete = {
                viewModel.delete(agent)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * Says plainly that the thing the user switched on cannot happen, and offers the one tap that
 * fixes it. A silent agent with no explanation is the worst version of this feature.
 */
@Composable
private fun NotificationsBlockedCard(onFix: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = LargeContainerShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Notifications are off",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "These agents will still run, and their answers will still be here — but " +
                    "nothing will tell you when they finish.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFix) { Text("Turn on") }
                TextButton(onClick = onOpenSettings) {
                    Text("System settings", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun AgentCard(
    agent: Agent,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onOpenRun: () -> Unit,
) {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = agent.scheduleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = agent.enabled, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = agent.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onEdit),
            )

            agent.lastRunAt?.let { lastRun ->
                Spacer(Modifier.height(10.dp))
                // Clickable overload rather than a clickable modifier: `Surface` clips content to
                // its shape, so a ripple attached outside that clip squares off the corners.
                Surface(
                    onClick = onOpenRun,
                    enabled = agent.lastConversationId != null,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = ContainerShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when (agent.lastStatus) {
                                AgentRunStatus.FAILED -> Icons.Outlined.ErrorOutline
                                else -> Icons.Outlined.CheckCircle
                            },
                            contentDescription = null,
                            tint = when (agent.lastStatus) {
                                AgentRunStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = agent.lastSummary?.takeIf { it.isNotBlank() }
                                    ?: "Ran with nothing to report",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatRelative(lastRun),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) { Text("Edit") }
                IconButton(onClick = onRunNow, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = "Run now",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AgentEditorSheet(
    agent: Agent?,
    models: List<String>,
    defaultModel: String,
    onSave: (String, String, String?, Int, Set<DayOfWeek>, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(agent?.id) { mutableStateOf(agent?.name.orEmpty()) }
    var prompt by remember(agent?.id) { mutableStateOf(agent?.prompt.orEmpty()) }
    var model by remember(agent?.id) { mutableStateOf(agent?.model) }
    var days by remember(agent?.id) { mutableStateOf(agent?.days ?: Agent.WEEKDAYS) }
    var tools by remember(agent?.id) { mutableStateOf(agent?.toolsEnabled ?: true) }
    var notify by remember(agent?.id) { mutableStateOf(agent?.notifyOnFinish ?: true) }

    val timeState = rememberTimePickerState(
        initialHour = (agent?.minuteOfDay ?: DEFAULT_MINUTE) / 60,
        initialMinute = (agent?.minuteOfDay ?: DEFAULT_MINUTE) % 60,
        is24Hour = true,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                // The sheet is taller than a phone once the time picker is in it.
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (agent == null) "New agent" else "Edit agent",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Morning briefing") },
                singleLine = true,
                shape = ContainerShape,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("What should it do?") },
                placeholder = {
                    Text("Search for anything new on Kotlin coroutines and summarise it in five bullets.")
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
                        selected = day in days,
                        onClick = {
                            days = if (day in days) days - day else days + day
                        },
                        label = {
                            Text(day.name.take(2).lowercase().replaceFirstChar(Char::uppercase))
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Model", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = model == null,
                    onClick = { model = null },
                    label = { Text("Default${defaultModel.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}") },
                )
                models.take(MAX_MODEL_CHIPS).forEach { option ->
                    FilterChip(
                        selected = model == option,
                        onClick = { model = option },
                        label = { Text(option) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Let it use tools",
                subtitle = "Web search, GitHub, memory and anything else switched on",
                checked = tools,
                onCheckedChange = { tools = it },
            )
            ToggleRow(
                title = "Notify me when it finishes",
                subtitle = "The first line of the answer appears on the lock screen",
                checked = notify,
                onCheckedChange = { notify = it },
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(
                            name,
                            prompt,
                            model,
                            timeState.hour * 60 + timeState.minute,
                            days,
                            tools,
                            notify,
                        )
                    },
                    enabled = name.isNotBlank() && prompt.isNotBlank() && days.isNotEmpty(),
                ) {
                    Text("Save")
                }
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
private fun ToggleRow(
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

/** 08:00 — early enough to be waiting for you, late enough that the phone has a network. */
private const val DEFAULT_MINUTE = 8 * 60
private const val MAX_MODEL_CHIPS = 6
