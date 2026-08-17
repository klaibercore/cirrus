package dev.klaiber.cirrus.domain.tools.shell

import java.io.File

/**
 * The one directory a command may write to, divided into topics.
 *
 * Every command runs with a topic directory as its working directory, and [CommandPolicy] refuses
 * absolute paths and `..`, so "the topic" and "everywhere this command can reach" are the same
 * place. That is what makes the write-capable programs — `rm`, `mv`, `tee` — safe to offer at all:
 * the worst a mistake can do is destroy scratch files that were never meant to outlive the
 * conversation, and it cannot even reach the ones belonging to a different job.
 *
 * Topics exist because the models use this constantly, and a single flat scratch directory turns
 * into a pile of `out.txt`, `out2.txt`, `tmp.txt` within one long session — at which point the
 * model starts reading the wrong file, or refuses to overwrite its own. A topic is a name for the
 * job in hand (`invoice-totals`, `log-counts`); files inside one belong together, and cleaning up
 * is a decision about a job rather than about a filename.
 *
 * It lives under the app's data directory on purpose. Work nobody asked to keep belongs in a
 * scratch space, and keeping it out of the user's documents is the point.
 *
 * Cleaning up is not left to good intentions, and that is deliberate at three levels: [clear] runs
 * when the process starts, so a session never inherits the last one's mess; [sweep] runs before
 * every command and retires topics nothing has touched for a while; and [trimTo] caps the total so
 * a runaway `seq` cannot fill the disk. The model is *also* told to tidy up, but a rule the model
 * has to remember at the end of a session is the one rule it will not remember.
 */
class ShellWorkspace(private val root: File) {

    /** Creates the directory if it is not there, and hands it over. */
    fun directory(): File = root.apply { mkdirs() }

    /** Where files land, as the model should refer to it when explaining itself to the user. */
    val path: String get() = root.absolutePath

    /**
     * The directory for one topic, created if this is its first command.
     *
     * The name is normalised rather than rejected. A model that asks for "Invoice Totals (Q3)" has
     * said something perfectly clear about which job it means, and answering that with an error
     * spends a round trip teaching it a naming convention instead of doing the work.
     */
    fun topicDirectory(name: String?): File =
        File(root, topicName(name)).apply { mkdirs() }

    /** Every topic that currently has a directory, most recently touched first. */
    fun topics(): List<Topic> = (root.listFiles()?.asList() ?: emptyList())
        .filter { it.isDirectory }
        .map { directory ->
            val files = directory.walkTopDown().filter { it.isFile }.toList()
            Topic(
                name = directory.name,
                fileCount = files.size,
                sizeBytes = files.sumOf { it.length() },
                // The directory's own timestamp is the fallback: an empty topic was still touched
                // when it was made, and treating it as timeless would sweep it on the next command.
                modifiedAt = files.maxOfOrNull { it.lastModified() } ?: directory.lastModified(),
            )
        }
        .sortedByDescending { it.modifiedAt }

    /** Every file currently in the workspace, deepest last, with sizes. */
    fun entries(): List<Entry> = root
        .walkTopDown()
        .filter { it != root }
        .map { Entry(it.toRelativeString(root), it.isDirectory, it.length(), it.lastModified()) }
        .sortedBy { it.path }
        .toList()

    /** Files in one topic, relative to that topic, so the model sees the names it wrote. */
    fun topicEntries(topic: String?): List<Entry> {
        val directory = File(root, topicName(topic))
        if (!directory.isDirectory) return emptyList()
        return directory
            .walkTopDown()
            .filter { it != directory }
            .map {
                Entry(it.toRelativeString(directory), it.isDirectory, it.length(), it.lastModified())
            }
            .sortedBy { it.path }
            .toList()
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Empties the workspace, or one topic of it, and reports how many files went.
     *
     * The directory itself is recreated rather than left missing: a working directory that does not
     * exist makes the *next* command fail with an error about the shell rather than about itself.
     */
    fun clear(topic: String? = null): Int {
        val target = if (topic == null) root else File(root, topicName(topic))
        if (!target.exists()) return 0
        val removed = target.walkTopDown().count { it != target && it.isFile }
        target.deleteRecursively()
        if (target == root) root.mkdirs()
        return removed
    }

    /**
     * Retires topics nobody is working on any more, and reports which ones went.
     *
     * Two rules, and the second is the one that matters. Idle time answers the ordinary case: a job
     * finished half an hour ago is finished, whether or not anything said so. The count cap answers
     * the case idle time cannot — a session that opens a fresh topic every couple of minutes stays
     * under the idle window forever while accumulating twenty directories, which is the flat scratch
     * directory again with extra steps.
     *
     * Reporting rather than doing it silently is deliberate: the model may be about to read a file
     * from a topic that has just been swept, and "invoice-totals was cleaned up" is something it
     * can act on, where a file that has quietly stopped existing is a puzzle it will spend a turn on.
     */
    fun sweep(
        idleMs: Long = IDLE_MS,
        maxTopics: Int = MAX_TOPICS,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val topics = topics()
        val stale = topics.filter { now - it.modifiedAt > idleMs }
        // Newest first, so the ones over the cap are the oldest survivors.
        val overCap = (topics - stale.toSet()).drop(maxTopics)

        return (stale + overCap)
            .filter { File(root, it.name).deleteRecursively() }
            .map { it.name }
            .sorted()
    }

    /**
     * Deletes oldest-first until the workspace fits in [maxBytes].
     *
     * Oldest rather than largest: the one big file a command has just written is usually the point
     * of the command, and deleting it to make room for itself is the one behaviour that would be
     * worse than doing nothing.
     */
    fun trimTo(maxBytes: Long = MAX_BYTES): Int {
        var used = usedBytes()
        if (used <= maxBytes) return 0

        var removed = 0
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (used <= maxBytes) return@forEach
                val size = file.length()
                if (file.delete()) {
                    used -= size
                    removed++
                }
            }
        return removed
    }

    data class Entry(
        val path: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val modifiedAt: Long,
    )

    /** One job's worth of scratch files. */
    data class Topic(
        val name: String,
        val fileCount: Int,
        val sizeBytes: Long,
        val modifiedAt: Long,
    )

    companion object {
        /** Generous for text, small enough that a mistake is not a storage incident. */
        const val MAX_BYTES: Long = 16L * 1024 * 1024

        /** Where a command lands when it did not say which job it belongs to. */
        const val DEFAULT_TOPIC = "scratch"

        /** Long enough to survive a conversation that wandered off and came back. */
        const val IDLE_MS: Long = 45L * 60 * 1000

        /** Past this many live topics, the oldest is not a job in hand any more. */
        const val MAX_TOPICS = 8

        private const val MAX_TOPIC_LENGTH = 32

        /**
         * A topic name that is safe as a directory name, and still recognisable as what was asked
         * for.
         *
         * Lowercase ASCII, digits and single dashes, and nothing else — which incidentally disposes
         * of `..`, `/`, leading dots and every other way a name could mean somewhere else. The
         * policy would refuse those in a command anyway, but a topic arrives as a tool argument
         * rather than as a command word, so it never passes that check.
         */
        fun topicName(raw: String?): String {
            val slug = buildString {
                raw.orEmpty().trim().lowercase().forEach { char ->
                    when {
                        char in 'a'..'z' || char in '0'..'9' -> append(char)
                        endsWith('-') -> Unit
                        isNotEmpty() -> append('-')
                    }
                }
            }.trim('-').take(MAX_TOPIC_LENGTH).trim('-')

            return slug.ifEmpty { DEFAULT_TOPIC }
        }
    }
}
