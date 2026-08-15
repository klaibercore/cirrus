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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * The description below is the other half, and it is written for the model rather than for a
 * reader. It is what the model has in front of it when it decides whether to call this at all, so
 * the two rules that matter most — stay non-destructive, and clean up afterwards — are stated
 * there in the imperative, in capitals, next to the reason each one exists.
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
            "counting, sorting or reformatting text, checksums and encodings, arithmetic on " +
            "dates, and looking at scratch files you wrote earlier in this conversation.\n\n" +
            "WHERE IT RUNS. Every command starts in a private scratch workspace inside Cirrus's " +
            "own cache. That directory is the entire reachable world: absolute paths, \"..\", " +
            "$(…) substitution, backticks and background jobs are all refused before anything " +
            "runs, and so is any program not on the list below. The user's photos, messages and " +
            "other apps are not reachable from here — do not tell them otherwise.\n\n" +
            "PROGRAMS. " + CommandPolicy.summary() + " Pipes, &&, ||, ; and redirection into the " +
            "workspace all work.\n\n" +
            "BE NON-DESTRUCTIVE. Read before you write, and never delete or overwrite a file you " +
            "did not create yourself in this conversation. Nothing outside the workspace can be " +
            "harmed, which means a destructive command is never necessary here — it is only ever " +
            "a mistake with your own working files.\n\n" +
            "CLEAN UP AFTER YOURSELF. If you created files, call clean_workspace once you have " +
            "the answer, and always before you finish a session in which you used this tool. " +
            "Leaving scratch files behind for the next conversation to trip over is the failure " +
            "mode this workspace exists to avoid.\n\n" +
            "DO NOT use this to ask the date, the time or what day something falls on: " +
            "get_datetime and show_calendar answer those exactly and without a process. Output " +
            "is capped at ${ShellRunner.MAX_OUTPUT_CHARS} characters, so pipe long results " +
            "through head or wc rather than reading them whole.",
        required = listOf("command"),
    ) {
        stringParam(
            "command",
            "The command line, on one line. Pipes and && are fine; quoting works as in sh.",
        )
        intParam(
            "timeout_seconds",
            "How long to wait before the command is killed. Default 10, maximum 60.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val command = arguments.string("command")
            ?: return@shellTool errorJson("missing required argument: command")

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

                val result = runner.run(command, timeout)
                buildJsonObject {
                    put("command", command)
                    put("exit_code", result.exitCode)
                    put("output", result.output)
                    if (result.output.isBlank() && !result.timedOut) {
                        // A silent success reads as a failure to a model that expected text, and
                        // the retry that follows is always the same command again.
                        put("note", "The command produced no output. For many programs that is success.")
                    }
                    if (result.outputTruncated) {
                        put(
                            "truncated",
                            "Output was cut at ${ShellRunner.MAX_OUTPUT_CHARS} characters. " +
                                "Narrow the command rather than running it again.",
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
                    put("workspace_files", workspace.entries().size)
                }.toString()
            }
        }
    }
}

/**
 * Puts the workspace back the way it was found.
 *
 * Offered as a tool rather than done automatically at the end of every turn, because a multi-turn
 * task legitimately writes a file in one turn and reads it in the next. The process-start wipe in
 * `CirrusApp` is the backstop for a model that forgets; this is the one that keeps a long session
 * tidy while it is still going.
 */
@Singleton
class CleanWorkspaceTool @Inject constructor(
    private val workspace: ShellWorkspace,
) : CirrusTool {

    override val name: String = "clean_workspace"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Delete every file run_command has created in the scratch workspace. Call " +
            "this as the last step of any task that wrote files, and ALWAYS before you finish a " +
            "session in which you used run_command — leaving work behind is the one thing you " +
            "must not do here. Nothing outside the workspace is touched, and nothing the user " +
            "asked you to keep should have been written here in the first place: if something " +
            "matters, put it in your answer before you clean up.",
    ) {}

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val removed = workspace.clear()
        buildJsonObject {
            put("removed_files", removed)
            put(
                "status",
                if (removed == 0) "The workspace was already empty." else "Workspace cleaned.",
            )
        }.toString()
    }
}

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
