package dev.klaiber.cirrus.ui.chat

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.StarterPrompt
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
import dev.klaiber.cirrus.ui.window.LocalWindowTitle
import dev.klaiber.cirrus.ui.util.FileDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * How wide the transcript is allowed to get.
 *
 * A shade wider than the [dev.klaiber.cirrus.ui.components.ReadingMeasure] the settings pages use,
 * because a transcript is not only prose: a code block or a table set at 780pt starts wrapping
 * lines that were written to fit, and re-wrapped code is harder to read than a long line of it.
 */
private val TranscriptMeasure = 860.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    model: ChatModel,
    sidebarVisible: Boolean = false,
    onToggleSidebar: (() -> Unit)? = null,
    leadingInset: Dp = 0.dp,
    topInset: Dp = 0.dp,
) {
    val state by model.uiState.collectAsState()
    val isRefreshingModels by model.isRefreshingModels.collectAsState()
    val isLoadingModelDetails by model.isLoadingModelDetails.collectAsState()
    val speaking by model.speaking.collectAsState()
    val clipboard = rememberClipboard()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showModelPicker by remember { mutableStateOf(false) }
    var showParameters by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var actionTargetId by remember { mutableStateOf<String?>(null) }
    var copyNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copyNotice) {
        copyNotice?.let {
            snackbarHostState.showSnackbar(it)
            copyNotice = null
        }
    }

    // The window is named after what it is showing. The title is hidden from the bar itself on
    // macOS, but it is still how this window is labelled in Mission Control, in the Window menu
    // and in the app switcher — three places where "Cirrus, Cirrus, Cirrus" is no help at all.
    val windowTitle = LocalWindowTitle.current
    LaunchedEffect(state.title) {
        windowTitle.value = state.title.ifBlank { "Cirrus" }
    }
    DisposableEffect(Unit) {
        onDispose { windowTitle.value = "Cirrus" }
    }

    LaunchedEffect(Unit) {
        model.events.collect { event ->
            when (event) {
                is ChatEvent.NavigateToConversation -> onNavigateToConversation(event.conversationId)
                is ChatEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
                // Android hands the transcript to the share sheet. A desktop has no such thing,
                // so exporting is what sharing becomes: pick a file, write it, say where it went.
                is ChatEvent.ShareText -> {
                    val suggested = event.subject
                        .replace(Regex("""[^A-Za-z0-9 _.-]"""), "")
                        .trim()
                        .ifBlank { "conversation" }
                    val target = withContext(Dispatchers.IO) { FileDialogs.save("$suggested.md") }
                    if (target != null) {
                        val written = withContext(Dispatchers.IO) {
                            runCatching { target.writeText(event.text) }
                        }
                        snackbarHostState.showSnackbar(
                            written.fold(
                                onSuccess = { "Exported to ${target.name}" },
                                onFailure = { "Could not write ${target.name}" },
                            ),
                        )
                    }
                }
            }
        }
    }

    // What the transcript actually renders. Hoisted because the list index is load-bearing: tool
    // and system messages are in `state.messages` and are not rows, so `messages.lastIndex` is not
    // the last row — it is somewhere short of it, and every scroll built on it landed there.
    val visibleMessages = remember(state.messages) {
        state.messages.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
    }

    // Whether the end of the last row is on screen. A few dp of slack, because "exactly at the
    // bottom" is a pixel that a fling settles next to rather than on.
    val bottomSlack = with(LocalDensity.current) { 16.dp.roundToPx() }
    val atBottom by remember(bottomSlack) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + bottomSlack
        }
    }

    // Follow the tail of the transcript as tokens arrive — but only while the reader is still
    // there. Scrolling up mid-answer is how you re-read what was just said, and a transcript that
    // drags you back to the bottom on every token makes that impossible.
    //
    // Following is its own state rather than "are we at the bottom right now?", and that is the
    // fix. A streaming answer moves the bottom away from the reader on every token, so a position
    // test says "the reader has scrolled up" a few times a second while the reader is sitting
    // perfectly still — which is why following used to give up mid-answer and the button flickered
    // in and out. Only a *backward* scroll hands control to the reader, and only arriving back at
    // the end gives it up: content growing underneath does neither.
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.lastScrolledBackward }
            .collect { backward -> if (backward) followTail = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (!scrolling && atBottom) followTail = true }
    }

    val lastMessage = visibleMessages.lastOrNull()
    // Tool cards change the height of a turn without changing a character of its text, so the
    // signature has to count them too — otherwise the transcript stops following for exactly as
    // long as the model spends calling tools, which is the part with nothing else to look at.
    val tailSignature = listOf(
        visibleMessages.size,
        lastMessage?.content?.length,
        lastMessage?.thinking?.length,
        lastMessage?.toolInvocations?.size,
        lastMessage?.toolInvocations?.count { it.isComplete },
        lastMessage?.isStreaming,
    )
    LaunchedEffect(tailSignature) {
        // Sending is itself a decision to be at the bottom — nobody types a message in order to
        // carry on reading something further up — so a turn of your own takes following back.
        // Checked here rather than in an effect of its own so it cannot race the scroll below.
        if (lastMessage?.role == Role.USER) followTail = true

        if (followTail && visibleMessages.isNotEmpty()) {
            // Not animate*: this restarts on every token, and a cancelled animation per token is
            // both wasted work and visibly jittery.
            listState.scrollToTail(visibleMessages.lastIndex)
        }
    }

    // Searching moves the transcript; streaming must not fight it, which is why the follow-the-tail
    // effect above stands down whenever the reader has scrolled away from the bottom.
    LaunchedEffect(state.search.currentId, state.search.matchIds) {
        val target = state.search.currentId ?: return@LaunchedEffect
        val index = visibleMessages.indexOfFirst { it.id == target }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    /**
     * The two bindings that belong to a transcript rather than to the window.
     *
     * They live here because the state they act on does: `openSearch` and `closeSearch` are the
     * chat model's, and hoisting them to the window shell so it could own every shortcut would
     * mean the shell knowing which screen is showing before it could decide what a key means.
     */
    fun onChatShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when {
            event.isMetaPressed && event.key == Key.F && state.messages.isNotEmpty() -> {
                model.openSearch(); true
            }
            // Escape closes the find bar, and nothing else — a stray Escape in the composer must
            // not throw away a half-written message.
            event.key == Key.Escape && state.search.isActive -> {
                model.closeSearch(); true
            }
            else -> false
        }
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent(::onChatShortcut),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.search.isActive) {
                FindBar(
                    search = state.search,
                    onQueryChange = model::onSearchQueryChange,
                    onPrevious = model::previousMatch,
                    onNext = model::nextMatch,
                    onClose = model::closeSearch,
                    topInset = topInset,
                    leadingInset = leadingInset,
                )
            } else {
            Column {
            Spacer(Modifier.height(topInset))
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
                    // With the list resident there is nothing to reveal, so the same button
                    // collapses it instead. Two glyphs for two genuinely different actions: a
                    // hamburger that hides a visible sidebar reads as a bug.
                    IconButton(
                        onClick = onToggleSidebar ?: onOpenDrawer,
                        modifier = Modifier.padding(start = leadingInset),
                    ) {
                        Icon(
                            imageVector = if (sidebarVisible) {
                                Icons.AutoMirrored.Outlined.MenuOpen
                            } else {
                                Icons.Outlined.Menu
                            },
                            contentDescription = if (sidebarVisible) {
                                "Hide conversations"
                            } else {
                                "Show conversations"
                            },
                        )
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
                                    model.openSearch()
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
                                    model.shareConversation()
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
            // No `navigationBarsPadding`/`imePadding` here: those are Android's gesture bar and
            // soft keyboard, and a desktop window has neither to make room for.
            Column {
                state.errorBanner?.let { error ->
                    ErrorBanner(error, onDismiss = model::dismissError)
                }
                if (state.needsApiKey) {
                    ApiKeyPrompt(onOpenSettings = onOpenSettings)
                } else {
                    // On the transcript's measure, and centred with it: a composer running the
                    // full width of the window puts the caret somewhere other than under the
                    // column of text it is replying to.
                    Box(Modifier.fillMaxWidth()) {
                    Composer(
                        input = state.composerText,
                        attachments = state.pendingAttachments,
                        isGenerating = state.isGenerating,
                        canSend = state.canSend,
                        toolsEnabled = state.toolsEnabled,
                        thinkMode = state.params.thinkMode,
                        sendOnEnter = state.settings.sendOnEnter,
                        voiceAvailable = false,
                        isListening = false,
                        voiceLevel = 0f,
                        isVoiceOnDevice = false,
                        onInputChange = model::onInputChange,
                        onSend = model::send,
                        onStop = model::stop,
                        onAttach = {
                            scope.launch {
                                withContext(Dispatchers.IO) { FileDialogs.open() }?.let(model::attach)
                            }
                        },
                        onRemoveAttachment = model::removeAttachment,
                        onToggleTools = { model.setToolsEnabled(!state.toolsEnabled) },
                        // Dictation is Android's `SpeechRecognizer`, which has no desktop
                        // counterpart worth shipping — a bundled model would dwarf the app. The
                        // composer hides the button rather than offering one that does nothing.
                        onToggleVoice = {},
                        onOpenParameters = { showParameters = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = TranscriptMeasure)
                            .fillMaxWidth(),
                    )
                    }
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
                // Only asked for once the screen that shows them is actually on it, so a thread
                // with messages in it never pays for openers nobody will see.
                LaunchedEffect(state.settings.defaultModel, state.toolsEnabled) {
                    model.ensureSuggestions()
                }
                EmptyChatState(
                    hasModel = state.model.isNotBlank(),
                    suggestions = state.starterPrompts,
                    onSuggestionClick = { suggestion ->
                        model.onInputChange(suggestion)
                    },
                    onShuffle = model::refreshSuggestions,
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (state.isAgentRun) {
                        AgentRunBanner(
                            agentName = state.agentName,
                            onKeep = model::keepAgentRun,
                        )
                    }
                    // Weighted rather than filled: the agent banner above it, when there is one,
                    // has to take its height from somewhere. Inside that, the transcript holds a
                    // measure instead of filling the window — a line of prose set across 1100pt
                    // is one the eye loses its place on returning from the right-hand end, and a
                    // desktop window is always wider than anything anybody wants to read.
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = TranscriptMeasure)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(
                            items = visibleMessages,
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
                                onRegenerate = { model.regenerate(message.id) },
                                onBranch = { model.branchFrom(message.id) },
                                onSpeak = { model.readAloud(message.id) },
                                onMore = { actionTargetId = message.id },
                            )
                        }
                    }
                    }
                }

                // The way back, now that scrolling up no longer gets overridden. Shown for the
                // reader having left rather than for the end being off screen: during a long
                // answer the end is off screen between one token and the next, and a button that
                // appeared each time would blink at somebody who never moved.
                AnimatedVisibility(
                    visible = !followTail && !atBottom,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                ) {
                    JumpToLatestButton(
                        isGenerating = state.isGenerating,
                        onClick = {
                            // Following resumes here rather than waiting for the scroll to settle:
                            // the press *is* the reader saying they want the tail, and a streaming
                            // answer would otherwise move the end away again before we arrived.
                            followTail = true
                            scope.launch {
                                listState.scrollToTail(visibleMessages.lastIndex, animate = true)
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
            onSelect = model::setModel,
            onRefresh = model::refreshModels,
            onDismiss = { showModelPicker = false },
        )
    }

    if (showParameters) {
        ParametersSheet(
            params = state.params,
            systemPrompt = state.systemPrompt,
            supportsThinking = state.modelInfo?.supportsThinking ?: true,
            onParamsChange = model::setParams,
            onSystemPromptChange = model::setSystemPrompt,
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
                    model.editAndResend(targetId, newText)
                    actionTargetId = null
                },
                onDelete = {
                    model.deleteMessage(targetId)
                    actionTargetId = null
                },
                onBranch = {
                    model.branchFrom(targetId)
                    actionTargetId = null
                },
                onDismiss = { actionTargetId = null },
            )
        }
    }
}

/**
 * Puts the *end* of the last message on screen, rather than its beginning.
 *
 * This is the whole of the jump-to-latest bug, and it is worth being precise about because the old
 * code looked right. `scrollToItem` aligns an item's **top** with the top of the viewport, which is
 * what you want for "go to message 12" and exactly wrong for "go to the bottom": on an answer
 * longer than the screen — which is most answers worth jumping back to — it lands on the first line
 * of the reply and leaves the rest below the fold, so the button appeared to do nothing, or worse,
 * to jump somewhere arbitrary.
 *
 * So the scroll happens in two parts: get to the item, then consume whatever is still below it. The
 * loop terminates because a lazy list clamps its scroll at the end of its content, so each pass
 * either reaches the bottom or ends the loop by running out of scroll. The step count is the braces
 * to that belt, and it is set high because the cost of a spare iteration is nothing and the cost of
 * stopping short is the bug this function exists to fix.
 */
private suspend fun LazyListState.scrollToTail(index: Int, animate: Boolean = false) {
    if (index < 0) return
    if (animate) animateScrollToItem(index) else scrollToItem(index)

    var steps = 0
    while (canScrollForward && steps++ < MAX_TAIL_STEPS) {
        val viewport = layoutInfo.viewportSize.height.toFloat()
        if (viewport <= 0f) return
        // Never animated, even when the jump was: an animation per viewport of a long answer is a
        // second of scenery on the way to a destination the reader has already asked for.
        scrollBy(viewport)
    }
}

private const val MAX_TAIL_STEPS = 24

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
    topInset: Dp = 0.dp,
    leadingInset: Dp = 0.dp,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column {
    Spacer(Modifier.height(topInset))
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
            IconButton(onClick = onClose, modifier = Modifier.padding(start = leadingInset)) {
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
 * to be warm about it: a blank conversation. The greeting is the mark and one line, set side by
 * side in the rounded display face — a cloud and "Good evening" reads as somebody saying hello,
 * where a 40dp logo above a question reads as a splash screen. It also stops the screen asking a
 * question ("What should we dig into?") that the four rows underneath are already answering.
 *
 * Those rows are hairline-outlined rather than tinted chips, for the same reason the model list is:
 * it lets four of them read as one object instead of four buttons.
 */
@Composable
private fun EmptyChatState(
    hasModel: Boolean,
    suggestions: List<StarterPrompt>,
    onSuggestionClick: (String) -> Unit,
    onShuffle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = greeting(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (hasModel) {
                "Ask me anything, or start with one of these."
            } else {
                "Pick a model from the title bar and we can begin."
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
        if (suggestions.isNotEmpty() && hasModel) {
            Spacer(Modifier.height(4.dp))
            Row(
                // heightIn rather than padding: the row is the tap target, and a 32dp one would
                // be under the platform minimum however comfortable the text looks inside it.
                modifier = Modifier
                    .clip(Pill)
                    .clickable(onClick = onShuffle)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Suggest something else",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The time of day, said the way a person would.
 *
 * Read once per composition of the empty state rather than kept live: a greeting that changed under
 * the reader at midnight would be a strange thing to have built, and the screen is replaced by a
 * transcript the moment anything is sent.
 */
private fun greeting(): String = when (LocalTime.now().hour) {
    in 0..4 -> "Still up?"
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
