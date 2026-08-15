package dev.klaiber.cirrus.domain.tools.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/** What one command produced. [exitCode] is null when nothing ever started. */
data class ShellResult(
    val exitCode: Int?,
    val output: String,
    val outputTruncated: Boolean,
    val timedOut: Boolean,
    val durationMs: Long,
)

/**
 * Runs a checked command in the workspace and collects what it says.
 *
 * Three things have to be true of anything spawned from a chat turn, and each one is a deliberate
 * choice below rather than a default:
 *
 *  - **It must stop.** A command that never returns would otherwise hold the turn open until the
 *    user gives up. A watchdog kills the process at the deadline, and killing it is what unblocks
 *    the read — interrupting the reading thread does not, because a blocking read on a pipe ignores
 *    interruption on Linux.
 *  - **It must stop when the turn does.** The watchdog kills the process in its `finally`, so
 *    cancelling the coroutine — the user pressed stop, the thread was switched — takes the process
 *    with it instead of leaving it running against a workspace nobody is watching.
 *  - **It must not bury the answer.** Output is capped, because a tool result is fed straight back
 *    into the context window and `cat` on the wrong file would spend all of it.
 *
 * The environment is built rather than inherited. `TZ` is the reason: Android's `date` falls back to
 * UTC when `TZ` is unset, so a tool whose entire job is telling the time would have quietly told it
 * in the wrong one.
 */
class ShellRunner(
    private val workspace: ShellWorkspace,
    /** Resolved once. Falls back to a host shell so the runner is testable off-device. */
    private val shell: String = SHELLS.firstOrNull { File(it).canExecute() } ?: "/system/bin/sh",
) {

    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        coroutineScope {
            val directory = workspace.directory()
            val startedAt = System.currentTimeMillis()

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(shell, "-c", command)
                    .directory(directory)
                    // One stream: interleaved as the command wrote it, which is how a person reading
                    // a terminal sees it, and how the model needs to see a message about a failure
                    // sitting next to the output that failed.
                    .redirectErrorStream(true)
                    .apply {
                        environment().apply {
                            clear()
                            put("PATH", "/system/bin:/system/xbin:/usr/bin:/bin")
                            put("HOME", directory.absolutePath)
                            put("TMPDIR", directory.absolutePath)
                            put("PWD", directory.absolutePath)
                            put("TZ", TimeZone.getDefault().id)
                            put("LANG", "C")
                            put("LC_ALL", "C")
                            put("TERM", "dumb")
                        }
                    }
                    .start()
            }

            val expired = AtomicBoolean(false)
            val stdout = process.inputStream
            val watchdog = launch(Dispatchers.IO) {
                try {
                    delay(timeoutMs)
                    expired.set(true)
                } finally {
                    // Runs on cancellation too, which is what makes a stopped turn kill the process.
                    if (process.isAlive) process.destroyForcibly()
                    // Killing the shell is not always enough. `sh -c` is free to fork rather than
                    // exec — which one you get depends on the shell and on the shape of the command,
                    // and a pipeline is the case most likely to fork — and a surviving grandchild
                    // inherits the write end of this pipe. The read would then block past the
                    // deadline the watchdog exists to enforce. Closing the stream from here ends
                    // the read on any platform, whoever is still holding the other end.
                    runCatching { stdout.close() }
                }
            }

            try {
                val collected = withContext(Dispatchers.IO) {
                    process.outputStream.close()
                    val text = StringBuilder()
                    var truncated = false
                    // An IOException here is the watchdog closing the stream underneath the read,
                    // which is a deadline rather than a failure: whatever was collected before it
                    // is still the command's output, and is still worth returning.
                    runCatching {
                        stdout.bufferedReader().use { reader ->
                            val buffer = CharArray(READ_BUFFER)
                            while (true) {
                                val read = reader.read(buffer)
                                if (read < 0) break
                                val room = MAX_OUTPUT_CHARS - text.length
                                if (room > 0) {
                                    text.appendRange(buffer, 0, minOf(read, room))
                                }
                                if (read > room) truncated = true
                            }
                        }
                    }
                    Collected(text.toString(), truncated, process.waitFor())
                }

                workspace.trimTo()

                ShellResult(
                    // A killed process reports 137/143; saying so as an exit code alone would read
                    // as the command's own failure, so the timeout flag carries the explanation.
                    exitCode = collected.exitCode,
                    output = collected.text.trimEnd(),
                    outputTruncated = collected.truncated,
                    timedOut = expired.get(),
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            } finally {
                watchdog.cancel()
            }
        }

    private data class Collected(val text: String, val truncated: Boolean, val exitCode: Int)

    companion object {
        /** Android first; the host shells are only ever reached by a JVM test. */
        private val SHELLS = listOf("/system/bin/sh", "/bin/sh")

        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MAX_TIMEOUT_MS = 60_000L

        /** A tool result competes with the conversation for context, so this is deliberately tight. */
        const val MAX_OUTPUT_CHARS = 8_000
        private const val READ_BUFFER = 4_096
    }
}
