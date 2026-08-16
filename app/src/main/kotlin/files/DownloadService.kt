package garden.appl.mitch.files

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import garden.appl.mitch.NOTIFICATION_CHANNEL_ID_INSTALLING
import tofu.gg.mitchy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that runs downloads. Unlike WorkManager's expedited (foreground)
 * workers, a real foreground service is not stopped when the app goes to the background,
 * when the expedited 10-minute time cap is hit, or when the app is removed from the recents
 * screen, so a download keeps running until it completes, fails, or is cancelled by the user.
 */
class DownloadService : Service() {
    companion object {
        const val ACTION_DOWNLOAD = "garden.appl.mitch.DOWNLOAD"
        const val ACTION_CANCEL = "garden.appl.mitch.CANCEL_DOWNLOAD"

        const val EXTRA_URL = "url"
        const val EXTRA_USER_AGENT = "ua"
        const val EXTRA_DOWNLOAD_DIR = "path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DOWNLOAD_OR_INSTALL_ID = "download_id"
        const val EXTRA_UPLOAD_ID = "upload_id"
        const val EXTRA_CONTENT_LENGTH = "content_length"

        private val activeDownloads = ConcurrentHashMap<Long, ActiveDownload>()

        fun isDownloading(downloadId: Long): Boolean = activeDownloads.containsKey(downloadId)

        fun cancel(downloadId: Long) {
            activeDownloads.remove(downloadId)?.job?.cancel()
        }

        fun cancelAll() {
            activeDownloads.keys.toList().forEach(::cancel)
        }
    }

    private class ActiveDownload(val job: Job, val fileName: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var foregroundDownloadId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancel(intent.getLongExtra(EXTRA_DOWNLOAD_OR_INSTALL_ID, -1))
                stopIfIdle()
            }
            ACTION_DOWNLOAD, null -> intent?.let(::startDownload)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun startDownload(intent: Intent) {
        val downloadOrInstallId = intent.getLongExtra(EXTRA_DOWNLOAD_OR_INSTALL_ID, -1)
        if (downloadOrInstallId == -1L || activeDownloads.containsKey(downloadOrInstallId))
            return

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
        if (foregroundDownloadId == null) {
            foregroundDownloadId = downloadOrInstallId
            ServiceCompat.startForeground(
                this, downloadOrInstallId.toInt(), buildNotification(fileName),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }

        val job = scope.launch {
            val url = intent.getStringExtra(EXTRA_URL) ?: return@launch
            val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            val downloadDir = intent.getStringExtra(EXTRA_DOWNLOAD_DIR)
            val contentLength = if (intent.hasExtra(EXTRA_CONTENT_LENGTH))
                intent.getLongExtra(EXTRA_CONTENT_LENGTH, -1)
            else
                -1
            val uploadId = if (intent.hasExtra(EXTRA_UPLOAD_ID))
                intent.getIntExtra(EXTRA_UPLOAD_ID, 0)
            else
                null

            Downloader.performDownload(
                this@DownloadService, url, userAgent, downloadDir, fileName,
                contentLength, downloadOrInstallId, uploadId
            )
        }
        activeDownloads[downloadOrInstallId] = ActiveDownload(job, fileName)

        job.invokeOnCompletion {
            activeDownloads.remove(downloadOrInstallId)
            if (activeDownloads.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            } else if (foregroundDownloadId == downloadOrInstallId) {
                // Promote another active download to the foreground notification.
                val next = activeDownloads.entries.first()
                foregroundDownloadId = next.key
                stopForegroundCompat()
                ServiceCompat.startForeground(
                    this, next.key.toInt(), buildNotification(next.value.fileName),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        }
    }

    private fun stopIfIdle() {
        if (activeDownloads.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(fileName: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_INSTALLING).apply {
            setSmallIcon(R.drawable.ic_mitch_notification)
            setContentTitle(fileName)
            setContentText(getString(R.string.library_item_downloading))
            setOngoing(true)
        }.build()
}
