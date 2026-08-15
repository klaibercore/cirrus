package dev.klaiber.cirrus.domain.tools.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.TimeZone

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

    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
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

        val collector = OutputCollector(process.inputStream)
        // A plain daemon thread, not a coroutine, and that is the whole design.
        //
        // The read cannot be interrupted, cancelled or closed out of from another thread — on Linux
        // neither killing the process nor closing the stream ends it, because a shell that forked
        // for a pipeline leaves a grandchild holding the write end of the pipe. Anything structured
        // as a child coroutine would therefore keep this function waiting for it. So the reader is
        // deliberately *not* something we wait on: we wait on the deadline, and if the deadline
        // wins we take what has been collected and abandon the thread. It ends on its own when the
        // command finally does. Daemon, so a stuck one can never hold a JVM open.
        val reader = Thread(collector, "cirrus-shell-reader").apply {
            isDaemon = true
            start()
        }

        try {
            withContext(Dispatchers.IO) { process.outputStream.close() }

            // `join` is interruptible, so this is the one point where both the deadline and a
            // cancelled turn can take effect. Null means the deadline won.
            val exitCode = withTimeoutOrNull(timeoutMs) {
                runInterruptible {
                    reader.join()
                    process.waitFor()
                }
            }

            if (exitCode == null) process.destroyForcibly()
            workspace.trimTo()

            val collected = collector.snapshot()
            return ShellResult(
                // Null rather than a number when the deadline won: a killed process reports 137,
                // and reporting that as the command's own exit code reads as the command failing.
                exitCode = exitCode,
                output = collected.text.trimEnd(),
                outputTruncated = collected.truncated,
                timedOut = exitCode == null,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } finally {
            // Covers cancellation as well as the timeout: a turn the user stopped must not leave a
            // process running against a workspace nobody is watching.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Reads the command's output into a buffer that another thread can read at any time.
     *
     * Synchronised because the reading thread may still be running when the deadline lifts the
     * snapshot out from under it — that is the normal path for a command that timed out, not a
     * race to be designed away, and whatever arrived before the deadline is still worth returning.
     */
    private class OutputCollector(private val stream: java.io.InputStream) : Runnable {

        private val lock = Any()
        private val text = StringBuilder()
        private var truncated = false

        override fun run() {
            val buffer = CharArray(READ_BUFFER)
            // The stream ending, or being closed underneath us, is how this is meant to finish.
            runCatching {
                stream.bufferedReader().use { reader ->
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(lock) {
                            val room = MAX_OUTPUT_CHARS - text.length
                            if (room > 0) text.appendRange(buffer, 0, minOf(read, room))
                            if (read > room) truncated = true
                        }
                    }
                }
            }
        }

        fun snapshot(): Collected = synchronized(lock) { Collected(text.toString(), truncated, 0) }
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
