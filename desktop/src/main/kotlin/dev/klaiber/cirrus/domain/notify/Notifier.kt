package dev.klaiber.cirrus.domain.notify

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts something on the desktop notification tray.
 *
 * An interface because the tool that calls it is exercised in JVM tests, where there is no
 * display to build a real one against — and because "did this try to notify, and what did it
 * say?" is the only thing worth asserting about a notification anyway.
 */
interface Notifier {

    /**
     * Whether this machine can show a notification at all.
     *
     * On Android the equivalent question is a runtime permission, which the user can be asked for.
     * Here it is whether the desktop has a system tray — a headless session, or a desktop with no
     * tray area, has nothing to ask for and nothing to grant.
     */
    val isAvailable: Boolean

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
    ) {
        AGENTS(
            id = "agents",
            title = "Scheduled agents",
            description = "Results from agents that run on a schedule.",
        ),
        ASSISTANT(
            id = "assistant",
            title = "Assistant notifications",
            description = "Reminders and alerts the assistant was asked to send.",
        ),
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversationId"
    }
}

/**
 * The real one, via AWT's SystemTray.
 *
 * A desktop notification is a tray balloon (or, on modern desktops, whatever the tray icon's
 * displayMessage maps to — GNOME and KDE route it into their own notification daemons). The
 * tray icon is created lazily and kept for the life of the process, so the first notification
 * pays for the icon and the rest are free.
 *
 * When the platform has no tray at all — a headless session, or a desktop without a tray area —
 * [notify] returns false with the same honest reason the Android build gives for a blocked
 * notification, so the model can say the thing in its answer instead.
 */
class DesktopNotifier : Notifier {

    override val isAvailable: Boolean get() = SystemTray.isSupported()

    private val trayReady = AtomicBoolean(false)
    private var trayIcon: TrayIcon? = null

    override fun notify(
        title: String,
        body: String,
        channel: Notifier.Channel,
        conversationId: String?,
    ): Boolean {
        if (!ensureTray()) return false
        val icon = trayIcon ?: return false
        return runCatching {
            icon.displayMessage(title.take(MAX_TITLE), body.take(MAX_BODY), TrayIcon.MessageType.NONE)
            true
        }.getOrElse { false }
    }

    /**
     * Installs the tray icon once. Returns false when there is no tray to install into.
     *
     * The icon is a 16x16 solid square in the app's accent colour — a tray icon is a marker, not
     * a logo, and a generated one avoids shipping an image asset for a desktop build.
     */
    private fun ensureTray(): Boolean {
        if (trayReady.get()) return true
        if (!SystemTray.isSupported()) return false
        return runCatching {
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(placeholderIcon(), "Cirrus").apply {
                isImageAutoSize = true
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        // A click on the tray icon brings the window forward. The window itself is
                        // registered separately; this is just the affordance.
                        onTrayClick?.invoke()
                    }
                })
            }
            tray.add(icon)
            trayIcon = icon
            trayReady.set(true)
            true
        }.getOrElse { false }
    }

    /** Called when the user clicks the tray icon; wired by the desktop app to focus the window. */
    var onTrayClick: (() -> Unit)? = null

    private fun placeholderIcon(): BufferedImage {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color(0x1A, 0x1A, 0x1A)
        graphics.fillRect(0, 0, 16, 16)
        graphics.dispose()
        return image
    }

    private companion object {
        const val MAX_TITLE = 100
        const val MAX_BODY = 800
    }
}
