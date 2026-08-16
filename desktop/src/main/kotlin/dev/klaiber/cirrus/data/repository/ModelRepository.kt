package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.remote.ModelCapabilityDetector
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.dto.ShowResponseDto
import dev.klaiber.cirrus.data.remote.dto.TagModelDto
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

/**
 * In-memory cache of the host's model catalogue.
 *
 * The list is small and changes rarely, so it is refreshed on demand rather than persisted; a
 * cold start with no network simply shows the last-known list as empty and surfaces the error.
 *
 * `/api/tags` carries no capability information, so each entry is enriched in the background
 * from `/api/show`. That pass is best-effort: the picker renders immediately from name-derived
 * guesses and upgrades in place as answers land.
 */
class ModelRepository(
    private val client: OllamaClient,
    private val detector: ModelCapabilityDetector,
    private val scope: CoroutineScope,
) {
    private val refreshMutex = Mutex()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

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

    suspend fun refreshIfEmpty(): Result<List<ModelInfo>> =
        if (_models.value.isEmpty()) refresh() else Result.success(_models.value)

    fun find(name: String): ModelInfo? = _models.value.firstOrNull { it.name == name }

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

    private suspend fun fetchCapabilities(name: String): Boolean {
        val detail = runCatching { client.showModel(name) }.getOrNull() ?: return false
        _models.update { current ->
            current.map { model -> if (model.name == name) model.merge(detail) else model }
        }
        return true
    }

    private fun ModelInfo.merge(detail: ShowResponseDto): ModelInfo =
        detector.detect(detail).let { detected ->
            copy(
                parameterSize = detected.parameterSize ?: parameterSize,
                quantization = detected.quantization ?: quantization,
                family = detected.family ?: family,
                reportedCapabilities = detected.capabilities,
                contextLength = detected.contextLength ?: contextLength,
                remoteHost = detected.remoteHost ?: remoteHost,
            )
        }

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
    }
}
