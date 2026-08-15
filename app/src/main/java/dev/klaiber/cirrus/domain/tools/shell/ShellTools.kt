package dev.klaiber.cirrus.domain.tools.shell

import dev.klaiber.cirrus.domain.tools.CirrusTool
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.int
import dev.klaiber.cirrus.domain.tools.github.intParam
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.tools.github.stringParam
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A shell, for the parts of everyday work that are a command rather than a question.
 *
 * The interesting design question is not how to run a command — that is ten lines of
 * [ProcessBuilder] — but what to do about the fact that a language model is writing it. The answer
 * here is that the command is *checked before it runs*, against [CommandPolicy], and the check is
 * structural rather than advisory: no unlisted program, no absolute path, no substitution. A model
 * that has been told not to do something might not; a command that cannot name `/` cannot reach it.
 *
 * Two arguments beyond the command itself carry most of the ergonomics, and both were added because
 * of how the models actually use this rather than how it was imagined:
 *
 *  - **`input`** pipes text straight to stdin. Without it, text out of the conversation has to be
 *    quoted into a command line that is length-capped and whose `$` and backticks are refused by the
 *    substitution check — so counting the words in a paragraph became a `printf` puzzle with three
 *    ways to fail before the command ran at all.
 *  - **`topic`** names the job the files belong to, and each topic is its own directory. One flat
 *    scratch directory across a long session becomes `out.txt`, `out2.txt`, `tmp.txt`, and the model
 *    starts reading the wrong one. Because `..` is refused, a topic is also an isolation boundary:
 *    a command working on one job cannot touch another's files even by accident.
 *
 * The description below is the other half, and it is written for the model rather than for a
 * reader. It is what the model has in front of it when it decides whether to call this at all, so
 * the rules that matter most — stay non-destructive, name your topic, clean up — are stated there
 * in the imperative, next to the reason each one exists.
 */
@Singleton
class RunCommandTool @Inject constructor(
    private val runner: ShellRunner,
    private val workspace: ShellWorkspace,
) : CirrusTool {

    override val name: String = "run_command"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Run a shell command on the user's Android phone and read its output. Use " +
            "it for the small mechanical jobs that are quicker to run than to reason about: " +
            "counting, sorting, deduplicating or reformatting text, checksums and encodings, and " +
            "looking at scratch files you wrote earlier in this conversation.\n\n" +
            "PASS TEXT IN, DO NOT QUOTE IT. Put the text you want to work on in \"input\" and it " +
            "arrives on the command's stdin: `wc -w`, `sort | uniq -c | sort -rn`, `sha256sum`, " +
            "`tr a-z A-Z`, `tee notes.txt`. That is the efficient shape here — quoting text into " +
            "the command line is length-limited, and a $ or a backtick in it is refused before " +
            "anything runs.\n\n" +
            "WHERE IT RUNS. Every command starts in a private scratch workspace inside Cirrus's " +
            "own cache, in a directory named by \"topic\". Give one topic per job (\"expenses\", " +
            "\"log-counts\") and reuse it for every command of that job, so its files stay " +
            "together and separate from everything else. That directory is the entire reachable " +
            "world: absolute paths, \"..\", $(…) substitution, backticks and background jobs are " +
            "all refused before anything runs, and so is any program not on the list below. The " +
            "user's photos, messages and other apps are not reachable from here — do not tell " +
            "them otherwise.\n\n" +
            "PROGRAMS. " + CommandPolicy.summary() + " Pipes, &&, ||, ; and redirection into the " +
            "topic all work.\n\n" +
            "BE NON-DESTRUCTIVE. Read before you write, and never delete or overwrite a file you " +
            "did not create yourself in this conversation. Nothing outside the workspace can be " +
            "harmed, which means a destructive command is never necessary here — it is only ever " +
            "a mistake with your own working files.\n\n" +
            "CLEAN UP AFTER YOURSELF. Call clean_workspace with the topic once you have the " +
            "answer, and always before you finish a session in which you wrote files. Topics " +
            "nobody has touched for a while are swept automatically, and the reply tells you when " +
            "that has happened, so never assume a file from an earlier topic is still there.\n\n" +
            "DO NOT use this to ask the date, the time or what day something falls on: " +
            "get_datetime and show_calendar answer those exactly and without a process. Output " +
            "is capped at ${ShellRunner.MAX_OUTPUT_CHARS} characters — the first and last part " +
            "are kept and the middle is dropped — so pipe long results through head, sort or wc " +
            "rather than reading them whole.",
        required = listOf("command"),
    ) {
        stringParam(
            "command",
            "The command line, on one line. Pipes and && are fine; quoting works as in sh.",
        )
        stringParam(
            "input",
            "Text to feed the command's stdin. Use this for any text you are working on rather " +
                "than quoting it into the command. Up to ${ShellRunner.MAX_INPUT_CHARS} characters.",
        )
        stringParam(
            "topic",
            "A short name for the job these files belong to, such as \"expenses\". Reuse the same " +
                "topic for every command of one job. Defaults to \"${ShellWorkspace.DEFAULT_TOPIC}\".",
        )
        intParam(
            "timeout_seconds",
            "How long to wait before the command is killed. Default 10, maximum 60.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val command = arguments.string("command")
            ?: return@shellTool errorJson("missing required argument: command")

        val topic = ShellWorkspace.topicName(arguments.string("topic"))
        // Before the command rather than after it: the point is that this command starts against a
        // tidy workspace, and a sweep that ran afterwards would report a state nobody asked about.
        val swept = workspace.sweep()

        when (val verdict = CommandPolicy.check(command)) {
            is CommandVerdict.Refused -> buildJsonObject {
                put("refused", true)
                put("command", command)
                put("reason", verdict.reason)
            }.toString()

            is CommandVerdict.Allowed -> {
                val timeout = arguments.int("timeout_seconds")
                    ?.let { it * 1_000L }
                    ?.coerceIn(1_000L, ShellRunner.MAX_TIMEOUT_MS)
                    ?: ShellRunner.DEFAULT_TIMEOUT_MS

                val input = arguments.string("input")
                val result = runner.run(
                    command = command,
                    timeoutMs = timeout,
                    directory = workspace.topicDirectory(topic),
                    input = input,
                )

                buildJsonObject {
                    put("command", command)
                    put("topic", topic)
                    put("exit_code", result.exitCode)
                    put("output", result.output)
                    put(
                        "output_lines",
                        if (result.output.isEmpty()) 0 else result.output.count { it == '\n' } + 1,
                    )
                    if (result.output.isBlank() && !result.timedOut) {
                        // A silent success reads as a failure to a model that expected text, and
                        // the retry that follows is always the same command again.
                        put("note", "The command produced no output. For many programs that is success.")
                    }
                    if (input != null && input.length > ShellRunner.MAX_INPUT_CHARS) {
                        put(
                            "input_truncated",
                            "Only the first ${ShellRunner.MAX_INPUT_CHARS} characters of input " +
                                "were sent. Split the text and run the command over each part.",
                        )
                    }
                    if (result.outputTruncated) {
                        put(
                            "truncated",
                            "Output was long: ${result.omittedChars} characters from the middle " +
                                "were dropped, and both ends kept. Narrow the command — head, " +
                                "grep, sort, wc — rather than running it again.",
                        )
                    }
                    if (result.timedOut) {
                        put(
                            "timed_out",
                            "The command was still running after ${timeout / 1000} seconds and " +
                                "was killed. Anything it printed first is above.",
                        )
                    }
                    put("duration_ms", result.durationMs)
                    putTopicFiles(workspace, topic)
                    putSweptTopics(swept)
                }.toString()
            }
        }
    }
}

/**
 * Puts a topic — or the whole workspace — back the way it was found.
 *
 * Offered as a tool rather than done automatically at the end of every turn, because a multi-turn
 * task legitimately writes a file in one turn and reads it in the next. The idle sweep in
 * [RunCommandTool] and the process-start wipe in `CirrusApp` are the backstops for a model that
 * forgets; this is the one that ends a job the moment the job is actually over, which is the only
 * moment anything knows for certain that it is.
 */
@Singleton
class CleanWorkspaceTool @Inject constructor(
    private val workspace: ShellWorkspace,
) : CirrusTool {

    override val name: String = "clean_workspace"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Delete the scratch files run_command created. Pass the topic you were " +
            "working in to clear just that job, or omit it to clear every topic. Call this as the " +
            "last step of any task that wrote files, and ALWAYS before you finish a session in " +
            "which you used run_command — leaving work behind is the one thing you must not do " +
            "here. Nothing outside the workspace is touched, and nothing the user asked you to " +
            "keep should have been written here in the first place: if something matters, put it " +
            "in your answer before you clean up.",
    ) {
        stringParam(
            "topic",
            "The topic to clear. Omit to clear the whole workspace.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val requested = arguments.string("topic")?.takeIf { it.isNotBlank() }
        val topic = requested?.let { ShellWorkspace.topicName(it) }
        val removed = workspace.clear(topic)

        buildJsonObject {
            topic?.let { put("topic", it) }
            put("removed_files", removed)
            put(
                "status",
                when {
                    removed > 0 && topic != null -> "Topic \"$topic\" cleaned."
                    removed > 0 -> "Workspace cleaned."
                    topic != null -> "Topic \"$topic\" was already empty."
                    else -> "The workspace was already empty."
                },
            )
            val left = workspace.topics().filter { it.fileCount > 0 }
            if (left.isNotEmpty()) {
                putJsonArray("topics_still_holding_files") {
                    left.forEach { add(JsonPrimitive(it.name)) }
                }
            }
        }.toString()
    }
}

/**
 * The topic's files, listed with the command's result.
 *
 * A listing rather than a count, because the next command is nearly always about one of these files
 * and the alternative is a whole round trip spent on `ls`. Capped, and the cap is why the total is
 * reported separately: a model told "14 files" and shown ten knows to look, where a model shown ten
 * and told nothing believes it has seen them all.
 */
private fun JsonObjectBuilder.putTopicFiles(workspace: ShellWorkspace, topic: String) {
    val entries = workspace.topicEntries(topic).filter { !it.isDirectory }
    put("file_count", entries.size)
    if (entries.isEmpty()) return

    putJsonArray("files") {
        entries.take(MAX_LISTED_FILES).forEach { entry ->
            add(
                buildJsonObject {
                    put("path", entry.path)
                    put("bytes", entry.sizeBytes)
                },
            )
        }
    }
}

/**
 * What the idle sweep took, when it took anything.
 *
 * Said out loud because the model may be one command away from reading a file that no longer
 * exists, and "log-counts was cleaned up" is something it can act on where a missing file is a
 * puzzle it spends a turn on.
 */
private fun JsonObjectBuilder.putSweptTopics(swept: List<String>) {
    if (swept.isEmpty()) return
    putJsonArray("cleaned_up_topics") { swept.forEach { add(JsonPrimitive(it)) } }
    put(
        "cleanup_note",
        "These topics had been idle and were removed automatically. Their files are gone.",
    )
}

private const val MAX_LISTED_FILES = 10

/**
 * Never throws — except when the turn itself was cancelled.
 *
 * A tool call happens mid-turn: an exception here ends the answer with a stack trace, whereas a
 * JSON error is something the model can read and recover from. Cancellation is the exception to the
 * exception, and has to be rethrown: swallowing it would turn "the user pressed stop" into a tool
 * result, and the turn would carry on as though nothing had happened.
 */
internal inline fun shellTool(body: () -> String): String = try {
    body()
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    errorJson(error.message ?: "The command could not be run.")
}
