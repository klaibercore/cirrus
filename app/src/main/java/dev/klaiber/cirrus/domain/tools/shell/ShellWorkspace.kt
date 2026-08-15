package dev.klaiber.cirrus.domain.tools.shell

import java.io.File

/**
 * The one directory a command may write to.
 *
 * Every command runs with this as its working directory, and [CommandPolicy] refuses absolute paths
 * and `..`, so "the workspace" and "everywhere a command can reach" are the same place. That is what
 * makes the write-capable programs — `rm`, `mv`, `tee` — safe to offer at all: the worst a mistake
 * can do is destroy scratch files that were never meant to outlive the conversation.
 *
 * It lives under the cache directory on purpose. Android is allowed to reclaim it under storage
 * pressure, which is the correct fate for work nobody asked to keep, and it is excluded from backup
 * for free.
 *
 * Cleaning up is not left to good intentions. [clear] runs when the process starts, so a session
 * never inherits the last one's mess; the model is told to call it when it has finished a task; and
 * [trimTo] caps the total so a runaway `seq` cannot fill the phone.
 */
class ShellWorkspace(private val root: File) {

    /** Creates the directory if it is not there, and hands it over. */
    fun directory(): File = root.apply { mkdirs() }

    /** Where files land, as the model should refer to it when explaining itself to the user. */
    val path: String get() = root.absolutePath

    /** Every file currently in the workspace, deepest last, with sizes. */
    fun entries(): List<Entry> = root
        .walkTopDown()
        .filter { it != root }
        .map { Entry(it.toRelativeString(root), it.isDirectory, it.length(), it.lastModified()) }
        .sortedBy { it.path }
        .toList()

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Empties the workspace and reports how many files went.
     *
     * The directory itself is recreated rather than left missing: a working directory that does not
     * exist makes the *next* command fail with an error about the shell rather than about itself.
     */
    fun clear(): Int {
        val removed = root.walkTopDown().count { it != root && it.isFile }
        root.deleteRecursively()
        root.mkdirs()
        return removed
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

    companion object {
        /** Generous for text, small enough that a mistake is not a storage incident. */
        const val MAX_BYTES: Long = 16L * 1024 * 1024
    }
}
