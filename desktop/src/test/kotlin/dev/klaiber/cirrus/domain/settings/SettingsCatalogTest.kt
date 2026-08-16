package dev.klaiber.cirrus.domain.settings

import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.ui.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue's whole value is that its directions are correct.
 *
 * "It is disabled" is not actionable; "Settings → Tools → Apps" is — but only while that row
 * exists. A path that has drifted from the interface is worse than no path at all, because it sends
 * somebody looking for something that is not there and then makes them doubt the rest of the
 * answer. These strings are read by a model and repeated verbatim to a user, so they are checked
 * here rather than trusted.
 */
class SettingsCatalogTest {

    @Test
    fun `every path points at a section that exists`() {
        val sections = SettingsSection.entries.map { it.title }.toSet()

        SettingSwitch.entries.forEach { switch ->
            val parts = switch.path.split("→").map { it.trim() }
            assertEquals("${switch.id}: paths read \"Settings → Section → Row\"", 3, parts.size)
            assertEquals("${switch.id}: paths start at Settings", "Settings", parts[0])
            assertTrue(
                "${switch.id}: \"${parts[1]}\" is not a settings section — it has been renamed " +
                    "or moved, and this path now sends the user nowhere",
                parts[1] in sections,
            )
            assertTrue("${switch.id}: the row needs a name", parts[2].isNotBlank())
        }
    }

    @Test
    fun `ids are unique and stable-looking`() {
        val ids = SettingSwitch.entries.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[a-z_]+")) })
    }

    @Test
    fun `a switch that is off reports off, and says how to turn it on`() {
        val settings = AppSettings(appControlEnabled = false)

        assertEquals("off", SettingSwitch.APPS.status(settings))
        val remedy = SettingSwitch.APPS.remedy(settings)
        assertNotNull(remedy)
        assertTrue(
            "the remedy has to carry the path, since that is the whole point of it",
            remedy!!.contains(SettingSwitch.APPS.path),
        )
    }

    /**
     * The distinction the whole class exists for.
     *
     * "Off" and "on but not signed in" send the user to the same screen for entirely different
     * reasons, and telling somebody to enable a switch that is already enabled is the fastest way
     * to make them stop believing the answer.
     */
    @Test
    fun `a switch that is on but unconfigured is not reported as off`() {
        val settings = AppSettings(gitHubToolsEnabled = true, hasGitHubToken = false)

        assertEquals("on, but not set up yet", SettingSwitch.GITHUB.status(settings))
        assertTrue(SettingSwitch.GITHUB.isOn(settings))
        assertTrue("it is on but cannot work", !SettingSwitch.GITHUB.isUsable(settings))

        val remedy = SettingSwitch.GITHUB.remedy(settings).orEmpty()
        assertTrue("should mention the token, not ask to enable it again", remedy.contains("token"))
    }

    @Test
    fun `a working switch has nothing to remedy`() {
        val settings = AppSettings(gitHubToolsEnabled = true, hasGitHubToken = true)

        assertEquals("on", SettingSwitch.GITHUB.status(settings))
        assertTrue(SettingSwitch.GITHUB.isUsable(settings))
        assertNull(SettingSwitch.GITHUB.remedy(settings))
    }

    @Test
    fun `the defaults match what the switches claim about them`() {
        val defaults = AppSettings()

        assertTrue("the shell is on by default", SettingSwitch.SHELL.isOn(defaults))
        assertTrue("memory is on by default", SettingSwitch.MEMORY.isOn(defaults))
        assertTrue("notifications are on by default", SettingSwitch.NOTIFICATIONS.isOn(defaults))
        assertTrue("apps act, so they start off", !SettingSwitch.APPS.isOn(defaults))
        assertTrue("writes are never on by default", !SettingSwitch.WRITES.isOn(defaults))
        assertTrue("github needs a token first", !SettingSwitch.GITHUB.isOn(defaults))
    }

    @Test
    fun `every switch explains what it unlocks`() {
        SettingSwitch.entries.forEach { switch ->
            assertTrue("${switch.id} needs a summary a model can read", switch.summary.length > 30)
            assertTrue("${switch.id} needs a title", switch.title.isNotBlank())
        }
    }

    /**
     * The desktop build drops the phone-only switches, and must not merely leave them unreachable:
     * a switch the model can read about is a capability it will offer, and there is no location or
     * Spotify tool here to back one up.
     */
    @Test
    fun `no switch survives that the desktop build cannot honour`() {
        val ids = SettingSwitch.entries.map { it.id }
        assertTrue("location is not a desktop capability", "location" !in ids)
        assertTrue("spotify is not wired on desktop", "spotify" !in ids)
    }
}
