package dev.klaiber.cirrus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.di.AppContainer
import dev.klaiber.cirrus.domain.TurnController
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.ConversationSummary
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.ui.markdown.MarkdownText
import kotlinx.coroutines.launch

/**
 * The chat screen: a conversation drawer on the left, the thread and composer on the right.
 *
 * State is held here rather than in a ViewModel — the desktop build has no back stack to survive,
 * so a composable's remembered state lives exactly as long as the window, which is the same
 * lifetime the Android ViewModel was buying.
 */
@Composable
fun ChatScreen(container: AppContainer, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val summaries by container.conversationRepository.observeSummaries().collectAsState(emptyList())
    val settings by container.settingsRepository.settings.collectAsState(AppSettings())
    val models by container.modelRepository.models.collectAsState(emptyList())
    val turns by container.turnController.turns.collectAsState(emptyMap())
    val errors by container.turnController.errors.collectAsState(emptyMap())

    var currentId by remember { mutableStateOf<String?>(null) }
    val messages by container.conversationRepository.observeMessages(currentId ?: "").collectAsState(emptyList())
    val currentConversation by container.conversationRepository.observeConversation(currentId ?: "").collectAsState(null)

    var composerText by remember { mutableStateOf("") }
    var toolsEnabled by remember { mutableStateOf(settings.toolsEnabledByDefault) }
    var selectedModel by remember { mutableStateOf(settings.defaultModel) }

    LaunchedEffect(Unit) { container.modelRepository.refreshIfEmpty() }

    // Keep the composer's tools toggle in step with the conversation being shown.
    LaunchedEffect(currentConversation?.id) {
        currentConversation?.let { toolsEnabled = it.toolsEnabled }
    }

    val liveTurn = currentId?.let { turns[it] }
    val isGenerating = liveTurn != null

    fun send() {
        val text = composerText.trim()
        if (text.isEmpty()) return
        composerText = ""
        scope.launch {
            val id = currentId ?: run {
                val model = selectedModel.ifBlank { settings.defaultModel }
                    .ifBlank { models.firstOrNull()?.name.orEmpty() }
                if (model.isBlank()) return@launch
                val conversation = container.conversationRepository.createConversation(
                    model = model,
                    toolsEnabled = toolsEnabled,
                )
                currentId = conversation.id
                conversation.id
            }
            container.conversationRepository.appendMessage(id, Role.USER, text)
            container.turnController.start(id)
        }
    }

    Row(Modifier.fillMaxSize()) {
        ConversationSidebar(
            summaries = summaries,
            currentId = currentId,
            onSelect = { currentId = it },
            onNewChat = { currentId = null; composerText = "" },
            onOpenSettings = onOpenSettings,
        )

        Column(Modifier.weight(1f).fillMaxHeight()) {
            MessageList(
                messages = messages,
                liveTurn = liveTurn,
                settings = settings,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            errors[currentId]?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Composer(
                text = composerText,
                onTextChange = { composerText = it },
                onSend = { send() },
                isGenerating = isGenerating,
                onStop = { currentId?.let(container.turnController::stop) },
                toolsEnabled = toolsEnabled,
                onToolsToggle = { enabled ->
                    toolsEnabled = enabled
                    currentConversation?.let { conversation ->
                        scope.launch {
                            container.conversationRepository.updateConversation(
                                conversation.copy(toolsEnabled = enabled),
                            )
                        }
                    }
                },
                models = models,
                selectedModel = selectedModel,
                onModelSelect = { selectedModel = it },
            )
        }
    }
}

@Composable
private fun ConversationSidebar(
    summaries: List<ConversationSummary>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Cirrus",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        TextButton(
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("New chat")
        }

        LazyColumn(Modifier.weight(1f)) {
            items(summaries, key = { it.conversation.id }) { summary ->
                val selected = summary.conversation.id == currentId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(summary.conversation.id) }
                        .background(
                            if (selected) MaterialTheme.colorScheme.surface
                            else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = summary.conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    summary.lastMessagePreview?.let { preview ->
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    liveTurn: TurnController.LiveTurn?,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Follow the stream: keep the newest message in view as content arrives.
    LaunchedEffect(messages.size, liveTurn?.content?.length, liveTurn?.thinking?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            val isLive = liveTurn != null && message.id == liveTurn.messageId
            MessageItem(
                message = message,
                liveContent = if (isLive) liveTurn.content else null,
                liveThinking = if (isLive) liveTurn.thinking else null,
                renderMarkdown = settings.renderMarkdown,
            )
        }
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    liveContent: String?,
    liveThinking: String?,
    renderMarkdown: Boolean,
) {
    val isUser = message.role == Role.USER
    val content = liveContent ?: message.content
    val thinking = liveThinking ?: message.thinking

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 640.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!thinking.isNullOrBlank()) {
                    Text(
                        text = thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (content.isNotBlank()) {
                    if (isUser || !renderMarkdown) {
                        Text(text = content, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MarkdownText(markdown = content)
                    }
                } else if (liveContent != null) {
                    // Streaming but nothing has arrived yet.
                    Text(text = "…", style = MaterialTheme.typography.bodyMedium)
                }
                message.errorMessage?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    onStop: () -> Unit,
    toolsEnabled: Boolean,
    onToolsToggle: (Boolean) -> Unit,
    models: List<dev.klaiber.cirrus.domain.model.ModelInfo>,
    selectedModel: String,
    onModelSelect: (String) -> Unit,
) {
    var modelMenuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { modelMenuOpen = true }) {
                    Text(
                        text = selectedModel.ifBlank { "Select model" },
                        maxLines = 1,
                    )
                }
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName) },
                            onClick = {
                                onModelSelect(model.name)
                                modelMenuOpen = false
                            },
                        )
                    }
                }
            }

            TextButton(onClick = { onToolsToggle(!toolsEnabled) }) {
                Text(if (toolsEnabled) "Tools: on" else "Tools: off")
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Cirrus…") },
                maxLines = 8,
            )
            Spacer(Modifier.width(8.dp))
            if (isGenerating) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(onClick = onSend, enabled = text.isNotBlank()) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
