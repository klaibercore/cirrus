package dev.klaiber.cirrus.domain.tools.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The workspace, and the two promises it makes about cleaning up.
 *
 * Both are the kind of thing that is easy to write and easy to get subtly wrong — a sweep that
 * takes the topic somebody is working in, a name that resolves to somewhere else — and neither
 * fails loudly when it does. So they are asserted here rather than trusted.
 */
class ShellWorkspaceTest {

    private lateinit var root: File
    private lateinit var workspace: ShellWorkspace

    @Before
    fun setUp() {
        root = Files.createTempDirectory("cirrus-workspace").toFile()
        workspace = ShellWorkspace(root)
    }

    private fun write(topic: String, name: String, text: String = "x"): File =
        File(workspace.topicDirectory(topic), name).apply { writeText(text) }

    @Test
    fun `a topic is its own directory`() {
        write("expenses", "totals.txt")
        write("log-counts", "counts.txt")

        assertEquals(listOf("expenses", "log-counts"), workspace.topics().map { it.name }.sorted())
        assertEquals(listOf("totals.txt"), workspace.topicEntries("expenses").map { it.path })
    }

    /** The point of normalising rather than rejecting: the model said something perfectly clear. */
    @Test
    fun `topic names are normalised into something safe`() {
        assertEquals("invoice-totals-q3", ShellWorkspace.topicName("Invoice Totals (Q3)"))
        assertEquals("scratch", ShellWorkspace.topicName(null))
        assertEquals("scratch", ShellWorkspace.topicName("   "))
        assertEquals("notes", ShellWorkspace.topicName("  notes  "))
    }

    /**
     * A topic name arrives as a tool argument, so it never passes through [CommandPolicy]. It has
     * to be unable to name anywhere else on its own.
     */
    @Test
    fun `a topic name cannot climb out of the workspace`() {
        val escape = ShellWorkspace.topicName("../../etc")

        assertFalse(escape.contains('/'))
        assertFalse(escape.contains(".."))
        assertEquals(root, workspace.topicDirectory("../../etc").parentFile)
    }

    @Test
    fun `clearing one topic leaves the others alone`() {
        write("expenses", "totals.txt")
        write("expenses", "raw.csv")
        write("notes", "todo.txt")

        assertEquals(2, workspace.clear("expenses"))
        assertEquals(listOf("notes"), workspace.topics().map { it.name })
        assertEquals(1, workspace.entries().count { !it.isDirectory })
    }

    @Test
    fun `clearing everything empties the workspace but keeps the directory`() {
        write("expenses", "totals.txt")
        write("notes", "todo.txt")

        assertEquals(2, workspace.clear())
        assertTrue(workspace.topics().isEmpty())
        assertTrue(workspace.directory().isDirectory)
    }

    @Test
    fun `the sweep retires topics nothing has touched, and says which`() {
        val stale = write("old-job", "note.txt")
        write("current-job", "note.txt")
        stale.setLastModified(System.currentTimeMillis() - 2 * ShellWorkspace.IDLE_MS)

        assertEquals(listOf("old-job"), workspace.sweep())
        assertEquals(listOf("current-job"), workspace.topics().map { it.name })
    }

    /**
     * The case idle time cannot answer: a session that opens a fresh topic every few minutes stays
     * inside the window forever, and is the flat scratch directory again with extra steps.
     */
    @Test
    fun `the sweep caps how many live topics there can be`() {
        // All well inside the idle window, so only the cap can be what removes any of them.
        val now = System.currentTimeMillis()
        val count = ShellWorkspace.MAX_TOPICS + 3
        repeat(count) { index ->
            write("job-$index", "note.txt").setLastModified(now - (count - index) * 1_000L)
        }

        val removed = workspace.sweep()

        assertEquals(listOf("job-0", "job-1", "job-2"), removed)
        assertEquals(ShellWorkspace.MAX_TOPICS, workspace.topics().size)
    }

    @Test
    fun `an untouched workspace sweeps to nothing`() {
        write("current-job", "note.txt")

        assertTrue(workspace.sweep().isEmpty())
        assertEquals(1, workspace.topics().size)
    }

    /** Oldest first, so the file the last command wrote is not deleted to make room for itself. */
    @Test
    fun `trimming removes the oldest files until it fits`() {
        val old = write("job", "old.txt", "a".repeat(2_000))
        val new = write("job", "new.txt", "b".repeat(2_000))
        old.setLastModified(1_000L)
        new.setLastModified(System.currentTimeMillis())

        assertEquals(1, workspace.trimTo(maxBytes = 2_500))
        assertFalse(old.exists())
        assertTrue(new.exists())
    }
}
