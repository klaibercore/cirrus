package dev.klaiber.cirrus.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The free engine, in place of Android's `TextToSpeech`.
 *
 * There is no cross-platform speech API on the JVM, and every desktop ships its own: macOS has
 * `say`, most Linux desktops have `spd-say` or `espeak`, and Windows has SAPI reachable through
 * PowerShell. This picks whichever is actually present and drives it as a subprocess.
 *
 * Text goes in on **stdin**, never on the command line. An answer read aloud is arbitrary model
 * output, and an answer containing a quote or a `$(` would otherwise be assembled into a command —
 * on Windows doubly so, where the argument is spliced into a PowerShell expression.
 */
class SystemVoice {

    /** Whether this machine has anything that can speak at all. */
    val isAvailable: Boolean get() = command != null

    private val command: Command? by lazy { detect() }

    /**
     * Speaks [text], returning when it has finished or been cancelled.
     *
     * Cancellation destroys the process, which is what makes stopping playback immediate rather
     * than "immediate after the current sentence".
     */
    suspend fun speak(text: String): Unit = withContext(Dispatchers.IO) {
        val engine = command ?: throw SpeechUnavailable
        suspendCancellableCoroutine { continuation ->
            val process = ProcessBuilder(engine.argv)
                .redirectErrorStream(true)
                .start()

            continuation.invokeOnCancellation { process.destroyForcibly() }

            runCatching {
                process.outputStream.use { it.write(engine.encode(text)) }
                // Drained rather than ignored: a process whose output nobody reads blocks once the
                // pipe buffer fills, and `say` on a long answer fills it.
                process.inputStream.use { it.readBytes() }
                process.waitFor()
            }
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    fun stop() {
        // Nothing to hold: each utterance is its own process, and cancelling the coroutine that
        // started it destroys it.
    }

    private class Command(val argv: List<String>, val encode: (String) -> ByteArray)

    private fun detect(): Command? {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ENGLISH)
        return when {
            os.contains("mac") -> executable("/usr/bin/say")?.let { say ->
                // `-f -` reads the text from stdin.
                Command(listOf(say, "-f", "-")) { it.toByteArray() }
            }

            os.contains("win") -> Command(
                listOf(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    // Reads the whole of stdin and speaks it, so nothing is spliced into the script.
                    "Add-Type -AssemblyName System.Speech; " +
                        "\$text = [Console]::In.ReadToEnd(); " +
                        "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak(\$text)",
                ),
            ) { it.toByteArray() }

            else -> onPath("spd-say")?.let { spd ->
                // `-w` waits until it has finished speaking; `-e` reads the text from stdin.
                Command(listOf(spd, "-w", "-e")) { it.toByteArray() }
            } ?: onPath("espeak")?.let { espeak ->
                // espeak reads stdin when given no text argument.
                Command(listOf(espeak, "--stdin")) { it.toByteArray() }
            }
        }
    }

    private fun executable(path: String): String? =
        path.takeIf { java.io.File(it).canExecute() }

    private fun onPath(program: String): String? = PATH_DIRS
        .map { java.io.File(it, program) }
        .firstOrNull { it.canExecute() }
        ?.absolutePath

    private companion object {
        val PATH_DIRS = (System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin")
            .split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
    }
}
