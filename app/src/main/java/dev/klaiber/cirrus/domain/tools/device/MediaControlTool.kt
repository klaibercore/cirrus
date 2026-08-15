package dev.klaiber.cirrus.domain.tools.device

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.klaiber.cirrus.domain.tools.CirrusTool
import dev.klaiber.cirrus.domain.tools.github.enumParam
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.int
import dev.klaiber.cirrus.domain.tools.github.intParam
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.tools.shell.shellTool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's own media buttons, which is the answer for everyone Spotify's API will not serve.
 *
 * Spotify refuses playback control on free accounts. That is their decision and not one Cirrus can
 * argue with, but it leaves "pause the music" — the most obvious request anyone will ever make of
 * an assistant on a phone — failing for a large share of users. Android's media key dispatch has no
 * such condition: it goes to whatever currently holds media focus, which is Spotify if Spotify is
 * playing, and the podcast app if that is.
 *
 * It costs nothing to have. [AudioManager.dispatchMediaKeyEvent] needs no permission, no account
 * and no network, and it works for players this app has never heard of. What it cannot do is *read*
 * anything — there is no "what is playing?" here, because that would mean
 * `MediaSessionManager.getActiveSessions`, which requires notification-listener access: a
 * system-settings toggle that grants the ability to read every notification on the phone. That is a
 * wildly disproportionate price for a track title, and `spotify_now_playing` answers it anyway for
 * the account that matters.
 *
 * Not a write, by the same test everything else uses: every action here is undone by another action
 * here.
 */
@Singleton
class MediaControlTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : CirrusTool {

    override val name: String = "media_control"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Play, pause, skip or set the volume for whatever is playing audio on this " +
            "phone — Spotify, a podcast app, anything. It uses Android's own media buttons, so it " +
            "needs no account and no Spotify Premium, and it is the right fallback whenever " +
            "spotify_playback comes back refused. It cannot tell you what is playing, only " +
            "control it, and it does nothing at all if nothing is playing.",
        required = listOf("action"),
    ) {
        enumParam(
            "action",
            "What to do. \"play_pause\" toggles, which is the safest choice when you do not know " +
                "whether something is currently playing.",
            listOf("play_pause", "play", "pause", "next", "previous", "stop", "volume", "mute", "unmute"),
        )
        intParam("volume_percent", "0-100. Required for \"volume\".")
    }

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        val action = arguments.string("action")?.lowercase()
            ?: return@shellTool errorJson("missing required argument: action")
        val audio = context.getSystemService<AudioManager>()
            ?: return@shellTool errorJson("This device has no audio service.")

        when (action) {
            "volume" -> {
                val percent = arguments.int("volume_percent")
                    ?: return@shellTool errorJson("volume needs volume_percent, 0-100")
                setVolume(audio, percent)?.let { return@shellTool errorJson(it) }
            }

            "mute" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                0,
            )

            "unmute" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_UNMUTE,
                0,
            )

            else -> {
                val keyCode = KEYS[action]
                    ?: return@shellTool errorJson("unknown action: $action")
                // Both halves, and in order. A player that only listens for ACTION_DOWN works
                // either way; one that waits for the matching ACTION_UP — which is how a long-press
                // is told from a tap — never fires at all if only the first is sent.
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
        }

        buildJsonObject {
            put("done", action)
            put("volume_percent", currentVolumePercent(audio))
            put(
                "note",
                "Sent to whatever holds media focus on the phone. Android reports no result, so " +
                    "this cannot confirm anything was listening — do not claim it worked, say " +
                    "what you sent.",
            )
        }.toString()
    }

    /** Returns an explanation on failure, or null when the volume was set. */
    private fun setVolume(audio: AudioManager, percent: Int): String? {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) * max + 50) / 100
        return runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            null
        }.getOrElse {
            // Do Not Disturb blocks volume changes unless the app holds notification-policy
            // access, which Cirrus does not ask for.
            "Android refused the volume change, which usually means Do Not Disturb is on. " +
                "Ask the user to change the volume themselves."
        }
    }

    private fun currentVolumePercent(audio: AudioManager): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return 0
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private companion object {
        val KEYS = mapOf(
            "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
        )
    }
}
