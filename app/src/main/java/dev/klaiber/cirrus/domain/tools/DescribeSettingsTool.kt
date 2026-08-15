package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.settings.SettingSwitch
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.shell.shellTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Cirrus can do, what is switched off, and where the switch is.
 *
 * The failure this exists for: a model asks for a tool that is behind a toggle, is told it cannot
 * have it, and — with no way to tell "this app does not do that" from "this app does that but not
 * today" — tells the user the app lacks the feature. The user believes it, because why would they
 * not, and nothing in the conversation ever corrects them.
 *
 * A refusal from `ToolRegistry` already names the specific switch. This is the other half: the
 * question asked *before* promising something. "Can you play music?" is answerable honestly only by
 * something that can see the settings, and the honest answer is often "yes, once you turn one thing
 * on, and here is where."
 *
 * It is the only tool with no gate of its own, which follows: a tool whose job is explaining why
 * things are switched off would be useless as the thing that was switched off.
 */
@Singleton
class DescribeSettingsTool @Inject constructor(
    private val settings: SettingsRepository,
) : CirrusTool {

    override val name: String = "describe_settings"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List Cirrus's capability switches: which are on, which are off, and " +
            "exactly where in the app each one lives. Call it before telling the user that " +
            "something is not possible — most of the time it is possible and simply switched " +
            "off, and the useful answer is which toggle to flip rather than \"I can't do that\". " +
            "Also worth calling when a tool has just been refused, so you can explain the refusal " +
            "in terms of their settings. Never claim to have changed a setting yourself: you " +
            "cannot, and the user has to do it.",
    ) {}

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val current = settings.current.value
        buildJsonObject {
            put(
                "settings",
                JsonArray(
                    SettingSwitch.entries.map { switch ->
                        buildJsonObject {
                            put("name", switch.title)
                            put("status", switch.status(current))
                            put("enables", switch.summary)
                            put("where", switch.path)
                            switch.remedy(current)?.let { put("to_enable", it) }
                        }
                    },
                ),
            )
            put(
                "per_conversation",
                "Web search, GitHub, Spotify and MCP servers additionally need this " +
                    "conversation's own tools switch, which is the toggle in the message box " +
                    "rather than a setting. Its state is not readable from here, so if one of " +
                    "those is refused while its setting says \"on\", that switch is the reason.",
            )
            put(
                "note",
                "You cannot change any of these. Tell the user which one to turn on and where it " +
                    "is, then carry on with whatever you can do without it.",
            )
        }.toString()
    }
}
