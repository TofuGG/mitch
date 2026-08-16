package garden.appl.mitch.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import garden.appl.mitch.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * This is an internal receiver which only receives broadcasts when clicking
 * the "Update available" notification, and there is one single uploadId available to install.
 */
class UpdateNotificationBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val LOGGING_TAG = "DownloadNotification"

        const val EXTRA_INSTALL_ID = "INSTALL_ID"

        // Keep the DB lookup and update start off the main thread; goAsync() keeps the
        // broadcast alive until the work completes.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(LOGGING_TAG, "onReceive")
        val extras = intent.extras ?: run {
            Log.w(LOGGING_TAG, "No extras in update notification intent, ignoring")
            return
        }

        val installId = extras.getInt(EXTRA_INSTALL_ID)

        val pendingResult = goAsync()
        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val updateCheckResult = db.updateCheckDao.getUpdateCheckResult(installId)
                    ?: run {
                        // Stale notification: the update was already installed (the row is
                        // deleted) or the check result was cleaned up. Don't crash the app.
                        Log.w(LOGGING_TAG, "No update check result for install $installId, ignoring")
                        return@launch
                    }
                GameDownloader.startUpdate(context, updateCheckResult)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
