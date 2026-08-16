package garden.appl.mitch.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import garden.appl.mitch.Utils
import garden.appl.mitch.install.InstallRequestBroadcastReceiver.Companion.EXTRA_APK_NAME
import garden.appl.mitch.install.InstallRequestBroadcastReceiver.Companion.EXTRA_DOWNLOAD_ID
import garden.appl.mitch.install.InstallRequestBroadcastReceiver.Companion.EXTRA_STREAM_SESSION_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * This is an internal receiver which only receives broadcasts when clicking the "Click to install" notification.
 *
 * Supply either:
 * [EXTRA_DOWNLOAD_ID] (for [AbstractInstaller.Type.File])
 * or
 * [EXTRA_STREAM_SESSION_ID] and [EXTRA_APK_NAME] (for [AbstractInstaller.Type.Stream])
 */
class InstallRequestBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val LOGGING_TAG = "InstallRequestReceiver"

        const val EXTRA_DOWNLOAD_ID = "DOWNLOAD_ID"
        const val EXTRA_STREAM_SESSION_ID = "stream_id"
        const val EXTRA_APK_NAME = "app_name"

        // Installing a file or finishing a streamed install reads the APK off disk and talks
        // to the package installer; neither should block the main thread in onReceive.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(LOGGING_TAG, "onReceive")
        val extras = intent.extras!!

        // Extract everything the background work needs while the Intent is still valid.
        val downloadId = Utils.getLong(extras, EXTRA_DOWNLOAD_ID)
        val sessionId = Utils.getLong(extras, EXTRA_STREAM_SESSION_ID)
        val apkFile = if (downloadId != null) File(intent.data!!.path!!) else null
        val apkName = if (sessionId != null) extras.getString(EXTRA_APK_NAME) else null

        val pendingResult = goAsync()
        scope.launch {
            try {
                if (downloadId != null) {
                    val installer = Installations.getInstaller(downloadId)
                    assert(installer.type == AbstractInstaller.Type.File)

                    installer.requestInstall(context, downloadId, apkFile!!)
                    return@launch
                }
                if (sessionId != null) {
                    val installer = Installations.getInstaller(sessionId)
                    assert(installer.type == AbstractInstaller.Type.Stream)

                    installer.finishStreamInstall(context, sessionId.toInt(), apkName!!)
                    return@launch
                }
                throw IllegalStateException("Must provide either EXTRA_DOWNLOAD_ID or EXTRA_STREAM_SESSION_ID")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
