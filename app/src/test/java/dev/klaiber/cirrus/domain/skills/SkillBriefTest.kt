package dev.klaiber.cirrus.domain.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines skills cost on every single turn.
 *
 * This is the only part of the feature that is paid for whether or not it is used, so it is the
 * part worth pinning down: it must vanish entirely when nothing is installed, it must name every
 * skill the model is allowed to ask for, and it must not grow without bound as somebody's shelf
 * does.
 */
class SkillBriefTest {

    private fun skill(name: String, description: String = "Does a thing.") = Skill(
        id = "owner/repo/skills/$name/SKILL.md",
        name = name,
        description = description,
        instructions = "Long instructions.",
        origin = SkillOrigin("owner/repo", "skills/$name/SKILL.md", "main"),
    )

    @Test
    fun `nothing installed costs nothing`() {
        assertNull(skillsBrief(emptyList()))
    }

    @Test
    fun `every skill is named, with what it is for`() {
        val brief = skillsBrief(listOf(skill("pdf", "Fills forms."), skill("xlsx")))

        assertTrue(brief!!.contains("pdf — Fills forms."))
        assertTrue(brief.contains("xlsx — Does a thing."))
    }

    @Test
    fun `the model is told how to open one`() {
        val brief = skillsBrief(listOf(skill("pdf")))

        assertTrue("naming the tool is the point of the brief", brief!!.contains("use_skill"))
    }

    @Test
    fun `a long shelf is capped, and says so`() {
        val many = (1..30).map { skill("skill-$it") }

        val brief = skillsBrief(many, limit = 5)!!

        assertEquals(
            "one line of preamble, five skills, one line saying there are more",
            7,
            brief.lines().size,
        )
        assertTrue(brief.contains("and 25 more"))
        assertTrue(brief.contains("list_skills"))
    }

    /** A description written as three paragraphs must not become three lines of the brief. */
    @Test
    fun `a multi-line description is flattened onto its own line`() {
        val brief = skillsBrief(listOf(skill("notes", "One.\n\nTwo.")))!!

        assertTrue(brief.contains("notes — One. Two."))
    }

    @Test
    fun `a very long description is trimmed at a word`() {
        val long = "word ".repeat(200)
        val brief = skillsBrief(listOf(skill("verbose", long)))!!

        assertTrue("the line has to stay a line", brief.lines()[1].length < 260)
        assertTrue(brief.endsWith("…"))
    }
}
