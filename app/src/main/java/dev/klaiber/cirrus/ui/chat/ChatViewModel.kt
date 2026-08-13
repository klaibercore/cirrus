package dev.klaiber.cirrus.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.AttachmentImporter
import dev.klaiber.cirrus.data.repository.ConversationRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.SpeechController
import dev.klaiber.cirrus.domain.TurnController
import dev.klaiber.cirrus.domain.userMessage
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Conversation
import dev.klaiber.cirrus.domain.model.GenerationParams
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.ui.markdown.markdownToSpeech
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val turnController: TurnController,
    private val speechController: SpeechController,
    private val attachmentImporter: AttachmentImporter,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private data class EditorState(
        val input: String = "",
        /** Live dictation not yet finalised by the recogniser; shown but not yet committed. */
        val voicePartial: String = "",
        val attachments: List<Attachment> = emptyList(),
        val error: String? = null,
    )

    /** What the screen needs from the turn running elsewhere: the buffer and any failure. */
    private data class TurnState(
        val turn: TurnController.LiveTurn? = null,
        val error: String? = null,
    )

    private val conversationId = MutableStateFlow(
        savedStateHandle.get<String>(ARG_CONVERSATION_ID)?.takeIf { it.isNotBlank() },
    )
    private val editor = MutableStateFlow(EditorState())

    /** Only what the user typed; which messages match is derived from the transcript. */
    private data class SearchInput(
        val isActive: Boolean = false,
        val query: String = "",
        val index: Int = 0,
    )

    private val search = MutableStateFlow(SearchInput())

    private val eventChannel = Channel<ChatEvent>(Channel.BUFFERED)
    val events: Flow<ChatEvent> = eventChannel.receiveAsFlow()

    private val conversationFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(null) else conversationRepository.observeConversation(id)
    }

    private val messagesFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversationRepository.observeMessages(id)
    }

    /**
     * The turn belongs to [TurnController], not to this ViewModel: the screen watches whichever
     * thread it is showing and no longer owns — or cancels — the work.
     */
    private val turnFlow = conversationId.flatMapLatest { id ->
        if (id == null) {
            flowOf(TurnState())
        } else {
            combine(turnController.turns, turnController.errors) { turns, errors ->
                TurnState(turns[id], errors[id])
            }
        }
    }

    private val coreFlow = combine(
        conversationFlow,
        messagesFlow,
        turnFlow,
    ) { conversation, messages, turnState ->
        Triple(conversation, mergeLiveTurn(messages, turnState.turn), turnState)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        coreFlow,
        settingsRepository.settings,
        modelRepository.models,
        editor,
        search,
    ) { core, settings, models, editorState, searchInput ->
        val (conversation, messages, turnState) = core
        ChatUiState(
            conversation = conversation,
            messages = messages,
            isGenerating = turnState.turn != null,
            input = editorState.input,
            voicePartial = editorState.voicePartial,
            pendingAttachments = editorState.attachments,
            settings = settings,
            availableModels = models,
            errorBanner = editorState.error ?: turnState.error,
            needsApiKey = !settings.hasApiKey && settings.baseUrl.contains("ollama.com"),
            search = searchInput.resolve(messages),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /**
     * Which messages contain the query, and which of those is currently in view.
     *
     * The index is clamped rather than reset as the transcript changes: a reply that lands while
     * the search bar is open must not throw away where the reader had got to.
     */
    private fun SearchInput.resolve(messages: List<ChatMessage>): ChatSearch {
        if (!isActive || query.isBlank()) return ChatSearch(isActive = isActive, query = query)
        val matches = messages
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .filter { it.content.contains(query, ignoreCase = true) }
            .map { it.id }
        return ChatSearch(
            isActive = true,
            query = query,
            matchIds = matches,
            currentIndex = index.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
        )
    }

    fun openSearch() = search.update { it.copy(isActive = true) }

    fun closeSearch() {
        search.value = SearchInput()
    }

    /** A new query starts at the first match, not wherever the last one had wandered to. */
    fun onSearchQueryChange(query: String) = search.update {
        it.copy(query = query, index = 0)
    }

    fun nextMatch() = stepMatch(1)

    fun previousMatch() = stepMatch(-1)

    private fun stepMatch(delta: Int) {
        val total = uiState.value.search.matchIds.size
        if (total == 0) return
        // Wrapping is what every find bar does, and the alternative is a button that stops working.
        search.update { it.copy(index = ((it.index + delta) % total + total) % total) }
    }

    /** Exposed separately from [uiState] so a refresh spinner cannot recompose the transcript. */
    val isRefreshingModels: StateFlow<Boolean> = modelRepository.isRefreshing

    /** True while `/api/show` is still filling in capabilities behind an already-visible list. */
    val isLoadingModelDetails: StateFlow<Boolean> = modelRepository.isLoadingDetails

    /** Which message is being read aloud, if any. Owned by [SpeechController], not by this screen. */
    val speaking: StateFlow<SpeechController.Speaking?> = speechController.speaking

    init {
        viewModelScope.launch { modelRepository.refreshIfEmpty() }
        // A failed synthesis has to say so somewhere; silence is indistinguishable from a bug.
        viewModelScope.launch {
            speechController.errors.collect { message ->
                if (message != null) {
                    editor.update { it.copy(error = message) }
                    speechController.clearError()
                }
            }
        }
    }

    /**
     * Reads an answer aloud, or stops if it is already reading that one.
     *
     * The markdown is converted here rather than in the controller: turning a document into
     * something worth hearing is a rendering decision, and the controller's job is only to make
     * sound come out.
     */
    fun readAloud(messageId: String) {
        val message = uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        speechController.toggle(messageId, markdownToSpeech(message.content))
    }

    fun refreshModels() {
        viewModelScope.launch {
            modelRepository.refresh().onFailure { error ->
                editor.update { it.copy(error = error.userMessage()) }
            }
        }
    }

    /** Overlays the live buffer onto the persisted row so the UI sees one coherent list. */
    private fun mergeLiveTurn(
        messages: List<ChatMessage>,
        turn: TurnController.LiveTurn?,
    ): List<ChatMessage> {
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

    fun dismissError() {
        editor.update { it.copy(error = null) }
        conversationId.value?.let(turnController::clearError)
    }

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
            turnController.start(conversation.id)
        }
    }

    fun stop() {
        conversationId.value?.let(turnController::stop)
    }

    /** Drops the selected assistant message and everything after it, then re-asks. */
    fun regenerate(messageId: String) {
        val conversationId = uiState.value.conversation?.id ?: return
        viewModelScope.launch {
            // Wait for the turn to let go before rewriting the history it is reading.
            turnController.stopAndJoin(conversationId)
            conversationRepository.truncateFrom(messageId)
            turnController.start(conversationId)
        }
    }

    /** Rewrites a user message in place and regenerates everything that followed it. */
    fun editAndResend(messageId: String, newText: String) {
        val conversationId = uiState.value.conversation?.id ?: return
        viewModelScope.launch {
            turnController.stopAndJoin(conversationId)
            val messages = conversationRepository.getMessages(conversationId)
            val target = messages.firstOrNull { it.id == messageId } ?: return@launch
            messages.filter { it.sequence > target.sequence }
                .forEach { conversationRepository.deleteMessage(it.id) }
            conversationRepository.editMessageText(messageId, newText)
            turnController.start(conversationId)
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

    /**
     * Switches which thread the screen is showing. It no longer stops the turn: a generation
     * belongs to its conversation, and leaving to read another one is not a decision to abandon
     * it. Whatever streams while you are away is on the thread when you come back.
     */
    fun openConversation(id: String?) {
        if (conversationId.value == id) return
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

    private companion object {
        const val ARG_CONVERSATION_ID = "conversationId"
    }
}
