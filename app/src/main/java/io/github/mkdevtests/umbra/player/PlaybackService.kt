package io.github.mkdevtests.umbra.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import io.github.mkdevtests.umbra.R

/**
 * Keeps the sound going while the player is out of sight (screen off,
 * another app): a foreground service with a notification to pause, stop or
 * come back to the player. The player itself stays in [PlayerActivity];
 * [BackgroundPlayback] links the two.
 */
class PlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> BackgroundPlayback.toggle?.invoke()
            ACTION_STOP -> {
                BackgroundPlayback.stop?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (wakeLock == null) {
            // The NAS is read over Wi-Fi with the screen off: keep the CPU and the Wi-Fi awake meanwhile.
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nyxara:playback")?.apply { acquire(4 * 3600_000L) }
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nyxara:playback")?.apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "playback"
        private const val ACTION_TOGGLE = "io.github.mkdevtests.umbra.TOGGLE"
        private const val ACTION_STOP = "io.github.mkdevtests.umbra.STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, PlaybackService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, PlaybackService::class.java))

        /** Shows the new state (paused, other episode) in the notification. */
        fun update(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.notify(ID, notification(context))
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Lecture en arrière-plan", NotificationManager.IMPORTANCE_LOW))
            }
            fun service(action: String, code: Int) = PendingIntent.getService(
                context, code, Intent(context, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val back = PendingIntent.getActivity(
                context, 0, Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE,
            )
            val playing = BackgroundPlayback.playing
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(BackgroundPlayback.title)
                .setContentText(BackgroundPlayback.subtitle)
                .setContentIntent(back)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                        if (playing) "Pause" else "Lecture",
                        service(ACTION_TOGGLE, 1),
                    ).build(),
                )
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), "Arrêter", service(ACTION_STOP, 2),
                    ).build(),
                )
                .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1).apply { BackgroundPlayback.session?.let(::setMediaSession) })
                .build()
        }
    }
}

/** The player behind the notification, while it plays out of sight. */
object BackgroundPlayback {
    @Volatile var title: String = ""
    @Volatile var subtitle: String? = null
    @Volatile var playing: Boolean = false
    @Volatile var toggle: (() -> Unit)? = null
    @Volatile var stop: (() -> Unit)? = null
    /** The player's media session: the notification's controls and the lock screen act on it. */
    @Volatile var session: android.media.session.MediaSession.Token? = null
}
