package garden.appl.mitch.files

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export/import the app's WebView data (cookies, localStorage, IndexedDB, service workers)
 * as a zip file. All web games share this storage, so this covers a game's save data too.
 * See https://itch.io/t/4677526/import-saves-how
 */
object WebViewDataBackup {
    private const val LOGGING_TAG = "WebViewDataBackup"

    const val PENDING_BROWSER_DATA_ZIP = "pending_browser_data.zip"
    const val EXPORT_ZIP_NAME = "mitch_browser_data.zip"

    fun getWebViewDataDir(context: Context): File = File(context.dataDir, "app_webview")

    /**
     * Zip the contents of the WebView data directory into [destZip].
     * @return true on success, false if there is nothing to export or zipping failed.
     */
    fun exportData(context: Context, destZip: File): Boolean {
        val dir = getWebViewDataDir(context)
        val files = dir.listFiles() ?: return false
        if (files.isEmpty())
            return false
        return try {
            ZipOutputStream(destZip.outputStream().buffered()).use { zos ->
                dir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val entryName = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(LOGGING_TAG, "Could not export browser data", e)
            destZip.delete()
            false
        }
    }

    /**
     * Replace the WebView data directory with the contents of [zipFile].
     * Should be called before any WebView is created (i.e. at app startup).
     * @return true on success.
     */
    fun importData(context: Context, zipFile: File): Boolean {
        val dir = getWebViewDataDir(context)
        return try {
            dir.deleteRecursively()
            dir.mkdirs()
            val dirCanonical = dir.canonicalPath + File.separatorChar
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                var ok = true
                while (entry != null) {
                    val target = File(dir, entry.name)
                    if (!target.canonicalPath.startsWith(dirCanonical)) {
                        Log.e(LOGGING_TAG, "Refusing to extract zip entry outside data dir: ${entry.name}")
                        ok = false
                        break
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                ok
            }
        } catch (e: Exception) {
            Log.e(LOGGING_TAG, "Could not import browser data", e)
            dir.deleteRecursively()
            false
        }
    }
}
