package dev.klaiber.cirrus.data.remote.elevenlabs

import dev.klaiber.cirrus.di.ElevenLabsHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport for the ElevenLabs text-to-speech API.
 *
 * Speaks HTTP and nothing else: it knows how to turn a string into an MP3 file and how to list the
 * voices on the account, and nothing about messages, markdown or playback. The audio is written
 * straight to a file rather than held in memory because a long answer is a few megabytes and the
 * player wants a file anyway.
 */
@Singleton
class ElevenLabsClient @Inject constructor(
    @ElevenLabsHttp private val client: OkHttpClient,
    private val credentials: ElevenLabsCredentials,
    private val json: Json,
) {

    /**
     * Synthesises [text] into [destination].
     *
     * Returns the file so callers can hand it to a player. Anything that goes wrong — no key, a
     * rejected key, a quota — surfaces as an [ElevenLabsException] with a sentence the settings
     * screen can show, because "read aloud did nothing" is the worst possible failure mode.
     */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        modelId: String,
        destination: File,
    ): File = withContext(Dispatchers.IO) {
        val key = credentials.apiKey ?: throw ElevenLabsException.MissingApiKey

        val body = json.encodeToString(
            SpeechRequest.serializer(),
            SpeechRequest(text = text, modelId = modelId),
        ).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("${credentials.apiBaseUrl}/v1/text-to-speech/$voiceId?output_format=$OUTPUT_FORMAT")
            .header("xi-api-key", key)
            .header("Accept", "audio/mpeg")
            .post(body)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw ElevenLabsException.Network(error.message ?: "Could not reach ElevenLabs.")
        }

        response.use {
            if (!it.isSuccessful) throw errorFor(it.code, it.body?.string())
            val stream = it.body?.byteStream() ?: throw ElevenLabsException.Network("Empty response.")
            destination.outputStream().use(stream::copyTo)
        }
        destination
    }

    /** The voices on the account, for the picker in settings. */
    suspend fun voices(): List<ElevenLabsVoice> = withContext(Dispatchers.IO) {
        val key = credentials.apiKey ?: throw ElevenLabsException.MissingApiKey

        val request = Request.Builder()
            .url("${credentials.apiBaseUrl}/v1/voices")
            .header("xi-api-key", key)
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw ElevenLabsException.Network(error.message ?: "Could not reach ElevenLabs.")
        }

        response.use {
            val payload = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw errorFor(it.code, payload)
            json.decodeFromString(VoicesResponse.serializer(), payload).voices.map { voice ->
                ElevenLabsVoice(
                    id = voice.voiceId,
                    name = voice.name.orEmpty().ifBlank { voice.voiceId },
                    description = voice.labels?.values?.joinToString(", ")?.takeIf { l -> l.isNotBlank() },
                )
            }
        }
    }

    private fun errorFor(code: Int, payload: String?): ElevenLabsException = when (code) {
        401, 403 -> ElevenLabsException.Unauthorized
        422 -> ElevenLabsException.Rejected(detail(payload) ?: "ElevenLabs rejected that request.")
        429 -> ElevenLabsException.QuotaExhausted
        else -> ElevenLabsException.Http(code, detail(payload))
    }

    /** ElevenLabs nests its message under `detail`, sometimes as an object and sometimes a string. */
    private fun detail(payload: String?): String? = payload
        ?.substringAfter("\"message\":\"", "")
        ?.substringBefore("\"")
        ?.takeIf { it.isNotBlank() }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 44.1 kHz MP3: the format every Android decoder handles without a codec dance. */
        const val OUTPUT_FORMAT = "mp3_44100_128"
    }
}

@Serializable
private data class SpeechRequest(
    val text: String,
    @SerialName("model_id") val modelId: String,
)

@Serializable
private data class VoicesResponse(val voices: List<VoiceDto> = emptyList())

@Serializable
private data class VoiceDto(
    @SerialName("voice_id") val voiceId: String,
    val name: String? = null,
    val labels: Map<String, String>? = null,
)

/** A voice as the picker needs it. */
data class ElevenLabsVoice(
    val id: String,
    val name: String,
    val description: String? = null,
)

sealed class ElevenLabsException(message: String) : Exception(message) {

    data object MissingApiKey : ElevenLabsException("Add an ElevenLabs API key in Settings.")

    data object Unauthorized : ElevenLabsException("The ElevenLabs key was rejected.")

    data object QuotaExhausted : ElevenLabsException("The ElevenLabs character quota is used up.")

    data class Rejected(val detail: String) : ElevenLabsException(detail)

    data class Network(val detail: String) : ElevenLabsException(detail)

    data class Http(val code: Int, val detail: String?) :
        ElevenLabsException(detail ?: "ElevenLabs returned HTTP $code.")
}
