package dev.klaiber.cirrus.domain.tools.shell

/**
 * What a shell command is allowed to be.
 *
 * The model writes the command, so the answer to "is this safe?" has to be decided before anything
 * runs, by code, from a list that can be read in one sitting. An allow list is the only shape that
 * works: a deny list of dangerous programs is a list of the dangerous programs *somebody thought
 * of*, and a desktop ships hundreds of programs, any of which can be a footgun.
 *
 * Three rules do most of the work, and they compose:
 *
 *  - **Only listed programs run.** Anything that can execute something else on the command's behalf
 *    — `sh`, `xargs`, `awk`, `env`, `find -exec` — is absent or refused by name, because it would
 *    otherwise be a hole straight through the list.
 *  - **No absolute paths.** The shell starts in a scratch directory inside the app's own data
 *    directory, and a command that cannot name `/` cannot reach anything the app can see, let alone
 *    anything it cannot. A handful of read-only informational files are the stated exception.
 *  - **No `..`, no command substitution.** `$(…)` and backticks would smuggle an unlisted program
 *    past the check as text, and `..` would climb out of the workspace.
 *
 * Everything here is pure: no Android, no process, no file system. It carries the tests.
 */
sealed interface CommandVerdict {

    /** Cleared to run. [programs] is one name per pipeline segment, in order. */
    data class Allowed(val programs: List<String>) : CommandVerdict

    /**
     * Refused, with a reason written *for the model* rather than for a log.
     *
     * It is fed straight back as the tool result, so it has to say what would be accepted instead —
     * a model told "not allowed" retries the same thing, and a model told "absolute paths are
     * refused, work relative to the workspace" writes a command that works.
     */
    data class Refused(val reason: String) : CommandVerdict
}

object CommandPolicy {

    /** Programs that only ever read: safe to run against anything reachable. */
    val readOnlyPrograms: Set<String> = setOf(
        // Time
        "date", "cal", "uptime",
        // Looking at files
        "ls", "cat", "head", "tail", "wc", "stat", "find", "du", "df", "file", "readlink",
        // Text. The whole point of the shell here, so the list is generous: every one of these
        // reads its input and writes to stdout, which is the shape the model works in.
        "grep", "egrep", "fgrep", "sed", "sort", "uniq", "cut", "tr", "rev", "tac", "nl", "fold",
        "paste", "comm", "join", "shuf", "diff", "cmp", "basename", "dirname", "printf", "echo",
        "seq", "expr", "xxd", "od", "base64", "md5sum", "sha1sum", "sha256sum", "sha512sum",
        "cksum", "strings",
        // Desktop search and JSON, in the same read-only spirit as grep and sed.
        "rg", "fd", "jq",
        // The machine
        "uname", "free", "nproc", "id", "whoami", "hostname", "printenv", "pwd", "which",
        "ps", "ping",
    )

    /** Programs that write. Harmless only because they cannot name anything outside the workspace. */
    val workspacePrograms: Set<String> = setOf(
        "mkdir", "touch", "cp", "mv", "rm", "rmdir", "tee", "truncate",
    )

    val allowedPrograms: Set<String> = readOnlyPrograms + workspacePrograms

    /**
     * Refused by name, each with the reason the model is told.
     *
     * Every one of these is already excluded by the allow list. They are named anyway because the
     * refusal is the only feedback the model gets: "su is blocked, and there is no root here" ends
     * that line of attempts, whereas "su is not on the allow list" invites a search for a synonym.
     */
    val blockedPrograms: Map<String, String> = mapOf(
        "su" to "there is no root on this machine, and Cirrus would not use it",
        "sudo" to "there is no root on this machine, and Cirrus would not use it",
        "sh" to "starting another shell would route around this whole check",
        "bash" to "starting another shell would route around this whole check",
        "ash" to "starting another shell would route around this whole check",
        "mksh" to "starting another shell would route around this whole check",
        "toybox" to "it is the multi-call binary behind every applet, so it would run anything",
        "busybox" to "it is a multi-call binary, so it would run anything",
        "xargs" to "it runs another program on your behalf, which routes around this check",
        "awk" to "its programs can execute shell commands",
        "env" to "it runs another program on your behalf",
        "nohup" to "it runs another program on your behalf",
        "timeout" to "it runs another program on your behalf",
        "setsid" to "it runs another program on your behalf",
        "sqlite3" to "editing databases behind an app's back corrupts them",
        "dd" to "it writes raw blocks, which is destructive by nature",
        "mount" to "changing what is mounted affects the whole machine",
        "umount" to "changing what is mounted affects the whole machine",
        "chmod" to "permissions on a scratch workspace are not worth changing",
        "chown" to "permissions on a scratch workspace are not worth changing",
        "reboot" to "restarting the machine is never a step in answering a question",
        "kill" to "killing processes belongs to the user and the system",
        "killall" to "killing processes belongs to the user and the system",
        "pkill" to "killing processes belongs to the user and the system",
        "curl" to "use the web_fetch tool, which goes through the app's own HTTP stack",
        "wget" to "use the web_fetch tool, which goes through the app's own HTTP stack",
        "nc" to "raw sockets are not something a chat turn should open",
        "ssh" to "raw sockets are not something a chat turn should open",
        "crontab" to "scheduling belongs to Cirrus's own agents",
    )

    /**
     * The only absolute paths that may be named.
     *
     * All five are world-readable statements of fact about the hardware, and all five are things
     * someone genuinely asks about ("how much RAM does this thing have?"). Nothing under the user's
     * home directory is here: the workspace is where files belong.
     */
    val readableFiles: Set<String> = setOf(
        "/proc/cpuinfo",
        "/proc/meminfo",
        "/proc/version",
        "/proc/uptime",
        "/proc/loadavg",
    )

    /**
     * Sequences that would let an unlisted program in as text rather than as a command word.
     *
     * Checked against the raw string before tokenising, quotes included. `echo '$(id)'` is harmless
     * and is refused anyway — being wrong about a quoted dollar sign costs one odd refusal, being
     * wrong the other way costs the whole policy.
     */
    private val substitutions = listOf("$(", "`", "<(", ">(", "\${")

    /** Segment separators. Each one starts a fresh command word. */
    private val segmentOperators = setOf("|", "||", "&&", ";")

    /** Redirections. The word after one is a file name, not a program. */
    private val redirectOperators = setOf(">", ">>", "<")

    private const val MAX_LENGTH = 500

    fun check(command: String): CommandVerdict {
        val raw = command.trim()
        if (raw.isEmpty()) return CommandVerdict.Refused("the command is empty")
        if (raw.length > MAX_LENGTH) {
            return CommandVerdict.Refused(
                "the command is longer than $MAX_LENGTH characters; break it into steps",
            )
        }
        if (raw.any { it.code < 0x20 || it.code == 0x7F }) {
            return CommandVerdict.Refused(
                "one line only: newlines and control characters are refused, use ; or && to " +
                    "sequence steps",
            )
        }
        substitutions.firstOrNull { raw.contains(it) }?.let {
            return CommandVerdict.Refused(
                "\"$it\" is command substitution, which would run something this check never saw. " +
                    "Run the inner command first and use its output.",
            )
        }

        val tokens = tokenize(raw)
            ?: return CommandVerdict.Refused("unbalanced quotes")

        val segments = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (token in tokens) {
            if (!token.operator) {
                current += token.text
                continue
            }
            when (token.text) {
                in segmentOperators -> {
                    if (current.isEmpty()) {
                        return CommandVerdict.Refused(
                            "\"${token.text}\" has no command in front of it",
                        )
                    }
                    segments += current
                    current = mutableListOf()
                }

                in redirectOperators -> Unit // The target is the next word, and is checked as one.

                "&" -> return CommandVerdict.Refused(
                    "background jobs are refused: a command Cirrus is not waiting for is a " +
                        "command nobody ever cleans up",
                )

                else -> return CommandVerdict.Refused("the \"${token.text}\" operator is refused")
            }
        }
        if (current.isNotEmpty()) segments += current
        if (segments.isEmpty()) return CommandVerdict.Refused("there is no command here")

        for (segment in segments) {
            val program = segment.first()
            when {
                program.startsWith("-") -> return CommandVerdict.Refused(
                    "\"$program\" is an option, not a program",
                )

                program.contains('/') -> return CommandVerdict.Refused(
                    "name programs by name, not by path: write \"${program.substringAfterLast('/')}\"",
                )

                blockedPrograms.containsKey(program) -> return CommandVerdict.Refused(
                    "$program is blocked — ${blockedPrograms.getValue(program)}",
                )

                program !in allowedPrograms -> return CommandVerdict.Refused(
                    "$program is not available. Runnable here: ${allowedPrograms.sorted().joinToString(" ")}",
                )
            }

            segment.forEach { word ->
                pathProblem(word)?.let { return CommandVerdict.Refused(it) }
            }
            argumentProblem(program, segment.drop(1))?.let { return CommandVerdict.Refused(it) }
        }

        return CommandVerdict.Allowed(segments.map { it.first() })
    }

    /** One line for the tool description, so the model knows the shape of the list up front. */
    fun summary(): String = buildString {
        append("Read-only: ")
        append(readOnlyPrograms.sorted().joinToString(" "))
        append(". Writes, inside the workspace only: ")
        append(workspacePrograms.sorted().joinToString(" "))
        append(".")
    }

    /**
     * Whether a word may name what it names.
     *
     * Applied to every word of every segment rather than to the ones that look like paths, because
     * "looks like a path" is exactly the judgement that gets this wrong: a redirection target, an
     * option's value and a bare argument are all just words by the time the shell sees them.
     */
    private fun pathProblem(word: String): String? = when {
        word.startsWith("~") -> "\"$word\": there is no home directory here; the shell already " +
            "starts in the workspace, so use plain relative paths"

        word == ".." || word.startsWith("../") || word.endsWith("/..") || word.contains("/../") ->
            "\"$word\": \"..\" would climb out of the workspace"

        !word.startsWith("/") -> null

        word in readableFiles -> null

        else -> "\"$word\": absolute paths are refused. Everything you write lives in the " +
            "workspace, so use relative paths. The only absolute files readable here are " +
            readableFiles.sorted().joinToString(" ") + "."
    }

    /** The few cases where the program is fine but a particular flag is not. */
    private fun argumentProblem(program: String, args: List<String>): String? = when (program) {
        "find" -> args
            .firstOrNull { it in setOf("-exec", "-execdir", "-ok", "-okdir", "-delete") }
            ?.let { "find $it runs or deletes things as a side effect of searching; find the " +
                "paths first, then act on them in a second command" }

        // Without a count, ping runs until something kills it — which here means burning the whole
        // timeout to learn nothing that the first three replies had not already said.
        "ping" -> "ping needs an explicit count, as in: ping -c 3 example.com"
            .takeIf { args.none { arg -> arg == "-c" || (arg.startsWith("-c") && arg.length > 2) } }

        "rm" -> "rm needs something to remove, named explicitly"
            .takeIf { args.none { arg -> !arg.startsWith("-") } }

        // git is on the read-only list, but only its read-only face is. A subcommand that changes
        // a repository or touches the network is refused by name, because the workspace is the
        // only reachable world and a network write would leave it.
        "git" -> {
            val subcommand = args.firstOrNull { !it.startsWith("-") }
            if (subcommand == null || subcommand !in READ_ONLY_GIT_SUBCOMMANDS) {
                "git needs a read-only subcommand — ${READ_ONLY_GIT_SUBCOMMANDS.sorted().joinToString(", ")}. " +
                    "Commands that change a repository or touch the network are refused."
            } else {
                null
            }
        }

        else -> null
    }

    /**
     * git subcommands that only read. Everything else — add, commit, push, pull, fetch, clone,
     * checkout, reset, merge, rebase, stash, clean, init, remote add, … — changes state or the
     * network and is refused by the check above.
     */
    private val READ_ONLY_GIT_SUBCOMMANDS: Set<String> = setOf(
        "status", "log", "diff", "show", "ls-files", "rev-parse", "branch", "tag", "remote",
        "config", "blame", "grep", "shortlog", "describe", "name-rev", "for-each-ref",
        "count-objects", "fsck", "ls-tree", "cat-file", "check-ignore", "check-attr", "help",
        "version", "archive",
    )

    private data class Token(val text: String, val operator: Boolean)

    private const val OPERATOR_CHARS = "|;&<>"

    /**
     * Splits a command into words and operators, or null if the quoting does not close.
     *
     * Quotes are resolved here rather than left in the words, so `rm "../x"` is caught by the same
     * `..` rule as `rm ../x` — a check that reads the raw string would miss it.
     */
    private fun tokenize(raw: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        val word = StringBuilder()
        var started = false
        var index = 0

        fun flush() {
            if (started) {
                tokens += Token(word.toString(), operator = false)
                word.setLength(0)
                started = false
            }
        }

        while (index < raw.length) {
            val char = raw[index]
            when {
                char == '\'' -> {
                    val close = raw.indexOf('\'', index + 1)
                    if (close < 0) return null
                    word.append(raw, index + 1, close)
                    started = true
                    index = close + 1
                }

                char == '"' -> {
                    var scan = index + 1
                    while (scan < raw.length && raw[scan] != '"') {
                        if (raw[scan] == '\\' && scan + 1 < raw.length) {
                            word.append(raw[scan + 1])
                            scan += 2
                        } else {
                            word.append(raw[scan])
                            scan++
                        }
                    }
                    if (scan >= raw.length) return null
                    started = true
                    index = scan + 1
                }

                char == '\\' && index + 1 < raw.length -> {
                    word.append(raw[index + 1])
                    started = true
                    index += 2
                }

                char.isWhitespace() -> {
                    flush()
                    index++
                }

                char in OPERATOR_CHARS -> {
                    flush()
                    var scan = index
                    while (scan < raw.length && raw[scan] == char) scan++
                    tokens += Token(raw.substring(index, scan), operator = true)
                    index = scan
                }

                else -> {
                    word.append(char)
                    started = true
                    index++
                }
            }
        }
        flush()
        return tokens
    }
}
