package dev.klaiber.cirrus.ui.agents

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
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
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.components.PillStyle
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.util.formatRelative
import dev.klaiber.cirrus.ui.util.formatWhen

/**
 * Prompts that run on a clock.
 *
 * Each row is one agent: what it does, when it next does it, and how the last run went. Tapping a
 * finished run opens the conversation it wrote — an ordinary thread, kept out of the drawer until
 * you reply to it, which is what stops a daily agent from burying the conversations you had
 * yourself under a fortnight of its own output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Agent?>(null) }
    var draft by remember { mutableStateOf<AgentDraft?>(null) }
    var pickingTemplate by remember { mutableStateOf(false) }
    var historyFor by remember { mutableStateOf<Agent?>(null) }
    var deleteTarget by remember { mutableStateOf<Agent?>(null) }

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
            ExtendedFloatingActionButton(
                onClick = { pickingTemplate = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New agent") },
            )
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
                                context.startActivity(appNotificationSettings(context.packageName))
                            }
                        },
                        onOpenSettings = {
                            context.startActivity(appNotificationSettings(context.packageName))
                        },
                    )
                }
            }

            if (state.agents.isEmpty()) {
                item {
                    Column {
                        EmptyState(
                            icon = Icons.Outlined.Schedule,
                            title = "No agents yet",
                            body = "An agent is a prompt that runs on its own schedule — a " +
                                "morning briefing, a Friday summary, a nightly check on " +
                                "something you care about. Its answers stay on this screen " +
                                "rather than in your conversation list.",
                            modifier = Modifier.padding(top = 40.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            PillButton(
                                label = "Start from an example",
                                onClick = { pickingTemplate = true },
                            )
                        }
                    }
                }
            }

            items(state.agents, key = { it.id }) { agent ->
                AgentCard(
                    agent = agent,
                    nextRunAt = state.nextRunFor(agent),
                    isRunning = agent.id in state.running,
                    onToggle = { viewModel.setEnabled(agent, it) },
                    onRunNow = { viewModel.runNow(agent) },
                    onEdit = {
                        editing = agent
                        draft = agent.toDraft()
                    },
                    onOpenRun = { agent.lastConversationId?.let(onOpenConversation) },
                    onHistory = {
                        historyFor = agent
                        viewModel.openHistory(agent.id)
                    },
                    onDuplicate = { viewModel.duplicate(agent) },
                    onDelete = { deleteTarget = agent },
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (pickingTemplate) {
        AgentTemplateSheet(
            templates = state.templates,
            onPick = { template ->
                pickingTemplate = false
                editing = null
                draft = template.toDraft()
            },
            onStartBlank = {
                pickingTemplate = false
                editing = null
                draft = AgentDraft()
            },
            onDismiss = { pickingTemplate = false },
        )
    }

    draft?.let { current ->
        val target = editing
        AgentEditorSheet(
            initial = current,
            isNew = target == null,
            models = state.models,
            defaultModel = state.defaultModel,
            onSave = { saved ->
                if (target == null) {
                    viewModel.create(
                        name = saved.name,
                        prompt = saved.prompt,
                        model = saved.model,
                        minuteOfDay = saved.minuteOfDay,
                        days = saved.days,
                        toolsEnabled = saved.toolsEnabled,
                        notifyOnFinish = saved.notifyOnFinish,
                        keepRuns = saved.keepRuns,
                    )
                } else {
                    viewModel.update(target.applying(saved))
                }
                draft = null
                editing = null
            },
            onDelete = if (target == null) {
                null
            } else {
                {
                    deleteTarget = target
                    draft = null
                    editing = null
                }
            },
            onDismiss = {
                draft = null
                editing = null
            },
        )
    }

    historyFor?.let { agent ->
        AgentHistorySheet(
            agentName = agent.name,
            runs = history,
            onOpenRun = { id ->
                historyFor = null
                viewModel.openHistory(null)
                onOpenConversation(id)
            },
            onDismiss = {
                historyFor = null
                viewModel.openHistory(null)
            },
        )
    }

    deleteTarget?.let { agent ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${agent.name}\"?") },
            text = {
                Text(
                    "The schedule, its run history and the threads it wrote will be removed. " +
                        "Anything you replied to has already become an ordinary conversation and " +
                        "stays where it is.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(agent)
                        deleteTarget = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private fun appNotificationSettings(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

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
    nextRunAt: Long?,
    isRunning: Boolean,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onOpenRun: () -> Unit,
    onHistory: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

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

            // What someone came to this screen to check. "07:30 · weekdays" is the rule; this is
            // the consequence of the rule, which is the part you can be surprised by.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                if (isRunning) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Running now",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = nextRunAt?.let { "Next ${formatWhen(it)}" } ?: "Not scheduled",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PillButton(
                    label = "Run now",
                    onClick = onRunNow,
                    style = PillStyle.Ghost,
                    icon = Icons.Outlined.PlayArrow,
                    enabled = !isRunning,
                )
                TextButton(onClick = onEdit) { Text("Edit") }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "More actions for ${agent.name}",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Run history") },
                            leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onHistory()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}
