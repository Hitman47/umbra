package io.github.mkdevtests.umbra.download

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
import io.github.mkdevtests.umbra.nas.toUserMessage
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Copies the queued videos to the device, one at a time, in the foreground
 * (a notification shows the progress). Read from the NAS through the app's
 * local server, with its parallel reads; a copy cut off resumes where it
 * stopped. Nothing is ever written on the NAS.
 */
class DownloadService : Service() {

    @Volatile private var stopped = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopped = true
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(ID, notification("Préparation…", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isAlive != true) {
            stopped = false
            worker = thread(name = "downloads", isDaemon = true) { runQueue() }
        }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        val app = application as NyxaraApp
        val downloads = app.downloads
        while (!stopped) {
            val download = downloads.next() ?: break
            try {
                copy(app, download)
            } catch (e: Exception) {
                Log.w(TAG, "download ${download.source}", e)
                downloads.set(download.copy(state = if (stopped) DownloadState.Queued else DownloadState.Failed, error = e.toUserMessage()))
                if (!stopped) continue else break
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun copy(app: NyxaraApp, start: Download) {
        val downloads = app.downloads
        val part = File(start.local + Downloads.PART)
        var done = if (part.exists()) part.length() else 0L
        var download = start.copy(state = DownloadState.Running, done = done, error = null)
        downloads.set(download)
        if (done < download.size) {
            val connection = URL(app.streamServer.urlFor(download.source)).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=$done-")
            connection.inputStream.use { input ->
                FileOutputStream(part, true).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var shown = 0L
                    while (!stopped) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (done - shown >= PROGRESS_STEP) {
                            shown = done
                            download = download.copy(done = done)
                            downloads.set(download)
                            notify(download)
                        }
                    }
                }
            }
        }
        if (stopped) {
            downloads.set(download.copy(state = DownloadState.Queued, done = done))
            return
        }
        if (done < download.size) throw java.io.IOException("Copie incomplète (${done / (1 shl 20)} Mo sur ${download.size / (1 shl 20)})")
        part.renameTo(File(download.local))
        // The subtitle files next to the video, small: copied whole.
        val subtitles = download.subtitles.mapNotNull { path ->
            runCatching {
                val target = File(downloads.folder, Downloads.safeName(path.substringAfterLast('\\')))
                app.nas?.open(path)?.use { file ->
                    val bytes = ByteArray(file.size.toInt())
                    var filled = 0
                    while (filled < bytes.size) {
                        val count = file.read(bytes, filled.toLong(), filled, bytes.size - filled)
                        if (count <= 0) break
                        filled += count
                    }
                    target.writeBytes(bytes.copyOf(filled))
                }
                target.absolutePath
            }.getOrNull()
        }
        downloads.set(download.copy(state = DownloadState.Done, done = download.size, localSubtitles = subtitles))
    }

    private fun notify(download: Download) {
        // A Perso video stays behind the tab's lock: no name on the notification.
        val title = if (download.key.startsWith("perso:")) "vidéo Perso" else download.title
        getSystemService(NotificationManager::class.java)?.notify(ID, notification(title, download))
    }

    private fun notification(title: String, download: Download?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Téléchargement : $title")
            .setContentText(download?.let { "${(it.fraction * 100).toInt()} % · ${it.done / (1 shl 20)} / ${it.size / (1 shl 20)} Mo" })
            .setProgress(100, download?.let { (it.fraction * 100).toInt() } ?: 0, download == null)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .build()
    }

    override fun onDestroy() {
        stopped = true
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val ID = 8
        private const val CHANNEL = "downloads"
        private const val ACTION_STOP = "io.github.mkdevtests.umbra.STOP_DOWNLOADS"
        private const val PROGRESS_STEP = 8L shl 20

        fun start(context: Context) = context.startForegroundService(Intent(context, DownloadService::class.java))
    }
}
