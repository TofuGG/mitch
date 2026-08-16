package garden.appl.mitch.files

import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import garden.appl.mitch.NOTIFICATION_TAG_DOWNLOAD
import garden.appl.mitch.NOTIFICATION_TAG_DOWNLOAD_LONG
import garden.appl.mitch.Utils
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.install.Installations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * This is an internal receiver which only receives broadcasts when clicking "Cancel"
 * on a download notification
 */
class DownloadCancelBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val LOGGING_TAG = "DownloadCancelReceiver"

        const val EXTRA_DOWNLOAD_ID = "DOWNLOAD_ID"

        // The DB lookup and file cleanup must not block the main thread inside onReceive;
        // keep the broadcast alive with goAsync() until the background work is done.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = Utils.getLong(intent.extras!!, EXTRA_DOWNLOAD_ID)!!
        Log.d(LOGGING_TAG, "downloadId: $downloadId")

        val notificationManager =
            context.getSystemService(Activity.NOTIFICATION_SERVICE) as NotificationManager
        if (Utils.fitsInInt(downloadId))
            notificationManager.cancel(NOTIFICATION_TAG_DOWNLOAD, downloadId.toInt())
        else
            notificationManager.cancel(NOTIFICATION_TAG_DOWNLOAD_LONG, downloadId.toInt())

        val pendingResult = goAsync()
        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                db.installDao.getPendingInstallationByDownloadId(downloadId)?.let {
                    Installations.cancelPending(context, it)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
