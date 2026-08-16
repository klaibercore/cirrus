package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentTemplate
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.domain.model.StarterPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Openers and agent ideas, written by the model the user actually has.
 *
 * The hand-written lists these replace had one flaw that no amount of editing fixes: they were
 * written once, by somebody who had never met this user, and they were the same four suggestions on
 * the thousandth launch as on the first. A model that is *already configured and paid for* can do
 * better — it knows what it is good at, it can be told exactly which tools this install has, and it
 * costs one short request to ask.
 *
 * Three rules keep that from being a bad trade:
 *
 *  - **The static lists remain the answer until a better one arrives.** [starters] starts on
 *    [StarterPrompt.forSettings] and is only ever replaced by something that parsed. An empty chat
 *    is never blank while a request is in flight, and never blank because one failed.
 *  - **Nothing is ever asked twice for free.** [ensureStarters] fires once per process per
 *    capability signature. Switching GitHub on is a new signature and worth a fresh set; opening
 *    the app again is not.
 *  - **The model is told what it may suggest.** A suggestion that offers to read your repositories
 *    when there is no token is worse than no suggestion at all, so the capability list is built
 *    from settings and the model is told to use nothing outside it.
 */
@Singleton
class SuggestionGenerator @Inject constructor(
    private val engine: ChatEngine,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val json: Json,
    private val scope: CoroutineScope,
) {

    private val _starters = MutableStateFlow<List<StarterPrompt>>(emptyList())

    /** Empty until something has been generated; callers fall back to the static list. */
    val starters: StateFlow<List<StarterPrompt>> = _starters.asStateFlow()

    private val _agentIdeas = MutableStateFlow<List<AgentTemplate>>(emptyList())

    val agentIdeas: StateFlow<List<AgentTemplate>> = _agentIdeas.asStateFlow()

    private var starterJob: Job? = null
    private var starterSignature: String? = null
    private var agentJob: Job? = null
    private var agentSignature: String? = null

    /** Generates openers if this is the first time these capabilities have been seen. */
    fun ensureStarters(toolsEnabled: Boolean) {
        val current = settings.current.value
        val signature = signature(current, toolsEnabled)
        if (signature == starterSignature && _starters.value.isNotEmpty()) return
        if (starterJob?.isActive == true) return
        starterSignature = signature
        starterJob = scope.launch { _starters.value = requestStarters(current, toolsEnabled) }
    }

    /** Asks for a different four, whatever was asked before. */
    fun refreshStarters(toolsEnabled: Boolean) {
        if (starterJob?.isActive == true) return
        val current = settings.current.value
        starterSignature = signature(current, toolsEnabled)
        starterJob = scope.launch {
            requestStarters(current, toolsEnabled, vary = true)
                .takeIf { it.isNotEmpty() }
                ?.let { _starters.value = it }
        }
    }

    fun ensureAgentIdeas() {
        val current = settings.current.value
        val signature = signature(current, toolsEnabled = true)
        if (signature == agentSignature && _agentIdeas.value.isNotEmpty()) return
        if (agentJob?.isActive == true) return
        agentSignature = signature
        agentJob = scope.launch { _agentIdeas.value = requestAgentIdeas(current) }
    }

    // ---- Asking ------------------------------------------------------------------------------

    private suspend fun requestStarters(
        current: AppSettings,
        toolsEnabled: Boolean,
        vary: Boolean = false,
    ): List<StarterPrompt> {
        val model = current.defaultModel.takeIf { it.isNotBlank() } ?: return emptyList()
        val raw = engine.complete(
            model = model,
            system = "You write the suggestion chips on the opening screen of a chat app. Reply " +
                "with JSON only: an array of exactly four objects, each with \"label\" and " +
                "\"prompt\". The label is two or three words in sentence case. The prompt is what " +
                "the user would type, addressed to you, in one or two sentences — specific enough " +
                "to be worth tapping, general enough that anyone could have meant it. No preamble, " +
                "no markdown, no code fence.",
            user = buildString {
                append("Here is everything this assistant can do. Suggest nothing that needs ")
                append("anything outside this list.\n\n")
                capabilities(current, toolsEnabled).forEach { append("- ").append(it).append('\n') }
                if (vary) {
                    append("\nWrite four that are different in kind from the obvious ones.")
                }
            },
            supportsThinking = supportsThinking(model),
            tokenBudget = if (supportsThinking(model)) THINKING_BUDGET else BUDGET,
            temperature = if (vary) 1.0 else 0.8,
            format = JsonPrimitive("json"),
        ) ?: return emptyList()

        return parseArray(raw).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val label = item.text("label") ?: return@mapNotNull null
            val prompt = item.text("prompt") ?: return@mapNotNull null
            StarterPrompt(label.take(MAX_LABEL), prompt.take(MAX_PROMPT))
        }.take(STARTER_COUNT)
    }

    private suspend fun requestAgentIdeas(current: AppSettings): List<AgentTemplate> {
        val model = current.defaultModel.takeIf { it.isNotBlank() } ?: return emptyList()
        val raw = engine.complete(
            model = model,
            system = "You design scheduled prompts for a phone assistant: a prompt that runs at a " +
                "set time with nobody watching, and whose answer arrives as a notification. Reply " +
                "with JSON only: an array of four objects with \"name\" (two or three words), " +
                "\"summary\" (one short line), \"prompt\", \"hour\" (0-23), \"minute\" (0-59) and " +
                "\"days\" (one of: daily, weekdays, weekends, or a comma-separated list of " +
                "weekday names). Because nobody is there to say \"shorter, please\", every prompt " +
                "must state its own format — how many points, how long, and what to do when there " +
                "is nothing worth reporting. No preamble, no markdown, no code fence.",
            user = buildString {
                append("Here is everything this assistant can do. Suggest nothing that needs ")
                append("anything outside this list.\n\n")
                capabilities(current, toolsEnabled = true).forEach {
                    append("- ").append(it).append('\n')
                }
            },
            supportsThinking = supportsThinking(model),
            tokenBudget = if (supportsThinking(model)) THINKING_AGENT_BUDGET else AGENT_BUDGET,
            temperature = 0.8,
            format = JsonPrimitive("json"),
        ) ?: return emptyList()

        return parseArray(raw).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.text("name") ?: return@mapNotNull null
            val prompt = item.text("prompt") ?: return@mapNotNull null
            val hour = item.number("hour")?.coerceIn(0, 23) ?: 8
            val minute = item.number("minute")?.coerceIn(0, 59) ?: 0
            AgentTemplate(
                name = name.take(MAX_LABEL),
                summary = item.text("summary")?.take(MAX_LABEL * 3) ?: "",
                prompt = prompt.take(MAX_PROMPT),
                minuteOfDay = hour * 60 + minute,
                days = parseDays(item.text("days")),
                toolsEnabled = true,
            )
        }.take(STARTER_COUNT)
    }

    /**
     * What this install can actually do, in the model's own vocabulary.
     *
     * The wording matters more than it looks: these lines are the only thing standing between a
     * suggestion and an offer to do something that would fail on the first tap.
     */
    private fun capabilities(current: AppSettings, toolsEnabled: Boolean): List<String> = buildList {
        add("Answer questions, explain things, draft and edit writing, review code.")
        if (toolsEnabled) {
            add("Search the web and read specific pages, so anything current is available.")
        }
        if (toolsEnabled && current.gitHubToolsEnabled && current.hasGitHubToken) {
            add("Read the user's GitHub: repositories, code, issues and pull requests.")
        }
        if (current.memoryEnabled) {
            add("Remember durable facts about the user across conversations, and recall them.")
        }
        if (current.shellToolsEnabled) {
            add(
                "Tell the exact date and time, lay out a calendar month, describe this phone, " +
                    "and run safe shell commands in a private scratch folder.",
            )
        }
        if (current.appControlEnabled) {
            add("List the apps installed on the phone and open one.")
        }
        if (current.notificationToolEnabled) {
            add("Send a notification to the phone.")
        }
    }

    /** Server-reported capability when the catalogue has one, the model's name otherwise. */
    private fun supportsThinking(model: String): Boolean =
        models.find(model)?.supportsThinking ?: ModelInfo.mayThink(model)

    // ---- Reading whatever came back -----------------------------------------------------------

    /**
     * Pulls an array out of the reply, however it was wrapped.
     *
     * `format: "json"` guarantees the reply parses, not that it is the shape that was asked for.
     * Models wrap the array in `{"suggestions": […]}` about as often as they do not, and some fence
     * it anyway, so both are unwrapped here rather than argued with in the prompt.
     */
    private fun parseArray(raw: String): List<JsonElement> {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOfFirst { it == '[' || it == '{' }
        if (start < 0) return emptyList()

        val parsed = runCatching { json.parseToJsonElement(cleaned.substring(start)) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            when (parsed) {
                is JsonArray -> parsed.toList()
                is JsonObject ->
                    parsed.values.firstOrNull { it is JsonArray }
                        ?.jsonArray?.toList()
                        ?: emptyList()

                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.number(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.trim()?.toInt() }.getOrNull()

    /** "weekdays", "Mon, Wed", "daily" — models write all three, so all three are read. */
    private fun parseDays(raw: String?): Set<DayOfWeek> {
        val text = raw?.lowercase()?.trim() ?: return Agent.WEEKDAYS
        return when {
            text.isEmpty() -> Agent.WEEKDAYS
            "weekday" in text -> Agent.WEEKDAYS
            "weekend" in text -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            "daily" in text || "every" in text -> DayOfWeek.entries.toSet()
            else -> DayOfWeek.entries
                .filter { day -> day.name.lowercase().take(3) in text }
                .toSet()
                .ifEmpty { Agent.WEEKDAYS }
        }
    }

    /** Regenerate when what the assistant can do changes, and not merely because time passed. */
    private fun signature(current: AppSettings, toolsEnabled: Boolean): String = listOf(
        current.defaultModel,
        toolsEnabled,
        current.gitHubToolsEnabled && current.hasGitHubToken,
        current.memoryEnabled,
        current.shellToolsEnabled,
        current.appControlEnabled,
    ).joinToString("|")

    private companion object {
        const val STARTER_COUNT = 4
        const val MAX_LABEL = 32
        const val MAX_PROMPT = 400

        const val BUDGET = 400
        const val THINKING_BUDGET = 1_200
        const val AGENT_BUDGET = 700
        const val THINKING_AGENT_BUDGET = 1_800
    }
}
