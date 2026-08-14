package dev.klaiber.cirrus.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.StarterPrompt
import dev.klaiber.cirrus.ui.SharedPayload
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.components.PillStyle
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.theme.Pill
import dev.klaiber.cirrus.ui.chat.components.Composer
import dev.klaiber.cirrus.ui.chat.components.MessageActionsSheet
import dev.klaiber.cirrus.ui.chat.components.MessageItem
import dev.klaiber.cirrus.ui.chat.components.ModelPickerSheet
import dev.klaiber.cirrus.ui.chat.components.ParametersSheet
import dev.klaiber.cirrus.ui.chat.components.SpeechButtonState
import dev.klaiber.cirrus.ui.util.rememberClipboard
import dev.klaiber.cirrus.ui.voice.rememberVoiceInput
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sharedPayload: SharedPayload = SharedPayload(),
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshingModels by viewModel.isRefreshingModels.collectAsStateWithLifecycle()
    val isLoadingModelDetails by viewModel.isLoadingModelDetails.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = rememberClipboard()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showModelPicker by remember { mutableStateOf(false) }
    var showParameters by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var actionTargetId by remember { mutableStateOf<String?>(null) }
    var copyNotice by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::attach) }

    val voice = rememberVoiceInput(
        preferOnDevice = state.settings.preferOnDeviceRecognition,
        onPartial = viewModel::onVoicePartial,
        onFinal = viewModel::onVoiceFinal,
        onFailure = viewModel::showError,
    )
    // Granting the permission is itself the "yes, start listening" answer, so start right away.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voice.start()
        } else {
            viewModel.showError("Cirrus needs microphone access to dictate.")
        }
    }

    // Content shared in from another app prefills the composer exactly once.
    LaunchedEffect(sharedPayload) {
        if (!sharedPayload.isEmpty) {
            sharedPayload.text?.takeIf { it.isNotBlank() }?.let(viewModel::onInputChange)
            sharedPayload.imageUri?.let(viewModel::attach)
        }
    }

    LaunchedEffect(copyNotice) {
        copyNotice?.let {
            snackbarHostState.showSnackbar(it)
            copyNotice = null
        }
    }

    // The notification is how a reply that outlives the screen stays visible and stoppable, so
    // ask for it at the first generation rather than on a launch nobody has invested in yet.
    // Refusing it costs the notification, not the reply: the service runs either way.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Nothing to recover: the generation is already running. */ }
    LaunchedEffect(state.isGenerating) {
        if (state.isGenerating && needsNotificationPermission(context)) {
            notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.NavigateToConversation -> onNavigateToConversation(event.conversationId)
                is ChatEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
                is ChatEvent.ShareText -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, event.subject)
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share conversation"))
                }
            }
        }
    }

    // Follow the tail of the transcript as tokens arrive — but only while the reader is still
    // there. Scrolling up mid-answer is how you re-read what was just said, and a transcript that
    // drags you back to the bottom on every token makes that impossible.
    val isPinnedToBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }
    val lastMessage = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, lastMessage?.content?.length, lastMessage?.thinking?.length) {
        if (isPinnedToBottom && state.messages.isNotEmpty()) {
            // Not animate*: this restarts on every token, and a cancelled animation per token is
            // both wasted work and visibly jittery.
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    // Searching moves the transcript; streaming must not fight it, which is why the follow-the-tail
    // effect above stands down whenever the reader has scrolled away from the bottom.
    val visibleIds = state.messages
        .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
        .map { it.id }
    LaunchedEffect(state.search.currentId, state.search.matchIds) {
        val target = state.search.currentId ?: return@LaunchedEffect
        val index = visibleIds.indexOf(target)
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.search.isActive) {
                FindBar(
                    search = state.search,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onPrevious = viewModel::previousMatch,
                    onNext = viewModel::nextMatch,
                    onClose = viewModel::closeSearch,
                )
            } else {
            Column {
            TopAppBar(
                title = {
                    // One tap target covering both lines, clipped so the ripple has an edge to
                    // stop at. Without the clip the press state bleeds the full width of the
                    // title slot, which on a short conversation name looks like a bug.
                    Column(
                        modifier = Modifier
                            .clip(ContainerShape)
                            .clickable { showModelPicker = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.model.ifBlank { "Choose a model" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = "Change model",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Open conversations")
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Outlined.Add, contentDescription = "New chat")
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Find in conversation") },
                                enabled = state.messages.isNotEmpty(),
                                onClick = {
                                    showOverflow = false
                                    viewModel.openSearch()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Parameters") },
                                onClick = {
                                    showOverflow = false
                                    showParameters = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share conversation") },
                                enabled = state.conversation != null,
                                onClick = {
                                    showOverflow = false
                                    viewModel.shareConversation()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showOverflow = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            // The header is separated from the page by a rule, not by a shadow or a tint. It is
            // the single most characteristic move in the reference design, and the reason a
            // scrolled transcript never appears to slide *under* anything.
            Hairline()
            }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                state.errorBanner?.let { error ->
                    ErrorBanner(error, onDismiss = viewModel::dismissError)
                }
                if (state.needsApiKey) {
                    ApiKeyPrompt(onOpenSettings = onOpenSettings)
                } else {
                    Composer(
                        input = state.composerText,
                        attachments = state.pendingAttachments,
                        isGenerating = state.isGenerating,
                        canSend = state.canSend,
                        toolsEnabled = state.toolsEnabled,
                        thinkMode = state.params.thinkMode,
                        sendOnEnter = state.settings.sendOnEnter,
                        voiceAvailable = state.settings.voiceInputEnabled && voice.isAvailable,
                        isListening = voice.isListening,
                        voiceLevel = voice.level,
                        isVoiceOnDevice = voice.isOnDevice,
                        onInputChange = viewModel::onInputChange,
                        onSend = {
                            voice.stop()
                            viewModel.send()
                        },
                        onStop = viewModel::stop,
                        onAttach = { filePicker.launch(arrayOf("image/*", "text/*", "application/json")) },
                        onRemoveAttachment = viewModel::removeAttachment,
                        onToggleTools = { viewModel.setToolsEnabled(!state.toolsEnabled) },
                        onToggleVoice = {
                            when {
                                voice.isListening -> voice.stop()
                                voice.hasPermission() -> voice.start()
                                else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onOpenParameters = { showParameters = true },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.isEmpty) {
                EmptyChatState(
                    hasModel = state.model.isNotBlank(),
                    suggestions = state.starterPrompts,
                    onSuggestionClick = { suggestion ->
                        viewModel.onInputChange(suggestion)
                    },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (state.isAgentRun) {
                        AgentRunBanner(
                            agentName = state.agentName,
                            onKeep = viewModel::keepAgentRun,
                        )
                    }
                    LazyColumn(
                        state = listState,
                        // Weighted rather than filled: the agent banner above it, when there is
                        // one, has to take its height from somewhere.
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(
                            items = state.messages.filter {
                                it.role == Role.USER || it.role == Role.ASSISTANT
                            },
                            key = { it.id },
                        ) { message ->
                            MessageItem(
                                message = message,
                                showStats = state.settings.showStats,
                                renderMarkdown = state.settings.renderMarkdown,
                                developerMode = state.settings.developerMode,
                                highlight = state.search.highlight,
                                speech = when {
                                    !state.settings.readAloudEnabled -> SpeechButtonState.HIDDEN
                                    speaking?.messageId != message.id -> SpeechButtonState.IDLE
                                    speaking?.isPreparing == true -> SpeechButtonState.PREPARING
                                    else -> SpeechButtonState.SPEAKING
                                },
                                onCopy = { text ->
                                    clipboard.copy(text)
                                    if (!clipboard.showsSystemConfirmation) {
                                        // Android 13+ shows its own confirmation; don't double up.
                                        copyNotice = "Copied to clipboard"
                                    }
                                },
                                onRegenerate = { viewModel.regenerate(message.id) },
                                onBranch = { viewModel.branchFrom(message.id) },
                                onSpeak = { viewModel.readAloud(message.id) },
                                onMore = { actionTargetId = message.id },
                            )
                        }
                    }
                }

                // The way back, now that scrolling up no longer gets overridden.
                AnimatedVisibility(
                    visible = !isPinnedToBottom,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                ) {
                    JumpToLatestButton(
                        isGenerating = state.isGenerating,
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(state.messages.lastIndex)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = state.availableModels,
            selectedModel = state.model,
            isRefreshing = isRefreshingModels,
            isLoadingDetails = isLoadingModelDetails,
            onSelect = viewModel::setModel,
            onRefresh = viewModel::refreshModels,
            onDismiss = { showModelPicker = false },
        )
    }

    if (showParameters) {
        ParametersSheet(
            params = state.params,
            systemPrompt = state.systemPrompt,
            supportsThinking = state.modelInfo?.supportsThinking ?: true,
            onParamsChange = viewModel::setParams,
            onSystemPromptChange = viewModel::setSystemPrompt,
            onDismiss = { showParameters = false },
        )
    }

    actionTargetId?.let { targetId ->
        val target = state.messages.firstOrNull { it.id == targetId }
        if (target == null) {
            actionTargetId = null
        } else {
            MessageActionsSheet(
                message = target,
                onCopy = {
                    clipboard.copy(target.content)
                    actionTargetId = null
                },
                onEdit = { newText ->
                    viewModel.editAndResend(targetId, newText)
                    actionTargetId = null
                },
                onDelete = {
                    viewModel.deleteMessage(targetId)
                    actionTargetId = null
                },
                onBranch = {
                    viewModel.branchFrom(targetId)
                    actionTargetId = null
                },
                onDismiss = { actionTargetId = null },
            )
        }
    }
}

/**
 * The find bar, in place of the title bar.
 *
 * Replacing the bar rather than floating over the transcript keeps every line of the conversation
 * visible while searching, which is the whole point of searching it. The counter reads
 * "3/12" — messages, not occurrences, because that is what the arrows step through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindBar(
    search: ChatSearch,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column {
    TopAppBar(
        title = {
            TextField(
                value = search.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Find in conversation") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close search")
            }
        },
        actions = {
            if (search.query.isNotBlank()) {
                Text(
                    text = search.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPrevious, enabled = search.hasMatches) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = onNext, enabled = search.hasMatches) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next match")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
    Hairline()
    }
}

/**
 * Returns the reader to the tail of the transcript.
 *
 * Says "New response" rather than "Scroll down" while a turn is streaming, because at that moment
 * the reason to go back is that something is arriving, not that the list is long.
 */
@Composable
private fun JumpToLatestButton(isGenerating: Boolean, onClick: () -> Unit) {
    // The one place a solid black pill is the right answer rather than a loud one: this floats over
    // running text, and an outlined pill on a transparent fill would let the transcript show
    // through it. `inverseSurface` is the far end of the neutral ramp, so it separates on contrast
    // alone — which is exactly how the reference design gets depth without a shadow.
    // The clickable overload: `Surface` clips its content to the shape, so a `clickable` on the
    // modifier we hand it would paint a rectangular ripple across the pill's ends.
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = Pill,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isGenerating) "New response" else "Jump to latest",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ApiKeyPrompt(onOpenSettings: () -> Unit) {
    OutlinedPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = LargeContainerShape,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "Connect your Ollama account",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Add an API key from ollama.com/settings/keys to start chatting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            PillButton(label = "Open settings", onClick = onOpenSettings)
        }
    }
}

/**
 * Says whose thread this is.
 *
 * An agent's run looks like an ordinary conversation because it *is* one — that is the whole design
 * — but it is not in the drawer, and its first message is an instruction nobody remembers typing.
 * One line explains both, and offers the one action that changes it: keeping the thread turns it
 * into an ordinary conversation, which replying to it does anyway.
 */
@Composable
private fun AgentRunBanner(agentName: String?, onKeep: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = agentName?.let { "Run of \"$it\" · kept out of your conversations" }
                    ?: "An agent wrote this · kept out of your conversations",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            PillButton(label = "Keep", onClick = onKeep, style = PillStyle.Secondary)
        }
    }
}

/**
 * The first screen of a new thread, and the app's one piece of stage.
 *
 * Everywhere else the design gets out of the way of the transcript, which leaves exactly one moment
 * to say what the app is: a blank conversation. It is built like the reference site's hero —
 * the mark, one large line set in the rounded display face, one muted line under it, and openings
 * offered as bordered rows rather than as tinted chips. The rows are hairline-outlined for the same
 * reason the model list is: it lets three of them read as one object instead of three buttons.
 */
@Composable
private fun EmptyChatState(
    hasModel: Boolean,
    suggestions: List<StarterPrompt>,
    onSuggestionClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "What should we dig into?",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (hasModel) {
                "Attach files, enable web search, or tune sampling from the composer."
            } else {
                "Pick a model from the title bar to begin."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        suggestions.forEach { suggestion ->
            OutlinedPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                onClick = { onSuggestionClick(suggestion.prompt) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = suggestion.label,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = suggestion.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Whether the notification prompt is still worth showing.
 *
 * Tracked for the life of the process rather than persisted: Android itself stops showing the
 * dialog after the user has said no twice, so this only needs to stop us asking again while the
 * same session keeps generating.
 */
private var notificationPermissionAsked = false

private fun needsNotificationPermission(context: Context): Boolean {
    if (notificationPermissionAsked) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}
