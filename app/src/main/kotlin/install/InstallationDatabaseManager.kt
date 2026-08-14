package garden.appl.mitch.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.installation.Installation
import garden.appl.mitch.files.DownloadType


class InstallationDatabaseManager(val context: Context)  {
    companion object {
        private const val LOGGING_TAG = "InstallDatabaseHandler"
    }

    suspend fun onInstallResult(pendingInstall: Installation,
                                packageName: String?, status: Int
    ) {
        val db = AppDatabase.getDatabase(context)
        Log.d(LOGGING_TAG, "onInstallComplete")

        when (status) {
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                // Don't delete the row on failure: that used to silently remove the
                // game from the library. Keep it visible with a FAILURE marker so the
                // user can try downloading again from the store page.
                db.installDao.update(pendingInstall.copy(
                    status = Installation.STATUS_FAILURE,
                    downloadOrInstallId = null
                ))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Refresh the recorded version from the actually installed package so
                // that stale version strings don't cause spurious update notifications.
                val installedVersion = packageName?.let {
                    try {
                        context.packageManager.getPackageInfo(it, 0).versionName
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                val newInstall = pendingInstall.copy(
                    status = Installation.STATUS_INSTALLED,
                    downloadOrInstallId = null,
                    packageName = packageName!!,
                    version = installedVersion
                )
                Log.d(LOGGING_TAG, "New install: $newInstall")
                Installations.deleteOutdatedInstalls(context, pendingInstall)
                db.installDao.delete(pendingInstall)
                db.installDao.insert(newInstall)
            }
        }

        db.updateCheckDao.getUpdateCheckResultForUpload(pendingInstall.uploadId)?.let {
            it.isInstalling = false
            db.updateCheckDao.insert(it)
        }
    }

    suspend fun onDownloadComplete(pendingInstall: Installation, downloadType: DownloadType) {
        val db = AppDatabase.getDatabase(context)

        if (downloadType == DownloadType.INSTALL_MISC) {
            pendingInstall.status = Installation.STATUS_INSTALLED
            pendingInstall.downloadOrInstallId = null
            db.installDao.update(pendingInstall)
        } else {
            pendingInstall.status = Installation.STATUS_READY_TO_INSTALL
            db.installDao.update(pendingInstall)
        }
    }

    suspend fun onDownloadFailed(downloadId: Long) {
        val db = AppDatabase.getDatabase(context)
        Log.d(LOGGING_TAG, "onDownloadFailed")

        val pendingInstall =
            db.installDao.getPendingInstallationByDownloadId(downloadId) ?: return
        // Keep the game visible in the library instead of silently deleting it.
        db.installDao.update(pendingInstall.copy(
            status = Installation.STATUS_FAILURE,
            downloadOrInstallId = null
        ))
    }

    suspend fun onInstallStart(downloadId: Long, pendingInstallId: Long) {
        Log.d(LOGGING_TAG, "onInstallStart")

        val db = AppDatabase.getDatabase(context)
        val pendingInstall = db.installDao.getPendingInstallationByDownloadId(downloadId)!!

        pendingInstall.status = Installation.STATUS_INSTALLING
        pendingInstall.downloadOrInstallId = pendingInstallId
        db.installDao.update(pendingInstall)
    }

    suspend fun onInstallStart(sessionId: Int) {
        onInstallStart(sessionId.toLong(), sessionId.toLong())
    }
}