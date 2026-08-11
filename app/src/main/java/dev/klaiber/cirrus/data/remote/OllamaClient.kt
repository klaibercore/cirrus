package dev.klaiber.cirrus.data.remote

import dev.klaiber.cirrus.data.remote.dto.ChatChunkDto
import dev.klaiber.cirrus.data.remote.dto.ChatRequestDto
import dev.klaiber.cirrus.data.remote.dto.ErrorResponseDto
import dev.klaiber.cirrus.data.remote.dto.ShowRequestDto
import dev.klaiber.cirrus.data.remote.dto.ShowResponseDto
import dev.klaiber.cirrus.data.remote.dto.TagModelDto
import dev.klaiber.cirrus.data.remote.dto.TagsResponseDto
import dev.klaiber.cirrus.data.remote.dto.WebFetchRequestDto
import dev.klaiber.cirrus.data.remote.dto.WebFetchResponseDto
import dev.klaiber.cirrus.data.remote.dto.WebSearchRequestDto
import dev.klaiber.cirrus.data.remote.dto.WebSearchResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin transport over the Ollama HTTP API.
 *
 * This layer only speaks HTTP and JSON: it does not know about conversations, tool loops or
 * persistence. Everything above it consumes [streamChat] as a cold [Flow] of NDJSON chunks.
 */
@Singleton
class OllamaClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val credentials: ApiCredentials,
) {

    /**
     * Streams a chat completion as newline-delimited JSON.
     *
     * The returned flow is cold; collecting it issues the request, and cancelling the collector
     * cancels the underlying [Call] so a long generation stops server-side too.
     */
    fun streamChat(request: ChatRequestDto): Flow<ChatChunkDto> = flow {
        requireCredentials()
        val call = httpClient.newCall(
            buildRequest("/api/chat", encodeRequest(request.copy(stream = true))),
        )
        // Cancelling the coroutine must abort the socket read, which is otherwise blocking.
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        val response = try {
            call.execute()
        } catch (io: IOException) {
            throw asOllamaException(io)
        }

        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) {
                throw errorFor(httpResponse, request.model)
            }
            val source = httpResponse.body.source()
            var sawDone = false
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (io: IOException) {
                    throw asOllamaException(io)
                } ?: break

                if (line.isBlank()) continue
                val chunk = try {
                    json.decodeFromString(ChatChunkDto.serializer(), line)
                } catch (e: Exception) {
                    throw OllamaException.Malformed("Unreadable stream chunk: $line", e)
                }
                // Some failures arrive as a 200 with an error field on a single line.
                chunk.error?.let { throw OllamaException.ServerError(200, it) }
                emit(chunk)
                if (chunk.done) {
                    sawDone = true
                    break
                }
            }
            // A stream that just stops is not a finished answer: the server died, the network
            // moved, or the process was frozen mid-read. Completing quietly here is what makes a
            // half-written reply look like the model's final word, so say so instead.
            // Cancellation never reaches this line — `emit` throws first.
            if (!sawDone) throw OllamaException.Truncated()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(): List<TagModelDto> = withContext(Dispatchers.IO) {
        // `/api/tags` is readable without a key on the cloud host, so no credential check here.
        val call = httpClient.newCall(buildRequest("/api/tags", body = null))
        executeForJson(call, TagsResponseDto.serializer()).models
    }

    /**
     * Reads one model's manifest, whose `capabilities` array is the only authoritative answer to
     * "can this model see images / call tools / think".
     */
    suspend fun showModel(model: String): ShowResponseDto = withContext(Dispatchers.IO) {
        // Like `/api/tags`, this is metadata; let the caller's error handling deal with a 401.
        val payload = json.encodeToString(ShowRequestDto.serializer(), ShowRequestDto(model))
        val call = httpClient.newCall(buildRequest("/api/show", payload))
        executeForJson(call, ShowResponseDto.serializer())
    }

    suspend fun webSearch(query: String, maxResults: Int): WebSearchResponseDto =
        withContext(Dispatchers.IO) {
            requireCredentials()
            val payload = json.encodeToString(
                WebSearchRequestDto.serializer(),
                WebSearchRequestDto(query = query, maxResults = maxResults),
            )
            val call = httpClient.newCall(buildRequest("/api/web_search", payload))
            executeForJson(call, WebSearchResponseDto.serializer())
        }

    suspend fun webFetch(url: String): WebFetchResponseDto = withContext(Dispatchers.IO) {
        requireCredentials()
        val payload = json.encodeToString(WebFetchRequestDto.serializer(), WebFetchRequestDto(url))
        val call = httpClient.newCall(buildRequest("/api/web_fetch", payload))
        executeForJson(call, WebFetchResponseDto.serializer())
    }

    /** Verifies the current key by issuing a minimal authenticated request. */
    suspend fun validateCredentials(model: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireCredentials()
            val probe = ChatRequestDto(
                model = model,
                messages = listOf(dev.klaiber.cirrus.data.remote.dto.MessageDto("user", "ping")),
                stream = false,
                options = kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "num_predict" to kotlinx.serialization.json.JsonPrimitive(1),
                    ),
                ),
            )
            val call = httpClient.newCall(buildRequest("/api/chat", encodeRequest(probe)))
            val response = try {
                call.execute()
            } catch (io: IOException) {
                throw asOllamaException(io)
            }
            response.use {
                if (!it.isSuccessful) throw errorFor(it, model)
            }
        }
    }

    /** Serializes a request exactly as it will be sent, for the developer-mode inspector. */
    fun encodeRequest(request: ChatRequestDto): String =
        json.encodeToString(ChatRequestDto.serializer(), request)

    private fun requireCredentials() {
        if (credentials.apiKey == null && credentials.isCloudHost()) {
            throw OllamaException.MissingApiKey()
        }
    }

    private fun buildRequest(path: String, body: String?): Request {
        val builder = Request.Builder()
            .url(credentials.baseUrl + path)
            .header("Accept", "application/json")
        if (body != null) {
            builder.post(body.toRequestBody(JSON_MEDIA_TYPE))
        }
        return builder.build()
    }

    private fun <T> executeForJson(
        call: Call,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val response = try {
            call.execute()
        } catch (io: IOException) {
            throw asOllamaException(io)
        }
        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) throw errorFor(httpResponse, model = null)
            val text = httpResponse.body.string()
            return try {
                json.decodeFromString(deserializer, text)
            } catch (e: Exception) {
                throw OllamaException.Malformed("Unexpected response shape.", e)
            }
        }
    }

    /** Reads the error envelope and maps the status code onto an actionable exception type. */
    private fun errorFor(response: Response, model: String?): OllamaException {
        val raw = runCatching { response.body.string() }.getOrNull()
        val detail = raw
            ?.let { text -> runCatching { json.decodeFromString(ErrorResponseDto.serializer(), text).error }.getOrNull() }
            ?: raw?.takeIf { it.isNotBlank() && it.length < MAX_INLINE_ERROR_LENGTH }

        return when (response.code) {
            401, 403 -> OllamaException.Unauthorized(detail)
            404 -> OllamaException.ModelNotFound(model ?: "unknown", detail)
            429 -> OllamaException.RateLimited(
                detail,
                response.header("Retry-After")?.toLongOrNull(),
            )
            else -> OllamaException.ServerError(response.code, detail)
        }
    }

    private fun asOllamaException(io: IOException): OllamaException =
        if (io is OllamaException) io else OllamaException.Network(io)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_INLINE_ERROR_LENGTH = 500
    }
}
