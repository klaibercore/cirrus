package dev.klaiber.cirrus.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Dictation, hoisted into Compose state.
 *
 * Android's recogniser ends a session at every pause, so continuous dictation means restarting
 * it after each utterance. That restart is bounded: a couple of silent sessions in a row and we
 * stop, rather than holding the microphone open indefinitely because the user walked away.
 *
 * Note this produces *text*, not audio: Ollama's chat API has no field for audio, so a
 * transcript is the only thing a model can actually receive.
 */
@Stable
class VoiceInputState internal constructor(private val context: Context) {

    var isListening by mutableStateOf(false)
        private set

    /** Smoothed 0..1 loudness, for the level meter. */
    var level by mutableFloatStateOf(0f)
        private set

    /** False when the device has no recognition service at all, which is legal on Android. */
    var isAvailable by mutableStateOf(false)
        private set

    /** True when audio is being transcribed on the device rather than by a server. */
    var isOnDevice by mutableStateOf(false)
        private set

    internal var onPartial: (String) -> Unit = {}
    internal var onFinal: (String) -> Unit = {}
    internal var onFailure: (String) -> Unit = {}

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var request: Intent = Intent()

    /** Consecutive sessions that heard nothing; guards the restart loop. */
    private var silentSessions = 0

    /** Set by [stop] so the results of the final session are kept but no restart follows. */
    private var stopRequested = false

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        val recognizer = recognizer ?: run {
            onFailure("This device has no speech recogniser installed.")
            return
        }
        if (isListening) return
        stopRequested = false
        silentSessions = 0
        isListening = true
        recognizer.startListening(request)
    }

    /**
     * Ends dictation.
     *
     * The UI flips immediately rather than waiting for the service to acknowledge, because a
     * recogniser that never calls back would otherwise leave the microphone button stuck on.
     * Whatever the final session already heard still arrives and is appended.
     */
    fun stop() {
        if (!isListening) return
        stopRequested = true
        isListening = false
        level = 0f
        runCatching { recognizer?.stopListening() }
    }

    fun toggle() {
        if (isListening) stop() else start()
    }

    internal fun attach(preferOnDevice: Boolean) {
        release()
        recognizer = createRecognizer(preferOnDevice)?.apply { setRecognitionListener(listener) }
        isAvailable = recognizer != null
        request = buildRequest()
    }

    internal fun release() {
        handler.removeCallbacksAndMessages(null)
        isListening = false
        level = 0f
        recognizer?.let { existing ->
            runCatching { existing.cancel() }
            runCatching { existing.destroy() }
        }
        recognizer = null
    }

    private fun createRecognizer(preferOnDevice: Boolean): SpeechRecognizer? {
        if (preferOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            isOnDevice = true
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        isOnDevice = false
        return if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    private fun buildRequest(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        // Some OEM services refuse to start without knowing who is asking.
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (isOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /** Restarts on the main looper: restarting from inside a callback trips some services. */
    private fun restart() {
        if (!isListening) return
        handler.post {
            if (isListening) runCatching { recognizer?.startListening(request) }
        }
    }

    private fun finish() {
        isListening = false
        stopRequested = false
        level = 0f
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() {
            silentSessions = 0
        }

        /** The service reports roughly -2 dB (silence) to 10 dB (loud). */
        override fun onRmsChanged(rmsdB: Float) {
            level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            level = 0f
        }

        override fun onError(error: Int) {
            level = 0f
            if (stopRequested) {
                finish()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    silentSessions++
                    if (silentSessions >= MAX_SILENT_SESSIONS) finish() else restart()
                }

                else -> {
                    onFailure(describe(error))
                    finish()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            results.firstTranscript()?.let(onFinal)
            if (stopRequested) finish() else restart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstTranscript()?.let(onPartial)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle?.firstTranscript(): String? = this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Could not read the microphone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Cirrus needs microphone access to dictate."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The recogniser could not reach the network. Install an offline language pack to " +
                "dictate without a connection."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recogniser is busy. Try again."
        SpeechRecognizer.ERROR_SERVER -> "The speech service reported an error."
        SpeechRecognizer.ERROR_CLIENT -> "Dictation stopped unexpectedly."
        else -> "Dictation failed."
    }

    private companion object {
        const val MAX_SILENT_SESSIONS = 2
    }
}

/**
 * Creates a [VoiceInputState] bound to the current composition.
 *
 * The recogniser is rebuilt when [preferOnDevice] changes and destroyed on dispose, so leaving
 * the chat screen always releases the microphone.
 */
@Composable
fun rememberVoiceInput(
    preferOnDevice: Boolean,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onFailure: (String) -> Unit,
): VoiceInputState {
    val context = LocalContext.current
    val state = remember(context) { VoiceInputState(context) }

    // Re-read the callbacks every recomposition; the listener outlives any single one of them.
    SideEffect {
        state.onPartial = onPartial
        state.onFinal = onFinal
        state.onFailure = onFailure
    }

    DisposableEffect(state, preferOnDevice) {
        state.attach(preferOnDevice)
        onDispose { state.release() }
    }

    return state
}
