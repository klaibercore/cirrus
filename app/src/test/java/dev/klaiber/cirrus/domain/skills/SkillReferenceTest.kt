package dev.klaiber.cirrus.domain.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What somebody may type into the install field.
 *
 * The forms are not invented here: they are the ones `npx skills add` accepts, which is the whole
 * point — an instruction written for a laptop ("run npx skills add anthropics/skills") has to work
 * when it is typed into a phone. Anything this rejects is something the user will see an error
 * about, so the rejections matter as much as the parses.
 */
class SkillReferenceTest {

    @Test
    fun `the shorthand everybody uses`() {
        val reference = parseSkillReference("anthropics/skills")

        assertEquals("anthropics", reference?.owner)
        assertEquals("skills", reference?.repo)
        assertNull(reference?.ref)
        assertNull(reference?.path)
    }

    @Test
    fun `a pasted address`() {
        assertEquals(
            SkillReference("vercel-labs", "agent-skills"),
            parseSkillReference("https://github.com/vercel-labs/agent-skills"),
        )
    }

    @Test
    fun `a trailing slash or a dot-git is not a different repository`() {
        assertEquals(
            SkillReference("vercel-labs", "skills"),
            parseSkillReference("https://github.com/vercel-labs/skills.git"),
        )
        assertEquals(
            SkillReference("vercel-labs", "skills"),
            parseSkillReference("github.com/vercel-labs/skills/"),
        )
    }

    @Test
    fun `the ssh form people paste out of habit`() {
        assertEquals(
            SkillReference("anthropics", "skills"),
            parseSkillReference("git@github.com:anthropics/skills.git"),
        )
    }

    /** The form that names one skill inside a library of forty. */
    @Test
    fun `a tree url carries its branch and its path`() {
        val reference =
            parseSkillReference("https://github.com/anthropics/skills/tree/main/skills/pdf")

        assertEquals("anthropics", reference?.owner)
        assertEquals("skills", reference?.repo)
        assertEquals("main", reference?.ref)
        assertEquals("skills/pdf", reference?.path)
    }

    @Test
    fun `a blob url pointing straight at the file works too`() {
        val reference = parseSkillReference(
            "https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md",
        )

        assertEquals("skills/pdf/SKILL.md", reference?.path)
    }

    @Test
    fun `a bare path after the repository is taken as a path`() {
        val reference = parseSkillReference("anthropics/skills/skills/pdf")

        assertEquals("skills/pdf", reference?.path)
        assertNull("no branch was named, so the default one is meant", reference?.ref)
    }

    @Test
    fun `a pinned ref is read off the end`() {
        val reference = parseSkillReference("anthropics/skills@v2")

        assertEquals("v2", reference?.ref)
        assertEquals("skills", reference?.repo)
    }

    @Test
    fun `an owner on its own is not a source`() {
        assertNull(parseSkillReference("anthropics"))
        assertNull(parseSkillReference(""))
        assertNull(parseSkillReference("   "))
    }

    /** Cloning needs git, and there is no git on a phone. Saying so beats failing obscurely. */
    @Test
    fun `hosts that would have to be cloned are refused`() {
        assertNull(parseSkillReference("https://gitlab.com/someone/skills"))
        assertNull(parseSkillReference("./local-skills"))
    }

    @Test
    fun `nothing that is not a repository name gets through`() {
        assertNull(parseSkillReference("anthropics/skills;rm -rf"))
        assertNull(parseSkillReference("../../etc/passwd"))
    }

    @Test
    fun `the label reads back the way it was typed`() {
        assertEquals(
            "anthropics/skills/skills/pdf",
            parseSkillReference("anthropics/skills/skills/pdf")?.label,
        )
        assertEquals("anthropics/skills@v2", parseSkillReference("anthropics/skills@v2")?.label)
    }
}
