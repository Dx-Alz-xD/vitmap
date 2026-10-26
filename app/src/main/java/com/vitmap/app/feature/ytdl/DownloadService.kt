package com.vitmap.app.feature.ytdl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vitmap.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** Keeps the process alive while yt-dlp downloads and shows their progress in a notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_ID, progressNotification("Preparing download", null, 0), type)
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val queue = YtQueue.get(this)
        val manager = getSystemService(NotificationManager::class.java)
        val announced = HashSet<String>()
        var first = true
        combine(queue.history, queue.live) { h, live -> h to live }.conflate().collect { (h, live) ->
            if (h == null) return@collect
            if (first) {
                // Don't announce downloads that finished before this service started.
                h.items.filter { it.state == YtState.DONE || it.state == YtState.FAILED }.forEach { announced += it.id }
                first = false
            }
            for (item in h.items) {
                if ((item.state == YtState.DONE || item.state == YtState.FAILED) && announced.add(item.id)) {
                    manager.notify(item.id.hashCode(), doneNotification(item))
                }
            }
            val running = h.items.firstOrNull { it.state == YtState.RUNNING }
            val queued = h.items.count { it.state == YtState.QUEUED }
            if (running == null && queued == 0) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                // Only stops if no newer start request arrived in the meantime.
                stopSelf(lastStartId)
                return@collect
            }
            val l = running?.let { live[it.id] }
            val text = listOfNotNull(l?.item?.takeIf { it.isNotEmpty() }, l?.stage, l?.speed?.takeIf { it.isNotEmpty() }, l?.eta?.takeIf { it.isNotEmpty() }).joinToString(" · ")
            manager.notify(PROGRESS_ID, progressNotification(running?.title ?: "Waiting", text.ifEmpty { null } ?: if (queued > 0) "$queued waiting" else null, ((l?.progress ?: -1f) * 100).toInt()))
            // The system drops updates that arrive faster than this.
            delay(800)
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "ytdl").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(title: String, text: String?, percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun doneNotification(item: YtItem): Notification =
        NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (item.state == YtState.DONE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (item.state == YtState.DONE) "Downloaded" else "Download failed")
            .setContentText(item.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (item.state == YtState.DONE) item.title else "${item.title}\n${item.error.orEmpty()}"))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PROGRESS_ID = 4101
        private const val CHANNEL_PROGRESS = "downloads"
        private const val CHANNEL_DONE = "downloads-done"

        private fun ensureChannels(context: Context) {
            val m = context.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            m.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun start(context: Context) {
            // Starting from the background is restricted on Android 12+; downloads still run while the app is open.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
