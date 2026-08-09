package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.dto.ShowResponseDto
import dev.klaiber.cirrus.data.remote.dto.TagModelDto
import dev.klaiber.cirrus.di.ApplicationScope
import dev.klaiber.cirrus.domain.model.ModelCapability
import dev.klaiber.cirrus.domain.model.ModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the host's model catalogue.
 *
 * The list is small and changes rarely, so it is refreshed on demand rather than persisted;
 * a cold start with no network simply shows the last-known list as empty and surfaces the error.
 *
 * `/api/tags` carries no capability information, so each entry is enriched in the background
 * from `/api/show`. That pass is best-effort: the picker renders immediately from name-derived
 * guesses and upgrades in place as answers land.
 */
@Singleton
class ModelRepository @Inject constructor(
    private val client: OllamaClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val refreshMutex = Mutex()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** True while capability details are still arriving, after the list itself is on screen. */
    private val _isLoadingDetails = MutableStateFlow(false)
    val isLoadingDetails: StateFlow<Boolean> = _isLoadingDetails.asStateFlow()

    private var detailJob: Job? = null

    suspend fun refresh(): Result<List<ModelInfo>> = refreshMutex.withLock {
        _isRefreshing.value = true
        try {
            runCatching { client.listModels().map(::toModelInfo).sortedBy { it.name } }
                .onSuccess { models ->
                    _models.value = models
                    loadCapabilities(models)
                }
        } finally {
            _isRefreshing.value = false
        }
    }

    /** Refreshes only when nothing is cached yet, for screen entry. */
    suspend fun refreshIfEmpty(): Result<List<ModelInfo>> =
        if (_models.value.isEmpty()) refresh() else Result.success(_models.value)

    fun find(name: String): ModelInfo? = _models.value.firstOrNull { it.name == name }

    /**
     * Fills in capabilities from `/api/show`, a few models at a time.
     *
     * Runs on the application scope rather than the caller's: a screen being closed mid-pass
     * should not abandon a catalogue that every other screen shares. A host without the route
     * fails identically for every model, so the pass gives up once the first handful all fail.
     */
    private fun loadCapabilities(models: List<ModelInfo>) {
        detailJob?.cancel()
        detailJob = scope.launch {
            _isLoadingDetails.value = true
            try {
                var succeeded = 0
                var failed = 0
                for (batch in models.chunked(DETAIL_BATCH_SIZE)) {
                    val outcomes = coroutineScope {
                        batch.map { model -> async { fetchCapabilities(model.name) } }.awaitAll()
                    }
                    succeeded += outcomes.count { it }
                    failed += outcomes.count { !it }
                    if (succeeded == 0 && failed >= GIVE_UP_AFTER_FAILURES) break
                }
            } finally {
                _isLoadingDetails.value = false
            }
        }
    }

    /** Returns true when the host answered, so the caller can tell "no route" from "one bad model". */
    private suspend fun fetchCapabilities(name: String): Boolean {
        val detail = runCatching { client.showModel(name) }.getOrNull() ?: return false
        _models.update { current ->
            current.map { model -> if (model.name == name) model.merge(detail) else model }
        }
        return true
    }

    private fun ModelInfo.merge(detail: ShowResponseDto): ModelInfo = copy(
        parameterSize = detail.details?.parameterSize?.takeIf { it.isNotBlank() } ?: parameterSize,
        quantization = detail.details?.quantizationLevel?.takeIf { it.isNotBlank() } ?: quantization,
        family = detail.details?.family?.takeIf { it.isNotBlank() } ?: family,
        reportedCapabilities = detail.capabilities.mapNotNull(ModelCapability::fromWire).toSet(),
        contextLength = detail.contextLength() ?: contextLength,
        remoteHost = detail.remoteHost?.takeIf { it.isNotBlank() } ?: remoteHost,
    )

    /** `model_info` keys are architecture-prefixed, e.g. `qwen3.context_length`. */
    private fun ShowResponseDto.contextLength(): Int? = modelInfo
        ?.entries
        ?.firstOrNull { (key, _) -> key.endsWith(CONTEXT_LENGTH_SUFFIX) }
        ?.value
        ?.let { it as? JsonPrimitive }
        ?.intOrNull

    private fun toModelInfo(dto: TagModelDto): ModelInfo = ModelInfo(
        name = dto.name,
        sizeBytes = dto.size,
        parameterSize = dto.details?.parameterSize?.takeIf { it.isNotBlank() },
        quantization = dto.details?.quantizationLevel?.takeIf { it.isNotBlank() },
        family = dto.details?.family?.takeIf { it.isNotBlank() },
        modifiedAt = dto.modifiedAt,
    )

    private companion object {
        const val DETAIL_BATCH_SIZE = 4
        const val GIVE_UP_AFTER_FAILURES = 4
        const val CONTEXT_LENGTH_SUFFIX = ".context_length"
    }
}
