package dev.klaiber.cirrus.domain.tools.shell

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The runner, against a real shell.
 *
 * This is the one part of the shell feature that cannot be reasoned about from the source: whether
 * output is actually captured, whether a command that will not stop is actually killed, and whether
 * the working directory is actually the workspace. The host's `/bin/sh` stands in for Android's —
 * different applets, identical mechanics — and everything asserted here is about the mechanics.
 *
 * Skipped rather than failed where no shell exists, so a build on a machine without one reports the
 * truth instead of a red herring.
 */
class ShellRunnerTest {

    private lateinit var workspace: ShellWorkspace
    private lateinit var runner: ShellRunner

    @Before
    fun setUp() {
        assumeTrue("needs a POSIX shell", File("/bin/sh").canExecute())
        val root = Files.createTempDirectory("cirrus-shell").toFile()
        workspace = ShellWorkspace(root)
        runner = ShellRunner(workspace, shell = "/bin/sh")
    }

    @Test
    fun `captures output and the exit code`() = runBlocking {
        val result = runner.run("echo hello")

        assertEquals("hello", result.output)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.outputTruncated)
    }

    /** A failure has to arrive as data. The model needs both halves to decide what to do next. */
    @Test
    fun `a failing command reports its code and its complaint together`() = runBlocking {
        val result = runner.run("cat nothing-here.txt")

        assertTrue(result.exitCode != 0)
        assertTrue("stderr belongs in the output", result.output.isNotEmpty())
    }

    @Test
    fun `runs in the workspace, and writes land there`() = runBlocking {
        runner.run("echo written > note.txt")

        assertTrue(File(workspace.directory(), "note.txt").exists())
        assertEquals(1, workspace.entries().count { !it.isDirectory })
    }

    @Test
    fun `clearing the workspace removes what a command wrote`() = runBlocking {
        runner.run("mkdir -p sub && echo a > sub/a.txt && echo b > b.txt")

        assertEquals(2, workspace.clear())
        assertTrue(workspace.entries().isEmpty())
        assertTrue("the directory itself has to survive", workspace.directory().isDirectory)
    }

    /**
     * The reason a watchdog exists at all: a blocking read on a pipe does not answer to thread
     * interruption, so the only way out is to kill the process and let the stream close.
     *
     * The margins are deliberately wide. The property under test is "a command that would run for
     * thirty seconds does not", and the gap between the two-second deadline and the thirty-second
     * command is the room a loaded CI runner needs to schedule the watchdog. An earlier version cut
     * that to 700ms, which passed on a quiet laptop and failed on a shared runner — a test that
     * measures the scheduler rather than the code is worse than no test, because it teaches you to
     * re-run the build.
     */
    @Test
    fun `a command that will not stop is killed at the deadline`() = runBlocking {
        val started = System.currentTimeMillis()
        val result = runner.run("sleep 30", timeoutMs = 2_000)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("should not have waited out the sleep, took ${elapsed}ms", elapsed < 15_000)
        assertTrue("should be reported as a timeout, not as a clean exit", result.timedOut)
    }

    /**
     * The same, for a command the shell may not have exec'd.
     *
     * `sh -c` execs a lone command and becomes it, so killing the process kills the command. Give
     * it a pipeline and it forks instead, and the grandchildren inherit the write end of the pipe —
     * so killing the shell leaves the read blocked on a pipe nobody is going to close. Which of the
     * two you get varies by shell, which means it varies by platform: this passed on a laptop whose
     * /bin/sh is bash and failed on a runner whose /bin/sh is dash.
     */
    @Test
    fun `a hung pipeline is killed even when the shell forked`() = runBlocking {
        val started = System.currentTimeMillis()
        val result = runner.run("sleep 30 | cat", timeoutMs = 2_000)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("the read must not outlive the deadline, took ${elapsed}ms", elapsed < 15_000)
        assertTrue("should be reported as a timeout", result.timedOut)
    }

    @Test
    fun `output is capped rather than allowed to fill the context window`() = runBlocking {
        val result = runner.run("yes abcdefghij | head -20000", timeoutMs = 20_000)

        assertTrue(result.outputTruncated)
        assertTrue(result.output.length <= ShellRunner.MAX_OUTPUT_CHARS)
    }

    /** Android's `date` answers in UTC unless TZ is set, which would be a quietly wrong clock. */
    @Test
    fun `the environment is built rather than inherited`() = runBlocking {
        val result = runner.run("echo \$TZ; echo \$LANG")

        assertEquals(java.util.TimeZone.getDefault().id, result.output.lines().first().trim())
        assertTrue(result.output.contains("C"))
    }
}
