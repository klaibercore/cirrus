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
    /** Characters dropped from the middle of a long output. Zero unless [outputTruncated]. */
    val omittedChars: Long,
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
 * The cap keeps both ends rather than the first 8,000 characters, and that is worth the ring buffer
 * it costs. Nearly every text job here ends in a summary — `sort | uniq -c`, a `wc` after a
 * pipeline, a diff's final hunk — so a truncation that keeps only the head throws away the line the
 * command was run for, and the model then runs the same thing again with `tail` bolted on.
 *
 * The environment is built rather than inherited. `TZ` is the reason: a `date` that falls back to
 * UTC when `TZ` is unset would quietly tell the time in the wrong one.
 */
class ShellRunner(
    private val workspace: ShellWorkspace,
    /** Resolved once. Falls back to a host shell so the runner is testable off-device. */
    private val shell: String = SHELLS.firstOrNull { File(it).canExecute() } ?: "/bin/sh",
) {

    suspend fun run(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /** Defaults to the workspace root; the tool passes the topic the job belongs to. */
        directory: File = workspace.directory(),
        /**
         * Text fed to the command's stdin.
         *
         * The reason this exists is that the alternative is worse in three separate ways: the data
         * has to be quoted into a command line, the command line is length-limited, and `$` or a
         * backtick anywhere in the text trips the substitution check and the command is refused
         * before it runs. Piping it in makes `wc -w`, `sort | uniq -c`, `sha256sum` and `tee` work
         * on text out of the conversation with no escaping at all.
         */
        input: String? = null,
    ): ShellResult {
        directory.mkdirs()
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
                        put("PATH", DESKTOP_PATH)
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
            feedStdin(process, input)

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
                outputTruncated = collected.omitted > 0,
                omittedChars = collected.omitted,
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
     * Writes the input and closes stdin — on a daemon thread, for the reader's reason in reverse.
     *
     * A pipe write blocks once the kernel buffer is full, and it blocks in the same uninterruptible
     * way a read does. A command that ignores its stdin (`sleep 30`, or a typo) would therefore hold
     * this function past its own deadline if the write were awaited, which is the exact failure the
     * reader is arranged to avoid. Closing stdin is part of the same job: without the EOF, anything
     * that reads to the end of input waits for one forever.
     */
    private suspend fun feedStdin(process: Process, input: String?) {
        if (input.isNullOrEmpty()) {
            withContext(Dispatchers.IO) { runCatching { process.outputStream.close() } }
            return
        }

        val text = input.take(MAX_INPUT_CHARS)
        Thread({
            // A broken pipe is normal here, not a failure: `head -1` has every right to stop
            // reading, and the output it did produce is still the answer.
            runCatching { process.outputStream.bufferedWriter().use { it.write(text) } }
        }, "cirrus-shell-writer").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Reads the command's output into a buffer that another thread can read at any time.
     *
     * Both ends are kept: the head in a builder, the tail in a ring, and whatever fell between them
     * counted. See the class comment for why the tail is worth a ring buffer.
     *
     * Synchronised because the reading thread may still be running when the deadline lifts the
     * snapshot out from under it — that is the normal path for a command that timed out, not a
     * race to be designed away, and whatever arrived before the deadline is still worth returning.
     */
    private class OutputCollector(private val stream: java.io.InputStream) : Runnable {

        private val lock = Any()
        private val head = StringBuilder()
        private val tail = CharArray(TAIL_CHARS)
        private var tailWrite = 0
        private var tailFilled = 0
        private var overflow = 0L

        override fun run() {
            val buffer = CharArray(READ_BUFFER)
            // The stream ending, or being closed underneath us, is how this is meant to finish.
            runCatching {
                stream.bufferedReader().use { reader ->
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(lock) { append(buffer, read) }
                    }
                }
            }
        }

        private fun append(buffer: CharArray, count: Int) {
            var offset = 0
            val room = HEAD_CHARS - head.length
            if (room > 0) {
                offset = minOf(room, count)
                head.appendRange(buffer, 0, offset)
            }
            if (offset >= count) return

            overflow += count - offset
            var remaining = count - offset
            while (remaining > 0) {
                val chunk = minOf(remaining, TAIL_CHARS - tailWrite)
                System.arraycopy(buffer, count - remaining, tail, tailWrite, chunk)
                tailWrite = (tailWrite + chunk) % TAIL_CHARS
                remaining -= chunk
            }
            tailFilled = minOf(TAIL_CHARS, tailFilled + (count - offset))
        }

        fun snapshot(): Collected = synchronized(lock) {
            val whole = head.toString()
            if (overflow == 0L) return Collected(whole, 0L)

            val start = ((tailWrite - tailFilled) % TAIL_CHARS + TAIL_CHARS) % TAIL_CHARS
            val kept = buildString(tailFilled) {
                for (index in 0 until tailFilled) append(tail[(start + index) % TAIL_CHARS])
            }
            // Cut both retained halves at a line break, so neither end starts or stops mid-word.
            // Falls back to the whole half when it holds no line break at all — a single enormous
            // line is still better shown than replaced by an apology.
            val headText = whole.substringBeforeLast('\n', whole)
            val tailText = kept.substringAfter('\n', kept)
            val omitted = (whole.length - headText.length) + (overflow - tailText.length)

            // Zero is reachable: a head that happened to end on a newline, and an overflow small
            // enough to fit the ring whole. Nothing was lost, so nothing should say it was.
            if (omitted <= 0L) return Collected(headText + tailText, 0L)

            Collected(
                text = headText + "\n…[$omitted characters omitted]…\n" + tailText,
                omitted = omitted,
            )
        }
    }

    private data class Collected(val text: String, val omitted: Long)

    companion object {
        /** Desktop first; the Android shell is only ever reached by a JVM test. */
        private val SHELLS = listOf("/bin/sh", "/system/bin/sh")

        /**
         * The PATH the command sees. Deliberately narrow: the workspace is the only reachable
         * world, and the standard desktop locations are enough to find every allowed program.
         */
        private const val DESKTOP_PATH =
            "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin"

        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MAX_TIMEOUT_MS = 60_000L

        /** A tool result competes with the conversation for context, so this is deliberately tight. */
        const val MAX_OUTPUT_CHARS = 8_000

        /** How much stdin is worth accepting. Well past anything a chat turn can usefully hold. */
        const val MAX_INPUT_CHARS = 40_000

        private const val HEAD_CHARS = 6_000
        private const val TAIL_CHARS = 1_800
        private const val READ_BUFFER = 4_096
    }
}
