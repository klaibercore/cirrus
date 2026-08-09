package dev.klaiber.cirrus.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.AttachmentImporter
import dev.klaiber.cirrus.data.remote.OllamaException
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.ChatEngine
import dev.klaiber.cirrus.domain.TurnEvent
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.GenerationStats
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.domain.model.ToolInvocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val chatEngine: ChatEngine,
    private val attachmentImporter: AttachmentImporter,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** In-flight assistant turn, held in memory so the UI updates per token without DB writes. */
    private data class LiveTurn(
        val messageId: String,
        val content: String = "",
        val thinking: String = "",
        val tools: List<ToolInvocation> = emptyList(),
        val requestJson: String? = null,
        val stats: GenerationStats? = null,
    )

    private data class EditorState(
        val input: String = "",
        /** Live dictation not yet finalised by the recogniser; shown but not yet committed. */
        val voicePartial: String = "",
        val attachments: List<Attachment> = emptyList(),
        val error: String? = null,
    )

    private val conversationId = MutableStateFlow(
        savedStateHandle.get<String>(ARG_CONVERSATION_ID)?.takeIf { it.isNotBlank() },
    )
    private val live = MutableStateFlow<LiveTurn?>(null)
    private val editor = MutableStateFlow(EditorState())

    private val eventChannel = Channel<ChatEvent>(Channel.BUFFERED)
    val events: Flow<ChatEvent> = eventChannel.receiveAsFlow()

    private var generationJob: Job? = null
    private var lastPersistAt = 0L

    private val conversationFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(null) else conversationRepository.observeConversation(id)
    }

    private val messagesFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversationRepository.observeMessages(id)
    }

    private val coreFlow = combine(conversationFlow, messagesFlow, live) { conversation, messages, turn ->
        Triple(conversation, mergeLiveTurn(messages, turn), turn != null)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        coreFlow,
        settingsRepository.settings,
        modelRepository.models,
        editor,
    ) { core, settings, models, editorState ->
        val (conversation, messages, isGenerating) = core
        ChatUiState(
            conversation = conversation,
            messages = messages,
            isGenerating = isGenerating,
            input = editorState.input,
            voicePartial = editorState.voicePartial,
            pendingAttachments = editorState.attachments,
            settings = settings,
            availableModels = models,
            errorBanner = editorState.error,
            needsApiKey = !settings.hasApiKey && settings.baseUrl.contains("ollama.com"),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /** Exposed separately from [uiState] so a refresh spinner cannot recompose the transcript. */
    val isRefreshingModels: StateFlow<Boolean> = modelRepository.isRefreshing

    /** True while `/api/show` is still filling in capabilities behind an already-visible list. */
    val isLoadingModelDetails: StateFlow<Boolean> = modelRepository.isLoadingDetails

    init {
        viewModelScope.launch { modelRepository.refreshIfEmpty() }
    }

    fun refreshModels() {
        viewModelScope.launch {
            modelRepository.refresh().onFailure { error ->
                editor.update { it.copy(error = describe(error)) }
            }
        }
    }

    /** Overlays the live buffer onto the persisted row so the UI sees one coherent list. */
    private fun mergeLiveTurn(messages: List<ChatMessage>, turn: LiveTurn?): List<ChatMessage> {
        if (turn == null) return messages
        return messages.map { message ->
            if (message.id != turn.messageId) {
                message
            } else {
                message.copy(
                    content = turn.content,
                    thinking = turn.thinking.takeIf { it.isNotEmpty() },
                    toolInvocations = turn.tools,
                    rawRequestJson = turn.requestJson,
                    isStreaming = true,
                )
            }
        }
    }

    /** Typing commits whatever dictation was on screen, since the field shows both as one string. */
    fun onInputChange(value: String) = editor.update { it.copy(input = value, voicePartial = "") }

    fun dismissError() = editor.update { it.copy(error = null) }

    fun showError(message: String) = editor.update { it.copy(error = message) }

    /** A guess from the recogniser, replaced wholesale by the next partial or final result. */
    fun onVoicePartial(text: String) = editor.update {
        it.copy(voicePartial = joinDictation(it.input, text))
    }

    fun onVoiceFinal(text: String) = editor.update {
        it.copy(input = it.input + joinDictation(it.input, text), voicePartial = "")
    }

    /** Utterances arrive without surrounding whitespace, so supply the gap between them. */
    private fun joinDictation(existing: String, addition: String): String =
        if (existing.isEmpty() || existing.last().isWhitespace()) addition else " $addition"

    fun attach(uri: Uri) {
        viewModelScope.launch {
            attachmentImporter.import(uri).fold(
                onSuccess = { attachment ->
                    editor.update { it.copy(attachments = it.attachments + attachment) }
                },
                onFailure = { error ->
                    editor.update { it.copy(error = error.message ?: "Could not attach that file.") }
                },
            )
        }
    }

    fun removeAttachment(attachmentId: String) {
        editor.update { state ->
            state.attachments.firstOrNull { it.id == attachmentId }?.let(attachmentImporter::delete)
            state.copy(attachments = state.attachments.filterNot { it.id == attachmentId })
        }
    }

    fun send() {
        val state = uiState.value
        if (!state.canSend) return
        val text = state.composerText.trim()
        val attachments = state.pendingAttachments

        editor.update { EditorState() }

        viewModelScope.launch {
            val conversation = ensureConversation() ?: return@launch
            conversationRepository.appendMessage(
                conversationId = conversation.id,
                role = Role.USER,
                content = text,
                attachments = attachments,
            )
            startGeneration(conversation.id)
        }
    }

    fun stop() {
        generationJob?.cancel()
    }

    /** Drops the selected assistant message and everything after it, then re-asks. */
    fun regenerate(messageId: String) {
        val conversationId = uiState.value.conversation?.id ?: return
        viewModelScope.launch {
            generationJob?.cancelAndJoinQuietly()
            conversationRepository.truncateFrom(messageId)
            startGeneration(conversationId)
        }
    }

    /** Rewrites a user message in place and regenerates everything that followed it. */
    fun editAndResend(messageId: String, newText: String) {
        val conversationId = uiState.value.conversation?.id ?: return
        viewModelScope.launch {
            generationJob?.cancelAndJoinQuietly()
            val messages = conversationRepository.getMessages(conversationId)
            val target = messages.firstOrNull { it.id == messageId } ?: return@launch
            messages.filter { it.sequence > target.sequence }
                .forEach { conversationRepository.deleteMessage(it.id) }
            conversationRepository.editMessageText(messageId, newText)
            startGeneration(conversationId)
        }
    }

    fun branchFrom(messageId: String) {
        val conversationId = uiState.value.conversation?.id ?: return
        viewModelScope.launch {
            val fork = conversationRepository.fork(conversationId, messageId)
            if (fork == null) {
                eventChannel.send(ChatEvent.ShowMessage("Could not branch this conversation."))
            } else {
                eventChannel.send(ChatEvent.NavigateToConversation(fork.id))
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch { conversationRepository.deleteMessage(messageId) }
    }

    fun setModel(model: String) {
        viewModelScope.launch {
            val conversation = uiState.value.conversation
            if (conversation == null) {
                settingsRepository.setDefaultModel(model)
            } else {
                conversationRepository.updateConversation(conversation.copy(model = model))
            }
        }
    }

    fun setParams(params: GenerationParams) {
        viewModelScope.launch {
            val conversation = uiState.value.conversation
            if (conversation == null) {
                settingsRepository.setDefaultParams(params)
            } else {
                conversationRepository.updateConversation(conversation.copy(params = params))
            }
        }
    }

    fun setToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val conversation = uiState.value.conversation
            if (conversation == null) {
                settingsRepository.setToolsEnabledByDefault(enabled)
            } else {
                conversationRepository.updateConversation(conversation.copy(toolsEnabled = enabled))
            }
        }
    }

    fun setSystemPrompt(prompt: String?) {
        viewModelScope.launch {
            val conversation = uiState.value.conversation ?: return@launch
            conversationRepository.updateConversation(
                conversation.copy(systemPrompt = prompt?.takeIf { it.isNotBlank() }),
            )
        }
    }

    fun applyPreset(presetId: String) {
        viewModelScope.launch {
            val preset = conversationRepository.getPreset(presetId) ?: return@launch
            val conversation = uiState.value.conversation
            if (conversation == null) {
                val created = conversationRepository.createConversation(
                    model = preset.model ?: resolveDefaultModel(),
                    systemPrompt = preset.systemPrompt,
                    params = preset.params,
                    toolsEnabled = preset.toolsEnabled,
                    title = preset.name,
                )
                conversationId.value = created.id
            } else {
                conversationRepository.updateConversation(
                    conversation.copy(
                        model = preset.model ?: conversation.model,
                        systemPrompt = preset.systemPrompt,
                        params = preset.params,
                        toolsEnabled = preset.toolsEnabled,
                    ),
                )
            }
        }
    }

    fun shareConversation() {
        viewModelScope.launch {
            val state = uiState.value
            val conversation = state.conversation ?: return@launch
            eventChannel.send(
                ChatEvent.ShareText(
                    text = ConversationExporter.toMarkdown(conversation, state.messages),
                    subject = conversation.title,
                ),
            )
        }
    }

    fun openConversation(id: String?) {
        if (conversationId.value == id) return
        generationJob?.cancel()
        live.value = null
        editor.value = EditorState()
        conversationId.value = id
    }

    private suspend fun ensureConversation(): Conversation? {
        uiState.value.conversation?.let { return it }
        val settings = settingsRepository.current.value
        val model = resolveDefaultModel()
        if (model.isBlank()) {
            editor.update { it.copy(error = "Pick a model first.") }
            return null
        }
        val created = conversationRepository.createConversation(
            model = model,
            systemPrompt = null,
            params = settings.defaultParams,
            toolsEnabled = settings.toolsEnabledByDefault,
        )
        conversationId.value = created.id
        return created
    }

    private fun resolveDefaultModel(): String {
        val configured = settingsRepository.current.value.defaultModel
        if (configured.isNotBlank()) return configured
        return modelRepository.models.value.firstOrNull()?.name.orEmpty()
    }

    private suspend fun startGeneration(conversationId: String) {
        val conversation = conversationRepository.getConversation(conversationId) ?: return
        val history = conversationRepository.getMessages(conversationId)
        val settings = settingsRepository.current.value

        val placeholder = conversationRepository.appendMessage(
            conversationId = conversationId,
            role = Role.ASSISTANT,
            content = "",
            model = conversation.model,
        )
        live.value = LiveTurn(messageId = placeholder.id)
        lastPersistAt = 0L

        generationJob = viewModelScope.launch {
            try {
                chatEngine.respond(conversation, history, settings).collect { event ->
                    when (event) {
                        is TurnEvent.RequestPrepared -> live.update { turn ->
                            turn?.copy(
                                requestJson = if (settings.developerMode) event.requestJson else null,
                            )
                        }

                        is TurnEvent.ThinkingDelta -> {
                            live.update { it?.copy(thinking = it.thinking + event.text) }
                            persistThrottled()
                        }

                        is TurnEvent.ContentDelta -> {
                            live.update { it?.copy(content = it.content + event.text) }
                            persistThrottled()
                        }

                        is TurnEvent.ToolStarted -> live.update { turn ->
                            turn?.copy(tools = turn.tools + event.invocation)
                        }

                        is TurnEvent.ToolFinished -> live.update { turn ->
                            turn?.copy(
                                tools = turn.tools.map { existing ->
                                    if (existing.id == event.invocation.id) event.invocation else existing
                                },
                            )
                        }

                        is TurnEvent.Finished -> live.update { it?.copy(stats = event.stats) }
                    }
                }
                finalize(errorMessage = null)
                maybeGenerateTitle(conversationId)
            } catch (cancellation: CancellationException) {
                // Preserve whatever streamed before the user hit stop.
                withContext(NonCancellable) { finalize(errorMessage = null) }
                throw cancellation
            } catch (error: Throwable) {
                finalize(errorMessage = describe(error))
            }
        }
    }

    /**
     * Writes the buffer to the database at most every [PERSIST_INTERVAL_MS].
     *
     * Per-token writes would hammer SQLite for no benefit; this bounds how much of a long
     * generation is lost if the process dies mid-stream.
     */
    private suspend fun persistThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        val turn = live.value ?: return
        conversationRepository.updateMessageContent(
            messageId = turn.messageId,
            content = turn.content,
            thinking = turn.thinking.takeIf { it.isNotEmpty() },
            stats = null,
            toolInvocations = turn.tools,
            errorMessage = null,
        )
    }

    private suspend fun finalize(errorMessage: String?) {
        val turn = live.value ?: return
        conversationRepository.updateMessageContent(
            messageId = turn.messageId,
            content = turn.content,
            thinking = turn.thinking.takeIf { it.isNotEmpty() },
            stats = turn.stats,
            toolInvocations = turn.tools,
            errorMessage = errorMessage,
        )
        live.value = null
        if (errorMessage != null) {
            editor.update { it.copy(error = errorMessage) }
        }
    }

    /**
     * Keeps the thread title in step with what the conversation has become.
     *
     * A long thread drifts away from its opening question, so the title is re-summarised as it
     * grows — but at most once every [RETITLE_INTERVAL_MS], because each pass is an extra
     * request. A title the user typed themselves is never touched.
     */
    private suspend fun maybeGenerateTitle(conversationId: String) {
        val settings = settingsRepository.current.value
        if (!settings.autoTitleConversations) return
        val conversation = conversationRepository.getConversation(conversationId) ?: return

        val now = System.currentTimeMillis()
        val lastTitledAt = conversation.autoTitledAt
        val shouldTitle = when {
            // Still the placeholder: name it immediately, no waiting.
            lastTitledAt == null -> conversation.title == DEFAULT_TITLE
            else -> now - lastTitledAt >= RETITLE_INTERVAL_MS
        }
        if (!shouldTitle) return

        val transcript = summarisableTranscript(conversationId) ?: return
        chatEngine.suggestTitle(conversation.model, transcript)?.let { title ->
            conversationRepository.applyAutoTitle(conversationId, title, now)
        }
    }

    /**
     * A digest of the conversation for the titler: the opening exchange, which sets the topic,
     * plus the latest turns, which show where it has got to. The middle is dropped — it is the
     * least informative part and the most expensive to send.
     */
    private suspend fun summarisableTranscript(conversationId: String): String? {
        val messages = conversationRepository.getMessages(conversationId)
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .filter { it.errorMessage == null && it.content.isNotBlank() }
        if (messages.isEmpty()) return null

        val excerpt = if (messages.size <= TITLE_CONTEXT_MESSAGES) {
            messages
        } else {
            messages.take(TITLE_HEAD_MESSAGES) +
                messages.takeLast(TITLE_CONTEXT_MESSAGES - TITLE_HEAD_MESSAGES)
        }
        return excerpt.joinToString("\n\n") { message ->
            val speaker = if (message.role == Role.USER) "User" else "Assistant"
            "$speaker: ${message.content.take(TITLE_MESSAGE_CHARS)}"
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is OllamaException.MissingApiKey -> "Add your Ollama API key in Settings to start chatting."
        is OllamaException.Unauthorized -> "The API key was rejected. Check it in Settings."
        is OllamaException.RateLimited -> error.retryAfterSeconds
            ?.let { "Rate limited. Try again in ${it}s." }
            ?: "Rate limited. Try again shortly."
        is OllamaException.ModelNotFound -> "\"${error.model}\" is not available on this host."
        is OllamaException.Network -> "Network error: ${error.message}"
        else -> error.message ?: "Something went wrong."
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        runCatching { join() }
    }

    private companion object {
        const val ARG_CONVERSATION_ID = "conversationId"
        const val PERSIST_INTERVAL_MS = 600L
        const val DEFAULT_TITLE = "New chat"

        /**
         * How long an auto-title stands before the thread is summarised again. Long enough that
         * a busy half-hour of chat costs one extra request, not one per turn.
         */
        const val RETITLE_INTERVAL_MS = 30 * 60 * 1000L

        const val TITLE_CONTEXT_MESSAGES = 8
        const val TITLE_HEAD_MESSAGES = 2
        const val TITLE_MESSAGE_CHARS = 400
    }
}
