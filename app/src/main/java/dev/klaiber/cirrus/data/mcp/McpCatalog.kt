package dev.klaiber.cirrus.data.mcp

/**
 * A server the user might plausibly want, offered as a starting point.
 *
 * Only the URL is knowledge Cirrus can usefully hold. Whether the server exists, what it offers
 * and whether the user's token opens it are all questions only the server can answer, so every
 * entry still goes through the same probe as a hand-typed URL before it can be saved.
 */
data class McpCatalogEntry(
    val label: String,
    val url: String,
    /** What the server is for, in one line. */
    val summary: String,
    /** How to get a token, or null when the server is open. */
    val tokenHint: String? = null,
)

/**
 * Well-known hosted MCP servers.
 *
 * Deliberately short. A long directory would go stale silently and imply an endorsement Cirrus
 * cannot make — these are conveniences for the common cases, not a registry.
 */
val MCP_CATALOG: List<McpCatalogEntry> = listOf(
    McpCatalogEntry(
        label = "GitHub",
        url = "https://api.githubcopilot.com/mcp/",
        summary = "Repositories, issues and pull requests.",
        tokenHint = "A GitHub personal access token. Cirrus also ships native GitHub tools — " +
            "attach this only if you want the server's own set instead.",
    ),
    McpCatalogEntry(
        label = "Sentry",
        url = "https://mcp.sentry.dev/mcp",
        summary = "Errors, issues and releases from your Sentry projects.",
        tokenHint = "Signs you in through Sentry; leave the token blank if it does.",
    ),
    McpCatalogEntry(
        label = "Linear",
        url = "https://mcp.linear.app/mcp",
        summary = "Issues, projects and cycles from your Linear workspace.",
        tokenHint = "A Linear API key from Settings → API.",
    ),
    McpCatalogEntry(
        label = "Hugging Face",
        url = "https://huggingface.co/mcp",
        summary = "Search models, datasets and Spaces.",
        tokenHint = "Optional. A Hugging Face access token raises rate limits and reaches " +
            "private repositories.",
    ),
    McpCatalogEntry(
        label = "DeepWiki",
        url = "https://mcp.deepwiki.com/mcp",
        summary = "Generated documentation for public GitHub repositories.",
        tokenHint = null,
    ),
)
