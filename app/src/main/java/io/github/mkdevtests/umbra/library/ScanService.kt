package io.github.mkdevtests.umbra.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import io.github.mkdevtests.umbra.MainActivity
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps an analysis of the library going in the foreground, with its
 * progress in a notification: Android doesn't stop it when Nyxara leaves the
 * screen, an analysis of tens of thousands of files goes to its end.
 */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification("Analyse de la bibliothèque…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val scan = (application as NyxaraApp).library.scan
        scope.launch {
            scan.collect { state ->
                if (!state.running) {
                    stopSelf()
                    return@collect
                }
                getSystemService(NotificationManager::class.java)?.notify(ID, notification(state.progress ?: "Analyse de la bibliothèque…"))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Analyse de la bibliothèque", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Nyxara")
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val ID = 9
        private const val CHANNEL = "scan"

        /** Nothing when Android refuses (app not on screen): the analysis goes on without it. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ScanService::class.java)) }
                .onFailure { Log.w("ScanService", "not started", it) }
        }
    }
}
