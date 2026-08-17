package dev.klaiber.cirrus.domain.tools.github

import dev.klaiber.cirrus.data.remote.github.GitHubException
import kotlinx.coroutines.test.runTest
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
        val target = args("""{"repo":"klaibercore/cirrus"}""").repoRef().getOrNull()
        assertEquals("klaibercore", target?.owner)
        assertEquals("cirrus", target?.name)
        assertEquals("klaibercore/cirrus", target?.toString())
    }

    @Test
    fun `repo argument tolerates a pasted url`() {
        // Models paste the URL they were given rather than reformatting it.
        val target = args("""{"repo":"https://github.com/klaibercore/cirrus"}""").repoRef().getOrNull()
        assertEquals("klaibercore", target?.owner)
        assertEquals("cirrus", target?.name)

        val dotGit = args("""{"repo":"klaibercore/cirrus.git"}""").repoRef().getOrNull()
        assertEquals("cirrus", dotGit?.name)
    }

    @Test
    fun `repo argument rejects a bare name`() {
        assertTrue(args("""{"repo":"cirrus"}""").repoRef().isFailure)
        assertTrue(args("""{"repo":""}""").repoRef().isFailure)
        assertTrue(args("{}").repoRef().isFailure)
    }

    @Test
    fun `numbers are read whether sent as json numbers or strings`() {
        // Small models routinely quote their numeric arguments.
        assertEquals(42, args("""{"number":42}""").int("number"))
        assertEquals(42, args("""{"number":"42"}""").int("number"))
        assertEquals(42, args("""{"number":" 42 "}""").int("number"))
        assertNull(args("""{"number":"not a number"}""").int("number"))
        assertNull(args("""{"number":null}""").int("number"))
        assertNull(args("{}").int("number"))
    }

    @Test
    fun `blank strings read as absent`() {
        assertNull(args("""{"path":"   "}""").string("path"))
        assertNull(args("""{"path":null}""").string("path"))
        assertEquals("src", args("""{"path":"src"}""").string("path"))
    }

    @Test
    fun `function schema has the shape ollama expects`() {
        val schema = functionSchema(
            name = "github_read_file",
            description = "Read a file.",
            required = listOf("repo"),
        ) {
            stringParam("repo", "Repository.")
            intParam("start_line", "Where to start.")
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
    fun `execute turns a github failure into readable json rather than throwing`() = runTest {
        val tool = object : GitHubTool() {
            override val name = "test_tool"
            override val definition = functionSchema(name, "Test.") {}
            override suspend fun run(arguments: JsonObject): String =
                throw GitHubException.NotFound("owner/repo")
        }
        val result = tool.execute(args("{}"))
        assertTrue(result.contains("\"error\""))
        assertTrue(result.contains("Not found"))
    }

    @Test
    fun `errorJson names the problem`() {
        assertTrue(errorJson("missing required argument: repo").contains("repo"))
    }

    @Test
    fun `clip truncates long results and says so`() {
        val clipped = "abcdefghij".clip(5)
        assertTrue(clipped.startsWith("abcde"))
        assertTrue(clipped.contains("truncated"))
    }
}
