package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.repository.SkillRepository
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.tools.github.stringParam
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads an installed skill's instructions into the turn.
 *
 * The two halves of the feature are deliberately asymmetric. Every enabled skill's *name and
 * description* are already in the system message, put there by [ToolRegistry.standingBrief] —
 * that is what makes the model aware a skill exists at the moment it is deciding how to answer,
 * which no tool description could do. The instructions are what this tool is for: thousands of
 * words that are worth having when the skill applies and pure cost when it does not.
 *
 * Reading a file Cirrus already has is not an external tool, so this is not behind the
 * conversation's tools switch — for the same reason memory is not. Nothing leaves the phone.
 */
@Singleton
class UseSkillTool @Inject constructor(
    private val skills: SkillRepository,
) : CirrusTool {

    override val name: String = "use_skill"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Load the full instructions for one of the installed skills listed in your " +
            "system message. Call this as soon as a skill's description matches what you have " +
            "been asked to do, before you start — the description is a summary, and the " +
            "instructions are the part that changes how the work is done. Then follow them.",
        required = listOf("name"),
    ) {
        stringParam("name", "The skill's name, exactly as listed in your system message.")
    }

    override suspend fun execute(arguments: JsonObject): String = skillTool {
        val requested = arguments.string("name")
            ?: return@skillTool errorJson("missing required argument: name")

        val skill = skills.find(requested)
            ?: return@skillTool errorJson(
                "No skill called \"$requested\" is installed. The ones that are, are listed in " +
                    "your system message; call list_skills to see them again.",
            )

        buildJsonObject {
            put("name", skill.name)
            put("description", skill.description)
            put("instructions", skill.instructions)
            put("source", skill.origin.webUrl)
        }.toString()
    }
}

/**
 * The roster, again.
 *
 * Redundant with the standing brief by design, and it earns its place twice: the brief truncates
 * once there are more skills than a system message should carry, and a long conversation pushes
 * the system message far enough back that a model will genuinely re-check rather than guess a name.
 */
@Singleton
class ListSkillsTool @Inject constructor(
    private val skills: SkillRepository,
) : CirrusTool {

    override val name: String = "list_skills"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List the installed skills and what each one is for. Use it when you want " +
            "to check whether a skill covers the task in front of you.",
    ) {}

    override suspend fun execute(arguments: JsonObject): String = skillTool {
        val installed = skills.enabled
        buildJsonObject {
            put(
                "skills",
                JsonArray(
                    installed.map { skill ->
                        buildJsonObject {
                            put("name", skill.name)
                            put("description", skill.description)
                            put("from", skill.origin.repository)
                        }
                    },
                ),
            )
            if (installed.isEmpty()) {
                put(
                    "note",
                    "No skills are installed. The user can add some at " +
                        "Settings → Tools → Skills.",
                )
            }
        }.toString()
    }
}

/**
 * Nothing a skill tool does may throw.
 *
 * The model is mid-turn; an exception here ends the turn with a stack trace instead of letting it
 * recover. Cancellation is rethrown rather than reported: the user pressing stop is not a tool
 * failure, and turning it into one would leave a dead result in the transcript.
 */
private suspend fun skillTool(block: suspend () -> String): String = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    errorJson(error.message ?: "The installed skills could not be read.")
}

/**
 * The skills tools, offered or withheld together.
 *
 * As a pair for the same reason the memory tools are: a roster with no way to open an entry is a
 * list of things the model cannot have, and the loader with no roster is a tool it cannot address.
 */
@Singleton
class SkillToolSet @Inject constructor(
    useSkill: UseSkillTool,
    listSkills: ListSkillsTool,
) {
    val all: List<CirrusTool> = listOf(useSkill, listSkills)
}
