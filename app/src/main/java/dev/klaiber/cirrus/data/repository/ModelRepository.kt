package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.dto.TagModelDto
import dev.klaiber.cirrus.domain.model.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the host's model catalogue.
 *
 * The list is small and changes rarely, so it is refreshed on demand rather than persisted;
 * a cold start with no network simply shows the last-known list as empty and surfaces the error.
 */
@Singleton
class ModelRepository @Inject constructor(
    private val client: OllamaClient,
) {
    private val refreshMutex = Mutex()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    suspend fun refresh(): Result<List<ModelInfo>> = refreshMutex.withLock {
        _isRefreshing.value = true
        try {
            runCatching { client.listModels().map(::toModelInfo).sortedBy { it.name } }
                .onSuccess { _models.value = it }
        } finally {
            _isRefreshing.value = false
        }
    }

    /** Refreshes only when nothing is cached yet, for screen entry. */
    suspend fun refreshIfEmpty(): Result<List<ModelInfo>> =
        if (_models.value.isEmpty()) refresh() else Result.success(_models.value)

    fun find(name: String): ModelInfo? = _models.value.firstOrNull { it.name == name }

    private fun toModelInfo(dto: TagModelDto): ModelInfo = ModelInfo(
        name = dto.name,
        sizeBytes = dto.size,
        parameterSize = dto.details?.parameterSize?.takeIf { it.isNotBlank() },
        quantization = dto.details?.quantizationLevel?.takeIf { it.isNotBlank() },
        family = dto.details?.family?.takeIf { it.isNotBlank() },
        modifiedAt = dto.modifiedAt,
    )
}
