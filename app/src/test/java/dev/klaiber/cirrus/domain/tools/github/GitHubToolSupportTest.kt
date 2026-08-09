package dev.klaiber.cirrus.domain.tools.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolSupportTest {

    private fun args(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    @Test
    fun `repo argument accepts owner slash name`() {
        val target = args("""{"repo":"klaibercore/cirrus"}""").repoOrNull()
        assertEquals("klaibercore", target?.owner)
        assertEquals("cirrus", target?.repo)
        assertEquals("klaibercore/cirrus", target?.fullName)
    }

    @Test
    fun `repo argument tolerates a pasted url`() {
        // Models paste the URL they were given rather than reformatting it.
        val target = args("""{"repo":"https://github.com/klaibercore/cirrus"}""").repoOrNull()
        assertEquals("klaibercore", target?.owner)
        assertEquals("cirrus", target?.repo)

        val dotGit = args("""{"repo":"klaibercore/cirrus.git"}""").repoOrNull()
        assertEquals("cirrus", dotGit?.repo)
    }

    @Test
    fun `repo argument rejects a bare name`() {
        assertNull(args("""{"repo":"cirrus"}""").repoOrNull())
        assertNull(args("""{"repo":""}""").repoOrNull())
        assertNull(args("{}").repoOrNull())
    }

    @Test
    fun `numbers are read whether sent as json numbers or strings`() {
        // Small models routinely quote their numeric arguments.
        assertEquals(42, args("""{"number":42}""").intOrNull("number"))
        assertEquals(42, args("""{"number":"42"}""").intOrNull("number"))
        assertEquals(42, args("""{"number":" 42 "}""").intOrNull("number"))
        assertNull(args("""{"number":"not a number"}""").intOrNull("number"))
        assertNull(args("""{"number":null}""").intOrNull("number"))
        assertNull(args("{}").intOrNull("number"))
    }

    @Test
    fun `booleans survive the same treatment`() {
        assertEquals(true, args("""{"flag":true}""").booleanOrNull("flag"))
        assertEquals(false, args("""{"flag":"false"}""").booleanOrNull("flag"))
        assertNull(args("""{"flag":"maybe"}""").booleanOrNull("flag"))
    }

    @Test
    fun `blank strings read as absent`() {
        assertNull(args("""{"path":"   "}""").stringOrNull("path"))
        assertNull(args("""{"path":null}""").stringOrNull("path"))
        assertEquals("src", args("""{"path":"src"}""").stringOrNull("path"))
    }

    @Test
    fun `function schema has the shape ollama expects`() {
        val schema = functionSchema(
            name = "github_read_file",
            description = "Read a file.",
        ) {
            stringProperty("repo", "Repository.", required = true)
            integerProperty("start_line", "Where to start.")
        }.jsonObject

        assertEquals("function", schema["type"]?.jsonPrimitive?.content)
        val function = schema["function"]!!.jsonObject
        assertEquals("github_read_file", function["name"]?.jsonPrimitive?.content)

        val parameters = function["parameters"]!!.jsonObject
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)

        val properties = parameters["properties"]!!.jsonObject
        assertEquals("string", properties["repo"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("integer", properties["start_line"]!!.jsonObject["type"]?.jsonPrimitive?.content)

        // Only the required ones are listed, or the model will invent values for the rest.
        val required = parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("repo"), required)
    }

    @Test
    fun `runTool turns a failure into readable json rather than throwing`() = kotlinx.coroutines.test.runTest {
        val result = runTool { throw IllegalStateException("boom") }
        assertTrue(result.contains("\"error\""))
        assertTrue(result.contains("boom"))
    }

    @Test
    fun `missingArgument names the argument`() {
        assertTrue(missingArgument("repo").contains("repo"))
    }
}
