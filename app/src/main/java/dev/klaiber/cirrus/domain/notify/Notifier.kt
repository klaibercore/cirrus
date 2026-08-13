package dev.klaiber.cirrus.domain.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.klaiber.cirrus.MainActivity
import dev.klaiber.cirrus.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Puts something on the phone's notification shade.
 *
 * An interface because the tool that calls it is exercised in JVM tests, where there is no
 * `Context` to build a real one against — and because "did this try to notify, and what did it
 * say?" is the only thing worth asserting about a notification anyway.
 */
interface Notifier {

    /** Returns false when the platform refused — usually because notifications are switched off. */
    fun notify(
        title: String,
        body: String,
        channel: Channel,
        conversationId: String? = null,
    ): Boolean

    /**
     * Two channels, because they interrupt differently and the user should be able to silence one
     * without the other: an agent finishing overnight is news that can wait until morning, while a
     * notification the model was asked to send is the thing the user asked for.
     */
    enum class Channel(
        val id: String,
        val title: String,
        val description: String,
        val importance: Int,
        val priority: Int,
    ) {
        AGENTS(
            id = "agents",
            title = "Scheduled agents",
            description = "Results from agents that run on a schedule.",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            priority = NotificationCompat.PRIORITY_DEFAULT,
        ),
        ASSISTANT(
            id = "assistant",
            title = "Assistant notifications",
            description = "Reminders and alerts the assistant was asked to send.",
            importance = NotificationManager.IMPORTANCE_HIGH,
            priority = NotificationCompat.PRIORITY_HIGH,
        ),
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversationId"
    }
}

/**
 * The real one.
 *
 * Tapping a notification opens the conversation it came from, which is the only thing that makes a
 * notification about an answer worth sending: the answer itself is three lines long and lives in a
 * thread you will want to reply to.
 */
@Singleton
class AndroidNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : Notifier {

    override fun notify(
        title: String,
        body: String,
        channel: Notifier.Channel,
        conversationId: String?,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(manager, channel)
        if (!manager.areNotificationsEnabled()) return false

        manager.notify(Random.nextInt(), build(title, body, channel, conversationId))
        return true
    }

    private fun build(
        title: String,
        body: String,
        channel: Notifier.Channel,
        conversationId: String?,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.let { putExtra(Notifier.EXTRA_CONVERSATION_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            conversationId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(MAX_TITLE))
            .setContentText(body.take(MAX_BODY))
            // Without BigTextStyle a two-line answer is truncated to one, and it is usually the
            // half that says nothing.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(MAX_BODY)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(channel.priority)
            .build()
    }

    private fun ensureChannel(manager: NotificationManager, channel: Notifier.Channel) {
        if (manager.getNotificationChannel(channel.id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(channel.id, channel.title, channel.importance).apply {
                description = channel.description
            },
        )
    }

    private companion object {
        const val MAX_TITLE = 100
        const val MAX_BODY = 800
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotifierModule {

    @Binds
    abstract fun bindNotifier(notifier: AndroidNotifier): Notifier
}
