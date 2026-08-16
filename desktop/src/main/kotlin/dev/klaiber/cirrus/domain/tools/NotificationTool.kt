package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.domain.notify.Notifier
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Lets the model put something on the desktop notification tray.
 *
 * This is the one tool whose output the user cannot miss, which is exactly what makes it useful to
 * a scheduled agent — an answer written at 3am is worthless if nobody knows it exists — and exactly
 * what makes it worth being careful with in a normal chat. Hence the description below: a
 * notification is for something the user asked to be told about, not for announcing that a task is
 * finished while they are looking at the screen it finished on.
 *
 * The tool cannot escalate: the desktop decides whether notifications are allowed at all, and a
 * blocked notification comes back as a plain false rather than as an error the model might retry.
 */
class SendNotificationTool(
    private val notifier: Notifier,
) : CirrusTool {

    override val name: String = "send_notification"

    /**
     * Set for the duration of an agent run, so a scheduled result opens its own thread when tapped.
     * Chats leave it null: the conversation is already on screen.
     */
    @Volatile
    var conversationId: String? = null

    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put(
                "description",
                "Send a notification to the user's desktop. Use it when they asked to be told " +
                    "about something, when a scheduled run has produced something worth " +
                    "surfacing, or when you found something that cannot wait until they next " +
                    "open the app. Do NOT use it to announce that you have finished answering " +
                    "in a conversation they are already reading. Keep the title under about " +
                    "eight words and put the substance in the body — the body is what they read " +
                    "on the lock screen.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Short headline, a few words.")
                    }
                    putJsonObject("body") {
                        put("type", "string")
                        put("description", "What the user needs to know, in one or two sentences.")
                    }
                }
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("title"))
                        add(JsonPrimitive("body"))
                    },
                )
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): String = try {
        val title = arguments.string("title")
        val body = arguments.string("body")
        when {
            title == null -> errorJson("missing required argument: title")
            body == null -> errorJson("missing required argument: body")
            else -> {
                val posted = notifier.notify(
                    title = title,
                    body = body,
                    channel = Notifier.Channel.ASSISTANT,
                    conversationId = conversationId,
                )
                buildJsonObject {
                    put("sent", posted)
                    if (!posted) {
                        // Actionable, and addressed to the model: it has to decide whether to say
                        // the thing in the answer instead.
                        put(
                            "reason",
                            "Notifications are switched off for Cirrus on this desktop. Tell the " +
                                "user what you were going to notify them about, in your answer.",
                        )
                    }
                }.toString()
            }
        }
    } catch (error: Throwable) {
        errorJson(error.message ?: "Could not post the notification.")
    }
}
