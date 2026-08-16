package dev.klaiber.cirrus.domain.tools.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate, from the outside.
 *
 * A model writes the command, so this class is the only thing standing between "helpful" and
 * "helpful with `rm`". The cases below are organised around the three ways past a naive check —
 * name a program that runs other programs, name a path outside the sandbox, or smuggle either in
 * as text — because those are the three that matter and the ones a later refactor is most likely
 * to weaken without noticing.
 */
class CommandPolicyTest {

    private fun allowed(command: String) =
        assertTrue("expected to allow: $command", CommandPolicy.check(command) is CommandVerdict.Allowed)

    private fun refused(command: String): String {
        val verdict = CommandPolicy.check(command)
        assertTrue("expected to refuse: $command", verdict is CommandVerdict.Refused)
        return (verdict as CommandVerdict.Refused).reason
    }

    // ---- The everyday cases have to keep working ---------------------------------------------

    @Test
    fun `allows the ordinary read-only commands`() {
        allowed("date")
        allowed("ls -la")
        allowed("cat notes.txt")
        allowed("wc -l results.csv")
        allowed("uname -a")
    }

    @Test
    fun `allows pipelines, lists and redirection into the workspace`() {
        allowed("ls -1 | wc -l")
        allowed("cat a.txt | sort | uniq -c | head -20")
        allowed("mkdir out && cp a.txt out/b.txt")
        allowed("printf 'a\\nb\\n' > pair.txt")
        allowed("grep -c foo log.txt; echo done")
    }

    @Test
    fun `allows quoted arguments containing slashes and dollars`() {
        allowed("sed -n '\$p' file.txt")
        allowed("grep 'a/b' file.txt")
        allowed("echo \"hello world\"")
    }

    @Test
    fun `allows the informational files that are named explicitly`() {
        allowed("cat /proc/meminfo")
        allowed("grep MemTotal /proc/meminfo")
    }

    // ---- Programs that would run other programs -------------------------------------------

    @Test
    fun `refuses a second shell`() {
        assertTrue("blocked" in refused("sh -c 'rm -rf /'"))
        assertTrue("blocked" in refused("bash script.sh"))
    }

    @Test
    fun `refuses the applets that execute on your behalf`() {
        refused("xargs rm")
        refused("awk 'BEGIN{system(\"id\")}'")
        refused("env ls")
        refused("timeout 5 ls")
    }

    @Test
    fun `refuses find flags that run or delete as a side effect`() {
        refused("find . -name '*.txt' -delete")
        refused("find . -exec rm {} ;")
    }

    @Test
    fun `refuses the android system tools outright`() {
        refused("pm list packages")
        refused("am start -n com.example/.Main")
        refused("dumpsys battery")
        refused("logcat -d")
        refused("su")
    }

    @Test
    fun `refuses anything simply not on the list`() {
        assertTrue("not available" in refused("nmap 10.0.0.1"))
        assertTrue("not available" in refused("python3 script.py"))
    }

    // ---- Leaving the workspace ---------------------------------------------------------------

    @Test
    fun `refuses absolute paths that are not the few readable files`() {
        assertTrue("absolute" in refused("ls /"))
        assertTrue("absolute" in refused("cat /etc/hosts"))
        assertTrue("absolute" in refused("rm -rf /data/data/dev.klaiber.cirrus"))
        assertTrue("absolute" in refused("cp secret.txt /sdcard/out.txt"))
    }

    @Test
    fun `refuses climbing out with dot dot`() {
        assertTrue(".." in refused("cat ../../etc/hosts"))
        assertTrue(".." in refused("ls .."))
        // Quoting must not get it past the check, which is why quotes are resolved before the test.
        assertTrue(".." in refused("cat \"../secrets\""))
    }

    @Test
    fun `refuses a program named by path`() {
        assertTrue("by name" in refused("/system/bin/ls"))
    }

    @Test
    fun `refuses home-relative paths`() {
        refused("ls ~/Documents")
    }

    // ---- Smuggling ----------------------------------------------------------------------------

    @Test
    fun `refuses command substitution in any form`() {
        assertTrue("substitution" in refused("echo \$(id)"))
        assertTrue("substitution" in refused("echo `id`"))
        refused("cat <(ls)")
    }

    @Test
    fun `refuses background jobs and newlines`() {
        assertTrue("background" in refused("ping -c 4 example.com &"))
        assertTrue("one line" in refused("ls\nrm -rf ."))
    }

    @Test
    fun `refuses unbalanced quotes rather than guessing`() {
        assertTrue("quotes" in refused("echo 'unterminated"))
    }

    @Test
    fun `refuses an empty or headless command`() {
        refused("")
        refused("| wc -l")
    }

    @Test
    fun `refuses a command longer than the limit`() {
        refused("echo " + "a".repeat(600))
    }

    // ---- The narrow argument rules ------------------------------------------------------------

    @Test
    fun `ping must be bounded, because an unbounded one burns the whole timeout`() {
        refused("ping example.com")
        allowed("ping -c 3 example.com")
        allowed("ping -c3 example.com")
    }

    @Test
    fun `rm must name what it removes`() {
        refused("rm -rf")
        allowed("rm -f scratch.txt")
    }

    // ---- What the verdict says ----------------------------------------------------------------

    @Test
    fun `an allowed verdict names every program in the pipeline`() {
        val verdict = CommandPolicy.check("cat a.txt | sort | head -3")
        assertEquals(listOf("cat", "sort", "head"), (verdict as CommandVerdict.Allowed).programs)
    }

    /**
     * The refusal is the model's only feedback, and it is fed straight back as the tool result. A
     * bare "not allowed" gets the same command retried; a reason that names the alternative does
     * not.
     */
    @Test
    fun `a refusal explains itself in a sentence the model can act on`() {
        val reason = refused("cat /etc/hosts")
        assertTrue(reason.length > 40)
        assertTrue("relative" in reason)
    }

    @Test
    fun `nothing that writes is on the read-only list`() {
        val writes = setOf("rm", "mv", "cp", "tee", "mkdir", "truncate", "rmdir", "touch")
        assertTrue(CommandPolicy.readOnlyPrograms.none { it in writes })
    }

    @Test
    fun `no blocked program is also allowed`() {
        assertTrue(CommandPolicy.allowedPrograms.none { CommandPolicy.blockedPrograms.containsKey(it) })
    }
}
