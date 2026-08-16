package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsClient
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Reads an answer out loud.
 *
 * Lives on the application scope for the same reason a turn does: playback outlasts the screen
 * that started it, and a ViewModel scope dies when you switch threads. One message speaks at a
 * time — starting another stops the first, which is the only behaviour that makes sense when the
 * output is audio.
 *
 * Two engines sit behind the same call. The system voice is free, offline and — unlike on Android,
 * where there is always an engine — only there if the desktop happens to ship one; ElevenLabs
 * sounds enormously better and costs characters, so it is opt-in and needs a key. Long answers are
 * split into chunks either way — every hosted engine has a per-request character limit, and
 * chunking also means audio starts after the first sentence is ready rather than after the whole
 * answer has been synthesised.
 */
@Singleton
class SpeechController @Inject constructor(
    private val cacheDir: File,
    private val elevenLabs: ElevenLabsClient,
    private val systemVoice: SystemVoice,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    /** Which message is speaking, and whether the first audio is still being fetched. */
    data class Speaking(val messageId: String, val isPreparing: Boolean)

    private val _speaking = MutableStateFlow<Speaking?>(null)
    val speaking: StateFlow<Speaking?> = _speaking.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    private var job: Job? = null

    /** The open audio line, so [stop] can cut playback mid-buffer rather than at the next chunk. */
    @Volatile
    private var line: SourceDataLine? = null

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
        systemVoice.stop()
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
        val directory = File(cacheDir, CACHE_DIRECTORY).apply { mkdirs() }

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

    /**
     * Plays one chunk of raw PCM.
     *
     * The client asks ElevenLabs for PCM rather than MP3 precisely so this can be
     * `javax.sound.sampled` and nothing else — the JVM decodes no MP3, and the format is known and
     * fixed rather than read from a header.
     *
     * Written in blocks off the calling dispatcher: `SourceDataLine.write` blocks until the buffer
     * has room, which is what paces playback, and the loop re-checks the coroutine each time round
     * so stopping does not wait out a whole chunk.
     */
    private suspend fun play(file: File) = withContext(Dispatchers.IO) {
        val format = AudioFormat(
            ElevenLabsClient.SAMPLE_RATE,
            ElevenLabsClient.SAMPLE_BITS,
            ElevenLabsClient.CHANNELS,
            true,
            false,
        )
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) throw SpeechUnavailable

        val open = (AudioSystem.getLine(info) as SourceDataLine).apply {
            open(format)
            start()
        }
        line = open
        try {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(PLAYBACK_BUFFER_BYTES)
                while (isActive) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    open.write(buffer, 0, read)
                }
            }
            if (isActive) open.drain()
        } finally {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        line?.runCatching {
            // Flushed before closing: without it, close() waits for whatever is already buffered,
            // and stopping playback would keep speaking for another second.
            stop()
            flush()
            close()
        }
        line = null
    }

    /**
     * The system voice, one chunk at a time.
     *
     * Android queues every chunk into one engine and waits for the last utterance to report done.
     * Here each chunk is its own subprocess that exits when it has finished speaking, so awaiting
     * them in order *is* the queue, and there is no progress listener to arrange.
     */
    private suspend fun speakWithDevice(text: String) {
        if (!systemVoice.isAvailable) throw SpeechUnavailable
        val chunks = chunk(text, DEVICE_CHUNK_LIMIT)
        _speaking.update { it?.copy(isPreparing = false) }
        chunks.forEach { systemVoice.speak(it) }
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

        /** Small enough that stopping is heard immediately, large enough not to starve the line. */
        const val PLAYBACK_BUFFER_BYTES = 8 * 1024

        /** ElevenLabs' long-standing default voice, used until the user picks one. */
        const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
    }
}

/**
 * Raised when the machine has no working speech engine at all.
 *
 * More likely here than on Android, where there is always an engine to fall back to: a Linux
 * desktop with neither `spd-say` nor `espeak` installed has nothing, and the honest answer is to
 * say so rather than to fail silently.
 */
object SpeechUnavailable : Exception(
    "This computer has no text-to-speech engine. Install one, or add an ElevenLabs key in Settings.",
)

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
