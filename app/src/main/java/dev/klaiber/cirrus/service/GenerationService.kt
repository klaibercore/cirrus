package dev.klaiber.cirrus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.klaiber.cirrus.MainActivity
import dev.klaiber.cirrus.R
import dev.klaiber.cirrus.domain.TurnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive and unfrozen while an answer is still streaming.
 *
 * Without this, a turn only survived while Cirrus was on screen: Android freezes a cached
 * process within seconds of it leaving the foreground, and a frozen process cannot read from
 * its socket. The stream stalled, the connection eventually died, and the reply was left
 * wherever it happened to be — reliably at a sentence or a tool call, which reads like the model
 * deciding to stop rather than the system pulling the plug.
 *
 * It follows [TurnController.turns] and stops itself the moment nothing is generating, so it is
 * never the reason the app stays in memory.
 */
@AndroidEntryPoint
class GenerationService : Service() {

    @Inject lateinit var turnController: TurnController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Android can refuse the promotion outright — a start that raced the app being
        // backgrounded. Stopping is then the only legal move; the turn carries on without the
        // protection this service would have given it, which beats taking the app down with it.
        if (!enterForeground()) {
            stopSelf()
            return
        }
        acquireWakeLock()

        scope.launch {
            turnController.turns.collect { live ->
                if (live.isEmpty()) {
                    stopSelf()
                } else {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(live.size))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            turnController.stopAll()
            stopSelf()
        }
        // Restarting this service without the conversation that justified it would be pointless:
        // the turn died with the process, and the user is the one who decides to ask again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(): Boolean = runCatching {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(active = 1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }.onFailure { error ->
        Log.w(TAG, "Refused permission to run in the foreground", error)
    }.isSuccess

    private fun buildNotification(active: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, GenerationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_generating_title))
            .setContentText(
                if (active > 1) {
                    getString(R.string.notification_generating_many, active)
                } else {
                    getString(R.string.notification_generating_text)
                },
            )
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_generating),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_generating_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    /**
     * A foreground service is not allowed to sleep, but the CPU still is: with the screen off,
     * the reader thread would not run between packets. The timeout is a safety valve, not a
     * budget — releasing in [onDestroy] is the normal path.
     */
    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "GenerationService"
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1
        private const val ACTION_STOP = "dev.klaiber.cirrus.action.STOP_GENERATION"
        private const val WAKE_LOCK_TAG = "cirrus:generation"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /**
         * Starts the service for a turn that is beginning.
         *
         * Safe to call when one is already running — the second start is a no-op — and safe to
         * call when Android refuses: the turn still runs, it just loses the guarantee that it
         * will survive the app being backgrounded, which is not worth crashing over.
         */
        fun start(context: Context) {
            val intent = Intent(context, GenerationService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { error -> Log.w(TAG, "Could not start the generation service", error) }
        }
    }
}
