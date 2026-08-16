package dev.klaiber.cirrus.domain.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a `SKILL.md` written by somebody else.
 *
 * Every one of these shapes is one that exists in the wild — the frontmatter spec says two required
 * fields and says nothing about how anyone writes them, so descriptions arrive quoted, folded over
 * three lines, or with a colon in the middle of them. The parser is tolerant on purpose, and these
 * are the cases that decide where tolerance ends.
 */
class SkillDocumentTest {

    @Test
    fun `reads the two fields that matter and keeps the body`() {
        val document = parseSkillDocument(
            """
            ---
            name: web-design-guidelines
            description: Reviews an interface against a long list of rules.
            ---

            # Web design guidelines

            Start by reading the code.
            """.trimIndent(),
        )

        assertEquals("web-design-guidelines", document?.name)
        assertEquals("Reviews an interface against a long list of rules.", document?.description)
        assertTrue(document?.body?.startsWith("# Web design guidelines") == true)
        assertTrue(document?.body?.endsWith("Start by reading the code.") == true)
    }

    @Test
    fun `a folded description becomes one paragraph`() {
        val document = parseSkillDocument(
            """
            ---
            name: pdf
            description: >
              Fills forms, merges files and pulls text out of
              a scanned document.
            ---
            Body.
            """.trimIndent(),
        )

        assertEquals(
            "Fills forms, merges files and pulls text out of a scanned document.",
            document?.description,
        )
    }

    @Test
    fun `a literal description keeps its line breaks`() {
        val document = parseSkillDocument(
            """
            ---
            name: notes
            description: |
              One.
              Two.
            ---
            Body.
            """.trimIndent(),
        )

        assertEquals("One.\nTwo.", document?.description)
    }

    /** A description is a sentence, and sentences have colons in them. */
    @Test
    fun `only the first colon separates the key from the value`() {
        val document = parseSkillDocument(
            """
            ---
            name: xlsx
            description: Spreadsheets: reading them, writing them, and fixing broken ones.
            ---
            Body.
            """.trimIndent(),
        )

        assertEquals(
            "Spreadsheets: reading them, writing them, and fixing broken ones.",
            document?.description,
        )
    }

    @Test
    fun `quoted values are unwrapped`() {
        val document = parseSkillDocument(
            """
            ---
            name: "docx"
            description: 'Word documents.'
            ---
            Body.
            """.trimIndent(),
        )

        assertEquals("docx", document?.name)
        assertEquals("Word documents.", document?.description)
    }

    @Test
    fun `a skill marked internal is flagged rather than dropped here`() {
        val document = parseSkillDocument(
            """
            ---
            name: helper
            description: Called by the other skills.
            metadata:
              internal: true
            ---
            Body.
            """.trimIndent(),
        )

        assertTrue(document?.hidden == true)
    }

    @Test
    fun `nesting under metadata does not swallow the fields above it`() {
        val document = parseSkillDocument(
            """
            ---
            name: keeper
            description: Still here.
            metadata:
              internal: false
              version: 2
            ---
            Body.
            """.trimIndent(),
        )

        assertEquals("keeper", document?.name)
        assertEquals("Still here.", document?.description)
        assertFalse(document?.hidden == true)
    }

    /** The directory name is what the ecosystem installs a skill as, so it is a real fallback. */
    @Test
    fun `a missing name falls back to the directory it came from`() {
        val document = parseSkillDocument(
            """
            ---
            description: No name in here.
            ---
            Body.
            """.trimIndent(),
            fallbackName = "Invoice Totals",
        )

        assertEquals("invoice-totals", document?.name)
    }

    @Test
    fun `names are normalised to the ecosystem's own convention`() {
        val document = parseSkillDocument(
            """
            ---
            name: Web Design Guidelines
            description: x
            ---
            Body.
            """.trimIndent(),
        )

        assertEquals("web-design-guidelines", document?.name)
    }

    @Test
    fun `a file with no frontmatter is not a skill`() {
        assertNull(parseSkillDocument("# Just a readme\n\nNothing to see."))
    }

    @Test
    fun `unterminated frontmatter is not a skill`() {
        assertNull(parseSkillDocument("---\nname: half\ndescription: written\n"))
    }

    @Test
    fun `a file with frontmatter but nothing to call it is not a skill`() {
        assertNull(parseSkillDocument("---\ndescription: anonymous\n---\nBody."))
    }

    @Test
    fun `windows line endings and a byte order mark do not defeat it`() {
        val document = parseSkillDocument("﻿---\r\nname: crlf\r\ndescription: fine\r\n---\r\nBody.")

        assertEquals("crlf", document?.name)
        assertEquals("fine", document?.description)
        assertEquals("Body.", document?.body)
    }

    @Test
    fun `a comment line is not read as a field`() {
        val document = parseSkillDocument(
            """
            ---
            # the name of this skill
            name: commented
            description: fine
            ---
            Body.
            """.trimIndent(),
        )

        assertEquals("commented", document?.name)
        assertEquals("fine", document?.description)
    }
}
