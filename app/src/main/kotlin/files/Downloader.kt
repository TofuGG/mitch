package garden.appl.mitch.files

import android.content.Context
import android.content.Intent
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import garden.appl.mitch.HEADER_UA
import garden.appl.mitch.Mitch
import garden.appl.mitch.NOTIFICATION_TAG_DOWNLOAD
import garden.appl.mitch.NOTIFICATION_TAG_DOWNLOAD_LONG
import tofu.gg.mitchy.R
import garden.appl.mitch.Utils
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.installation.Installation
import garden.appl.mitch.install.AbstractInstaller
import garden.appl.mitch.install.InstallationDownloadFileListener
import garden.appl.mitch.install.Installations
import garden.appl.mitch.install.SessionInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Downloader {
    private const val LOGGING_TAG = "Downloader"
    private val installationListener = InstallationDownloadFileListener()
    private val normalListener = DownloadFileListener()

    private fun getListener(type: DownloadType): DownloadFileListener {
        return when (type) {
            DownloadType.NORMAL_FILE -> normalListener
            else -> installationListener
        }
    }

    fun getNormalDownloadPath(context: Context, downloadId: Long) =
        File(File(context.filesDir, "misc_download"), downloadId.toString())

    private val unusedDownloadIdMutex = Mutex()
    private val downloadIdCounter = AtomicLong(Int.MAX_VALUE.toLong())

    private suspend fun getUnusedDownloadId(context: Context): Long =
        unusedDownloadIdMutex.withLock {
            val db = AppDatabase.getDatabase(context)
            var id: Long
            while (true) {
                id = downloadIdCounter.incrementAndGet()
                if (id > Int.MAX_VALUE.toLong() * 2) {
                    downloadIdCounter.set(Int.MAX_VALUE.toLong())
                    continue
                }
                // Don't collide with an in-flight download or with a pending DB row that a dead
                // process left behind before the daily cleanup had a chance to remove it.
                if (DownloadService.isDownloading(id)) continue
                if (db.installDao.getPendingInstallationByDownloadId(id) != null) continue
                break
            }
            id
        }

    /**
     * @param contentLength file size, null if unknown
     * @param installer null if we are downloading into a file
     * @param downloadDir null if [installer] has type [AbstractInstaller.Type.Stream] or if [tempDownloadDir]
     */
    suspend fun requestDownload(
        context: Context,
        url: String,
        userAgent: String?,
        install: Installation?,
        fileName: String,
        contentLength: Long?,
        downloadDir: File?,
        tempDownloadDir: Boolean,
        installer: AbstractInstaller?
    ) {
        val id = if (installer != null)
            installer.createSessionForStreamInstall(context).toLong()
        else
            getUnusedDownloadId(context)

        val downloadDir = if (tempDownloadDir)
            getNormalDownloadPath(context, id)
        else
            downloadDir

        if (install != null) {
            Log.d(LOGGING_TAG, "Download or stream install ID: $id")
            install.downloadOrInstallId = id
            install.status = Installation.STATUS_DOWNLOADING

            val db = AppDatabase.getDatabase(context)
            db.installDao.upsert(install)
        }

        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_USER_AGENT, userAgent)
            putExtra(DownloadService.EXTRA_DOWNLOAD_DIR, downloadDir?.path)
            putExtra(DownloadService.EXTRA_FILE_NAME, fileName)
            if (contentLength != null)
                putExtra(DownloadService.EXTRA_CONTENT_LENGTH, contentLength)
            putExtra(DownloadService.EXTRA_DOWNLOAD_OR_INSTALL_ID, id)
            install?.let { putExtra(DownloadService.EXTRA_UPLOAD_ID, it.uploadId) }
        }
        // A real foreground service keeps the download alive while the app is in the background
        // or removed from the recents screen. WorkManager's expedited workers get stopped there
        // (background time cap, OEM battery savers) and silently lose the download, leaving a
        // stale progress notification behind. https://itch.io/t/5280860/app-doesnt-install-games
        ContextCompat.startForegroundService(context, intent)
    }

    suspend fun cancel(context: Context, downloadId: Long): Boolean {
        DownloadService.cancel(downloadId)
        return true
    }

    suspend fun checkIsDownloading(context: Context, downloadId: Long): Boolean =
        DownloadService.isDownloading(downloadId)

    fun cancelAll(context: Context) {
        DownloadService.cancelAll()
    }

    /**
     * Runs a single download, calling the right [DownloadFileListener] callbacks on
     * completion, error, or cancellation. Executed by [DownloadService]; the caller's
     * coroutine is cancelled by the user pressing "Cancel" on the progress notification.
     */
    suspend fun performDownload(
        context: Context,
        url: String,
        userAgent: String?,
        downloadDir: String?,
        fileName: String,
        contentLength: Long,
        downloadOrInstallId: Long,
        uploadId: Int?
    ) {
        val downloadType = if (downloadDir == null)
            DownloadType.INSTALL_SESSION
        else if (uploadId == null)
            DownloadType.NORMAL_FILE
        else if (fileName.endsWith(".apk"))
            DownloadType.INSTALL_APK
        else
            DownloadType.INSTALL_MISC
        Log.d(LOGGING_TAG, "Download type: $downloadType")
        val listener = getListener(downloadType)

        try {
            Log.d(LOGGING_TAG, "content length: $contentLength")
            if (downloadDir != null) {
                File("${downloadDir}/").mkdirs()

                if (StatFs(downloadDir).availableBytes <= contentLength)
                    throw SessionInstaller.NotEnoughSpaceException()
            }

            val outputStream = if (downloadDir != null) {
                val file = File(downloadDir, fileName)

                FileOutputStream(file, false)
            } else {
                val installer = Installations.getInstaller(downloadOrInstallId)

                installer.openWriteStream(
                    context,
                    downloadOrInstallId.toInt(),
                    contentLength
                )
            }

            if (DataURL.isValid(url)) {
                listener.onProgress(context, fileName, downloadOrInstallId, null)
                Utils.cancellableCopy(DataURL(url).toInputStream(), outputStream)
                listener.onCompleted(context, fileName, uploadId, downloadOrInstallId, downloadType)

                return
            }

            val request = Request.Builder().run {
                url(url)
                userAgent?.let { header(HEADER_UA, it) }
                get()
                build()
            }

            val response = suspendCancellableCoroutine { cont ->
                Mitch.httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        //TODO: use experimental API for safer close
                        cont.invokeOnCancellation {
                            response.close()
                        }
                        cont.resume(response)
                    }
                })
            }

            response.use { response ->
                listener.onProgress(context, fileName, downloadOrInstallId, null)

                outputStream.use { output ->
                    download(context, response, output, fileName, downloadOrInstallId, listener)
                }

                // Verify the file wasn't truncated or corrupted while downloading.
                // Installing a partial APK would otherwise end in Android's cryptic
                // "There was a problem parsing the package" error.
                if (downloadDir != null) {
                    val file = File(downloadDir, fileName)
                    if (downloadType == DownloadType.INSTALL_APK
                        || downloadType == DownloadType.INSTALL_MISC) {
                        if (contentLength > 0 && file.length() != contentLength)
                            throw IOException(
                                "Download incomplete: got ${file.length()} of $contentLength bytes")
                        if (downloadType == DownloadType.INSTALL_APK && !Utils.isValidZip(file))
                            throw IOException("Downloaded file is not a valid APK")
                        if (downloadType == DownloadType.INSTALL_MISC && fileName.endsWith(".zip")
                            && !Utils.isValidZip(file))
                            throw IOException("Downloaded file is not a valid archive")
                    }
                }

                with(NotificationManagerCompat.from(context)) {
                    if (Utils.fitsInInt(downloadOrInstallId))
                        cancel(NOTIFICATION_TAG_DOWNLOAD, downloadOrInstallId.toInt())
                    else
                        cancel(NOTIFICATION_TAG_DOWNLOAD_LONG, downloadOrInstallId.toInt())
                }

                //Add delay because if you send the completion notification
                //right after a progress notification, sometimes it doesn't show up
                delay(500)

                listener.onCompleted(context,
                        fileName, uploadId, downloadOrInstallId, downloadType)
            }
        } catch (_: CancellationException) {
            // The user cancelled: DownloadService cancelled this download's job.
            listener.onCancel(context, downloadOrInstallId)
        } catch (e: Exception) {
            Log.e(LOGGING_TAG, "Caught while downloading", e)
            val errorName = when (e) {
                is SessionInstaller.NotEnoughSpaceException ->
                    if (fileName.endsWith(".apk"))
                        R.string.dialog_installer_no_space
                    else
                        R.string.notification_download_no_space
                is IOException -> R.string.notification_download_io_error
                else -> R.string.notification_download_unknown_error
            }
            listener.onError(
                context, fileName, uploadId, downloadOrInstallId, downloadType,
                e.localizedMessage ?: context.getString(errorName), e
            )
        }
    }

    private suspend fun download(
        context: Context, response: Response, outputStream: OutputStream, fileName: String,
        downloadId: Long, listener: DownloadFileListener
    ) = withContext(Dispatchers.IO) {
        val totalBytes = response.body.contentLength()
        var progressPercent: Long = 0

        val body = response.body

        // No extra BufferedInputStream: Utils.cancellableCopy already reads through its own
        // 1 MB buffer, so a second (8 KB) buffering layer would only waste memory.
        body.byteStream().use { inputStream ->
            Utils.cancellableCopy(inputStream, outputStream) { bytesRead ->
                val currentProgress: Long =
                    if (totalBytes > 0) 100 * bytesRead / totalBytes else 0
                if (currentProgress != progressPercent) {
                    listener.onProgress(context,
                            fileName, downloadId, currentProgress.toInt())
                    progressPercent = currentProgress
                }
            }
        }
    }
}
