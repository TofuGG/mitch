package garden.appl.mitch.install

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.preference.PreferenceManager
import garden.appl.mitch.Mitch
import garden.appl.mitch.MiuiUtils
import garden.appl.mitch.NOTIFICATION_CHANNEL_ID_INSTALLING
import garden.appl.mitch.NOTIFICATION_TAG_DOWNLOAD
import garden.appl.mitch.NOTIFICATION_TAG_DOWNLOAD_LONG
import garden.appl.mitch.NOTIFICATION_TAG_INSTALL_RESULT
import garden.appl.mitch.NOTIFICATION_TAG_INSTALL_RESULT_LONG
import garden.appl.mitch.PREF_INSTALLER
import garden.appl.mitch.R
import garden.appl.mitch.Utils
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.installation.Installation
import garden.appl.mitch.ui.MitchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Installations {
    private const val LOGGING_TAG = "Installations"
    val nativeInstaller = NativeInstaller()
    val sessionInstaller = SessionInstaller()


    suspend fun deleteFinishedInstall(context: Context, uploadId: Int) {
        val db = AppDatabase.getDatabase(context)
        db.installDao.deleteFinishedInstallation(uploadId)

        withContext(Dispatchers.IO) {
            Mitch.installDownloadManager.deleteDownloadedFile(uploadId)
        }
    }

    suspend fun deleteOutdatedInstalls(context: Context, pendingInstall: Installation) {
        deleteFinishedInstall(context, pendingInstall.uploadId)

        if (pendingInstall.availableUploadIds == null)
            return

        val db = AppDatabase.getDatabase(context)
        val finishedInstalls =
            db.installDao.getFinishedInstallationsForGame(pendingInstall.gameId)

        for (finishedInstall in finishedInstalls) {
            if (!pendingInstall.availableUploadIds.contains(finishedInstall.uploadId))
                deleteFinishedInstall(context, finishedInstall.uploadId)
        }
    }

    suspend fun cancelPending(context: Context, pendingInstall: Installation) {
        val downloadOrInstallId = pendingInstall.downloadOrInstallId
        if (downloadOrInstallId == null) {
            // No active download/install session behind this row anymore; just remove it.
            AppDatabase.getDatabase(context).installDao.delete(pendingInstall.internalId)
            return
        }
        cancelPending(
            context,
            pendingInstall.status,
            downloadOrInstallId,
            pendingInstall.uploadId,
            pendingInstall.internalId
        )
    }

    suspend fun cancelPending(
        context: Context,
        status: Int,
        downloadOrInstallId: Long,
        uploadId: Int,
        installId: Int
    ) {
        if (status == Installation.STATUS_INSTALLED || status == Installation.STATUS_WEB_CACHED)
            throw IllegalArgumentException("Tried to cancel installed Installation")

        val db = AppDatabase.getDatabase(context)

        // A failed install/download has no active session anymore, just remove the marker.
        if (status == Installation.STATUS_FAILURE) {
            db.installDao.delete(installId)
            return
        }

        if (status != Installation.STATUS_INSTALLING) {
            val notificationService =
                context.getSystemService(Activity.NOTIFICATION_SERVICE) as NotificationManager
            if (Utils.fitsInInt(downloadOrInstallId))
                notificationService.cancel(NOTIFICATION_TAG_DOWNLOAD, downloadOrInstallId.toInt())
            else
                notificationService.cancel(NOTIFICATION_TAG_DOWNLOAD_LONG, downloadOrInstallId.toInt())
        }

        db.updateCheckDao.getUpdateCheckResultForUpload(uploadId)?.let {
            it.isInstalling = false
            db.updateCheckDao.insert(it)
        }

        if (status == Installation.STATUS_INSTALLING) {
            if (getInstaller(downloadOrInstallId).tryCancel(context, downloadOrInstallId))
                return
        }

        if (status == Installation.STATUS_DOWNLOADING) {
            Log.d(LOGGING_TAG, "Cancelling $downloadOrInstallId")
            Mitch.installDownloadManager.cancel(context, downloadOrInstallId, uploadId)
        } else {
            withContext(Dispatchers.IO) {
                Mitch.installDownloadManager.deletePendingFile(uploadId)
            }
        }
        db.installDao.delete(installId)
    }

    fun getInstaller(installId: Long): AbstractInstaller {
        return if (Utils.fitsInInt(installId))
            sessionInstaller
        else
            nativeInstaller
    }

    fun getInstaller(context: Context): AbstractInstaller {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        val defaultInstaller = if (MiuiUtils.doesSessionInstallerWork())
            "session"
        else
            "native"

        return if (sharedPrefs.getString(PREF_INSTALLER, defaultInstaller) == "native")
            nativeInstaller
        else
            sessionInstaller
    }

    suspend fun onInstallResult(context: Context, installId: Long, appName: String, appIcon: Drawable?,
                                packageName: String?, apk: File?, status: Int) {
        var packageName = packageName

        val db = AppDatabase.getDatabase(context)
        val install = db.installDao.getPendingInstallationByInstallId(installId)

        //TODO: this seems to only happen when
        // 1. we request permission to install
        // 2. Android launches installation twice???
        // 3. First one finishes, pending install is deleted
        // 4. Second one fails and install is null
        // This is a workaround, but is there a better solution? Or maybe the bug is my fault?
        if (install == null)
            return

        if (status == PackageInstaller.STATUS_SUCCESS && packageName == null) {
            if (install.packageName != null)
                packageName = install.packageName
            else
                packageName = tryGetPackageName(context, apk!!.path)!!
        }

        // On a signature conflict the PackageInstaller callback does not report the target
        // package, but we recorded it while streaming/downloading the APK. Without it the
        // user can't be told *which* app conflicts and the fix is a dead end.
        // (mentioned in https://itch.io/t/3102637/error-when-trying-to-install-update)
        if (status == PackageInstaller.STATUS_FAILURE_CONFLICT && packageName == null)
            packageName = install.packageName

        notifyInstallResult(context, installId, packageName, appName, appIcon, status)
        Mitch.installDownloadManager.deletePendingFile(install.uploadId)
        Mitch.databaseHandler.onInstallResult(install, packageName, status)
    }

    /**
     * This method should *NOT* depend on the AppDatabase because this could be used for
     * the GitLab build update check, or other things
     */
    private fun notifyInstallResult(context: Context, installSessionId: Long, packageName: String?,
                                    appName: String, appIcon: Drawable?, status: Int) {
        val message = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> context.resources.getString(R.string.notification_install_cancelled_title)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> context.resources.getString(R.string.notification_install_blocked_title)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> context.resources.getString(R.string.notification_install_conflict_title)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> context.resources.getString(R.string.notification_install_incompatible_title)
            PackageInstaller.STATUS_FAILURE_INVALID -> context.resources.getString(R.string.notification_install_invalid_title)
            PackageInstaller.STATUS_FAILURE_STORAGE -> context.resources.getString(R.string.notification_install_storage_title)
            PackageInstaller.STATUS_SUCCESS -> context.resources.getString(R.string.notification_install_complete_title)
            else -> context.resources.getString(R.string.notification_install_unknown_title)
        }
        val builder =
            NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID_INSTALLING).apply {
                setSmallIcon(R.drawable.ic_mitch_notification)
                setContentText(message)

                if (status == PackageInstaller.STATUS_SUCCESS) {
                    try {
                        val appInfo = context.packageManager.getApplicationInfo(packageName!!, 0)
                        setContentTitle(context.packageManager.getApplicationLabel(appInfo))
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(LOGGING_TAG, "Error: no name for package name $packageName", e)
                        setContentTitle(appName)
                    }

                    packageName?.let {
                        context.packageManager.getLaunchIntentForPackage(it)?.also { intent ->
                            val pendingIntent =
                                PendingIntentCompat.getActivity(context, 0, intent, 0, false)
                            setContentIntent(pendingIntent)
                            setAutoCancel(true)
                        }
                    }

                    if (appIcon != null) {
                        setLargeIcon(Utils.drawableToBitmap(appIcon))
                    } else {
                        try {
                            val icon = context.packageManager.getApplicationIcon(packageName!!)
                            setLargeIcon(Utils.drawableToBitmap(icon))
                        } catch (e: PackageManager.NameNotFoundException) {
                            Log.w(LOGGING_TAG, "Could not load icon for package name $packageName", e)
                        }
                    }
                } else {
                    setContentTitle(appName)
                }
//                priority = NotificationCompat.PRIORITY_HIGH
            }

            if (status == PackageInstaller.STATUS_FAILURE_CONFLICT && packageName != null) {
                // Point the user straight at the conflicting app so they can uninstall it
                // and retry (Android refuses same-package, different-signature installs).
                val detailsIntent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null))
                val detailsPendingIntent = PendingIntentCompat.getActivity(
                    context, 0, detailsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, false)
                builder.addAction(
                    0,
                    context.resources.getString(R.string.notification_install_conflict_action_uninstall),
                    detailsPendingIntent
                )
            }

        val tag = if (Utils.fitsInInt(installSessionId))
            NOTIFICATION_TAG_INSTALL_RESULT
        else
            NOTIFICATION_TAG_INSTALL_RESULT_LONG
        MitchActivity.tryNotifyWithPermission(
            null, context, null,
            tag, installSessionId.toInt(), builder.build(),
            R.string.dialog_notification_explain_download,
            R.string.dialog_notification_cancel_download
        )
    }

    suspend fun tryUpdatePendingInstallData(context: Context, installId: Long, apk: File) {
        val db = AppDatabase.getDatabase(context)
        val install = db.installDao.getPendingInstallationByInstallId(installId)!!
        db.installDao.update(install.copy(
            packageName = tryGetPackageName(context, apk.path)
        ))
    }

    private fun tryGetPackageName(context: Context, apkPath: String): String? {
        Log.d(LOGGING_TAG, "Looking at package info for $apkPath")
        val packageInfo = context.packageManager.getPackageArchiveInfo(apkPath, 0)
        Log.d(LOGGING_TAG, "pkg info: $packageInfo")
        return packageInfo?.packageName
    }
}