package dev.klaiber.cirrus.domain

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsClient
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.di.ApplicationScope
import dev.klaiber.cirrus.domain.model.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Reads an answer out loud.
 *
 * Lives on the application scope for the same reason a turn does: playback outlasts the screen
 * that started it, and a ViewModel scope dies when you switch threads. One message speaks at a
 * time — starting another stops the first, which is the only behaviour that makes sense when the
 * output is audio.
 *
 * Two engines sit behind the same call. The device engine is free, offline and always there;
 * ElevenLabs sounds enormously better and costs characters, so it is opt-in and needs a key. Long
 * answers are split into chunks either way — every hosted engine has a per-request character
 * limit, and chunking also means audio starts after the first sentence is ready rather than after
 * the whole answer has been synthesised.
 */
@Singleton
class SpeechController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val elevenLabs: ElevenLabsClient,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Which message is speaking, and whether the first audio is still being fetched. */
    data class Speaking(val messageId: String, val isPreparing: Boolean)

    private val _speaking = MutableStateFlow<Speaking?>(null)
    val speaking: StateFlow<Speaking?> = _speaking.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var engine: TextToSpeech? = null

    /** Speaks [text], or stops if [messageId] is already the one speaking. */
    fun toggle(messageId: String, text: String) {
        if (_speaking.value?.messageId == messageId) {
            stop()
            return
        }
        speak(messageId, text)
    }

    fun stop() {
        job?.cancel()
        job = null
        releasePlayer()
        engine?.stop()
        _speaking.value = null
    }

    fun clearError() {
        _errors.value = null
    }

    private fun speak(messageId: String, text: String) {
        stop()
        val spoken = text.trim()
        if (spoken.isEmpty()) return

        _speaking.value = Speaking(messageId, isPreparing = true)
        job = scope.launch {
            val settings = settingsRepository.current.value
            try {
                val useHosted = settings.speechEngine == SpeechEngine.ELEVENLABS &&
                    settings.hasElevenLabsKey
                if (useHosted) {
                    speakWithElevenLabs(spoken, settings.elevenLabsVoiceId, settings.elevenLabsModelId)
                } else {
                    speakWithDevice(spoken)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _errors.value = error.userMessage()
            } finally {
                releasePlayer()
                if (_speaking.value?.messageId == messageId) _speaking.value = null
            }
        }
    }

    /**
     * Synthesises one chunk ahead of the one playing.
     *
     * Without the look-ahead there is a silent gap at every chunk boundary while the next request
     * goes out; with it, the network round trip happens under the previous chunk's audio.
     */
    private suspend fun speakWithElevenLabs(
        text: String,
        voiceId: String,
        modelId: String,
    ) = coroutineScope {
        val voice = voiceId.ifBlank { DEFAULT_VOICE_ID }
        val chunks = chunk(text, HOSTED_CHUNK_LIMIT)
        val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }

        fun synthesize(index: Int): Deferred<File> = async(Dispatchers.IO) {
            elevenLabs.synthesize(
                text = chunks[index],
                voiceId = voice,
                modelId = modelId,
                destination = File(directory, "speech-$index.mp3"),
            )
        }

        var pending: Deferred<File>? = synthesize(0)
        chunks.indices.forEach { index ->
            val file = pending!!.await()
            pending = if (index + 1 < chunks.size) synthesize(index + 1) else null
            _speaking.update { it?.copy(isPreparing = false) }
            play(file)
        }
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine { continuation ->
        val media = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                if (continuation.isActive) continuation.resume(Unit)
            }
            setOnErrorListener { _, _, _ ->
                if (continuation.isActive) continuation.resume(Unit)
                true
            }
            prepare()
            start()
        }
        player = media
        continuation.invokeOnCancellation { releasePlayer() }
    }

    private fun releasePlayer() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    private suspend fun speakWithDevice(text: String) {
        val tts = awaitEngine() ?: throw SpeechUnavailable
        val chunks = chunk(text, DEVICE_CHUNK_LIMIT)
        val finished = Channel<Unit>(Channel.CONFLATED)
        val lastId = "cirrus-${chunks.lastIndex}"

        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == lastId) finished.trySend(Unit)
                }

                // Deprecated, but abstract: the base class still requires an implementation.
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    finished.trySend(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    finished.trySend(Unit)
                }
            },
        )

        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunk, mode, null, "cirrus-$index")
        }
        _speaking.update { it?.copy(isPreparing = false) }

        try {
            finished.receive()
        } finally {
            withContext(Dispatchers.Main.immediate) { tts.stop() }
        }
    }

    private suspend fun awaitEngine(): TextToSpeech? {
        engine?.let { return it }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                var created: TextToSpeech? = null
                created = TextToSpeech(context) { status ->
                    if (!continuation.isActive) return@TextToSpeech
                    if (status == TextToSpeech.SUCCESS) {
                        engine = created
                        continuation.resume(created)
                    } else {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private companion object {

        /**
         * Chunking is at sentence ends, never mid-word: a boundary you can hear is a bug.
         *
         * The hosted limit is well under what the API accepts, because the point is latency —
         * audio should start while the rest is still being made.
         */
        const val HOSTED_CHUNK_LIMIT = 900
        const val DEVICE_CHUNK_LIMIT = 3_500
        const val CACHE_DIRECTORY = "speech"

        /** ElevenLabs' long-standing default voice, used until the user picks one. */
        const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
    }
}

/** Raised when the device has no working speech engine at all. */
object SpeechUnavailable : Exception("This device has no text-to-speech engine installed.")

/**
 * Splits text into speakable chunks no longer than [limit].
 *
 * Sentence boundaries first, then any whitespace, then — for a wall of text with neither — a hard
 * cut, because returning a chunk over the limit would fail the request outright.
 */
internal fun chunk(text: String, limit: Int): List<String> {
    if (text.length <= limit) return listOf(text)

    val chunks = mutableListOf<String>()
    var rest = text.trim()

    while (rest.length > limit) {
        val window = rest.substring(0, limit)
        val split = window.lastIndexOfAny(SENTENCE_ENDS)
            .takeIf { it > limit / 3 }
            ?.plus(1)
            ?: window.lastIndexOf(' ').takeIf { it > 0 }
            ?: limit

        chunks += rest.substring(0, split).trim()
        rest = rest.substring(split).trim()
    }
    if (rest.isNotEmpty()) chunks += rest
    return chunks
}

private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '\n', ';', ':')
