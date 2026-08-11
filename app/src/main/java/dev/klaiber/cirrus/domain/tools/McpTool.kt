package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.mcp.McpClient
import dev.klaiber.cirrus.data.mcp.McpServerConfig
import dev.klaiber.cirrus.data.mcp.McpToolDescriptor
import dev.klaiber.cirrus.data.repository.McpServerRepository
import dev.klaiber.cirrus.data.repository.McpToolBinding
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One tool on a remote MCP server, wearing the interface the rest of Cirrus already speaks.
 *
 * The adapter is thin by design: the server's own `inputSchema` is forwarded to the model
 * unchanged. Rewriting it would mean guessing at semantics Cirrus does not have, and a schema
 * the server accepts is by definition the right one.
 */
class McpTool(
    private val server: McpServerConfig,
    private val descriptor: McpToolDescriptor,
    private val client: McpClient,
) : CirrusTool {

    override val name: String = qualifiedName(server, descriptor.name)

    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", name)
                // The server label goes in the description so the model can tell two servers'
                // similarly-named tools apart without having to decode the name prefix.
                put(
                    "description",
                    buildString {
                        append(descriptor.description.ifBlank { "A tool provided by ${server.label}." })
                        append(" (Provided by the \"${server.label}\" MCP server.)")
                    },
                )
                put("parameters", descriptor.inputSchema)
            },
        )
    }

    /**
     * Never throws: a tool call happens mid-turn, and an exception here would end the turn with a
     * stack trace instead of letting the model recover or explain itself.
     */
    override suspend fun execute(arguments: JsonObject): String = runCatching {
        client.callTool(server, descriptor.name, arguments)
    }.getOrElse { failure ->
        buildJsonObject {
            put("error", failure.message ?: "the MCP server did not answer")
            put("server", server.label)
            put("tool", descriptor.name)
        }.toString()
    }

    companion object {
        /**
         * Namespaces a server's tool so two servers offering `search` do not collide, and so an
         * MCP tool can never shadow a built-in one.
         *
         * Function names have to match `[a-zA-Z0-9_-]{1,64}`, so anything else in a label or a
         * tool name becomes an underscore, and the result is truncated. Truncation keeps the tool
         * name and sacrifices the prefix, because the tail is what carries the meaning.
         */
        fun qualifiedName(server: McpServerConfig, toolName: String): String {
            val tool = toolName.sanitized()
            val prefix = server.label.sanitized().ifBlank { "mcp" }
            val budget = MAX_NAME_LENGTH - tool.length - SEPARATOR.length
            return if (budget < MIN_PREFIX_LENGTH) {
                tool.take(MAX_NAME_LENGTH)
            } else {
                prefix.take(budget) + SEPARATOR + tool
            }
        }

        private fun String.sanitized(): String =
            map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
                .joinToString("")
                .trim('_')

        private const val SEPARATOR = "__"
        private const val MAX_NAME_LENGTH = 64
        private const val MIN_PREFIX_LENGTH = 2
    }
}

/**
 * The MCP tools currently on offer, rebuilt from whatever the repository last resolved.
 *
 * Separate from [McpServerRepository] so the tool layer depends on an interface it owns rather
 * than reaching into data-layer state, and so the mapping from binding to [CirrusTool] lives next
 * to the other tool wiring.
 */
@Singleton
class McpToolSet @Inject constructor(
    private val repository: McpServerRepository,
    private val client: McpClient,
) {
    /** Rebuilt per read, because the bindings behind it change as servers come and go. */
    val all: List<CirrusTool>
        get() = repository.bindings.value.map { it.toTool() }

    fun find(name: String): CirrusTool? =
        repository.bindings.value.firstOrNull { binding ->
            McpTool.qualifiedName(binding.server, binding.descriptor.name) == name
        }?.toTool()

    private fun McpToolBinding.toTool(): CirrusTool = McpTool(server, descriptor, client)
}
