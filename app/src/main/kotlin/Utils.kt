package garden.appl.mitch

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.util.Log
import android.webkit.URLUtil
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.work.Data
import com.github.ajalt.colormath.ConvertibleColor
import com.github.ajalt.colormath.fromCss
import garden.appl.mitch.files.DataURL
import jodd.net.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import tofu.gg.mitchy.BuildConfig
import tofu.gg.mitchy.R
import java.util.Locale


object Utils {
    class ErrorReport(message: String) : Throwable(message)

    private const val LOGGING_TAG = "Utils"
    private val versionNumbersRegex = Regex("""(?:\.?\d+)+""")

    suspend fun cancellableCopy(
        input: InputStream,
        output: OutputStream,
        progressCallback: ((Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val BUFFER_SIZE = 1024 * 1024

        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Long = 0
        while (true) {
            ensureActive()
            val count = input.read(buffer)
            if (count == -1)
                break
            bytesRead += count

            output.write(buffer, 0, count)
            progressCallback?.invoke(bytesRead)
        }
        output.flush()
    }

    //https://stackoverflow.com/a/10600736/5701177
    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null)
            return drawable.bitmap

        val bitmap: Bitmap
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            // Single color bitmap will be created of 1x1 pixel
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } else {
            bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun toString(bundle: Bundle?): String {
        if (bundle == null)
            return "null"

        val sb = StringBuilder()
        sb.append("[ ")
        for (key in bundle.keySet()) {
            sb.append("$key = ${bundle.get(key)}, ")
        }
        sb.append(" ]")
        return sb.toString()
    }

    fun toString(e: Throwable?): String {
        if (e == null)
            return "Throwable is null"

        val errorWriter = StringWriter()
        errorWriter.appendLine(e.localizedMessage)
        e.printStackTrace(PrintWriter(errorWriter))

        e.cause?.let { cause ->
            errorWriter.append("Cause: ")
            errorWriter.append(toString(cause))
        }

        return errorWriter.toString()
    }

    /**
     * Wrapper method for external library
     * TODO: minimal CSS color parsing without library?
     */
    fun parseCssColor(color: String): Int {
        return ConvertibleColor.fromCss(color).toRGB().toPackedInt()
    }


    fun colorStateListOf(vararg mapping: Pair<IntArray, Int>): ColorStateList {
        val (states, colors) = mapping.unzip()
        return ColorStateList(states.toTypedArray(), colors.toIntArray())
    }

    fun colorStateListOf(@ColorInt color: Int): ColorStateList {
        return ColorStateList.valueOf(color)
    }

    /**
     * Similar to ContextCompat.getColor, except also aware of light/dark themes
     */
    @ColorInt
    @Suppress("DEPRECATION")
    fun getColor(context: Context, @ColorRes id: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getColor(id)
        } else {
            val nightMode =
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                when (id) {
                    R.color.colorBackground -> context.resources.getColor(R.color.colorPrimaryDark)
                    R.color.colorForeground -> context.resources.getColor(R.color.colorPrimary)
                }
            } else {
                when (id) {
                    R.color.colorBackground -> context.resources.getColor(R.color.colorPrimary)
                    R.color.colorForeground -> context.resources.getColor(R.color.colorPrimaryDark)
                }
            }
            context.resources.getColor(id)
        }
    }

    fun getIntentForFile(context: Context, file: File, fileProvider: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = getIntentUriForFile(context, file, fileProvider)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun getIntentUriForFile(context: Context, file: File, fileProvider: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            FileProvider.getUriForFile(context, fileProvider, file)
        else
            Uri.fromFile(file)
    }

    fun getInt(bundle: Bundle, key: String): Int? {
        return if (bundle.containsKey(key))
            bundle.getInt(key)
        else
            null
    }

    fun getInt(data: Data, key: String): Int? {
        return data.keyValueMap[key] as? Int
    }

    fun getLong(bundle: Bundle, key: String): Long? {
        return if (bundle.containsKey(key))
            bundle.getLong(key)
        else
            null
    }

    fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            // An app that exists but is disabled is still installed; only a
            // missing package should count as "not installed". Otherwise the
            // 24h cleanup would silently drop games from the library.
            packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun fitsInInt(l: Long): Boolean {
        return l.toInt().toLong() == l
    }

    /**
     * Checks whether [file] is a structurally valid ZIP archive (APKs are ZIP files).
     * Used to detect truncated or corrupted downloads before they get installed.
     */
    fun isValidZip(file: File): Boolean = try {
        java.util.zip.ZipFile(file).use { true }
    } catch (e: Exception) {
        Log.d("Utils", "File is not a valid ZIP archive: ${file.name}", e)
        false
    }

    /**
     * Some Android games ship inside a .zip archive containing the APK rather than as a
     * bare .apk file. Extracts the largest `.apk` entry from [zipFile] into the same
     * directory and deletes the archive on success, or returns null if there is no APK inside.
     */
    fun extractApkFromZip(zipFile: File?): File? {
        if (zipFile == null || !zipFile.isFile || !isValidZip(zipFile))
            return null
        return try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val apkEntry = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .maxByOrNull { it.size }
                    ?: return null

                val baseName = apkEntry.name.substringAfterLast('/')
                    .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                    .ifEmpty { "game.apk" }
                val outFile = File(zipFile.parentFile, baseName)

                FileOutputStream(outFile).use { fos ->
                    zip.getInputStream(apkEntry).use { input -> input.copyTo(fos) }
                }

                if (isValidZip(outFile)) {
                    zipFile.delete()
                    outFile
                } else {
                    outFile.delete()
                    null
                }
            }
        } catch (e: Exception) {
            Log.d("Utils", "Failed to extract APK from ${zipFile.name}", e)
            null
        }
    }

    /**
     * Check if we're connected to some type of Internet network. Doesn't necessarily mean that
     * the connection is working!
     *
     * https://stackoverflow.com/a/53532456/5701177
     */
    fun isNetworkConnected(context: Context, requireUnmetered: Boolean = false): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val networkCapabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && (!requireUnmetered
                        || networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return when (networkInfo.type) {
                ConnectivityManager.TYPE_MOBILE -> !requireUnmetered && networkInfo.isConnected
                else -> networkInfo.isConnected
            }
        }
    }

    fun spannedFromHtml(htmlString: String): Spanned {
        if (Build.VERSION.SDK_INT >= 24) {
            return Html.fromHtml(htmlString, 0)
        } else {
            @Suppress("DEPRECATION")
            return Html.fromHtml(htmlString)
        }
    }

    fun asHexCode(@ColorInt color: Int): String {
        // https://stackoverflow.com/a/6540378/5701177
        return String.format("#%06X", 0xFFFFFF and color)
    }

    fun getPreferredLocale(config: Configuration): Locale {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
            return config.locales.get(0)
        } else {
            @Suppress("deprecation")
            return config.locale
        }
    }

    fun getPreferredLocale(context: Context): Locale {
        return getPreferredLocale(context.resources.configuration)
    }

    fun makeLocalizedContext(context: Context, locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun shouldUseLightForeground(@ColorInt bgColor: Int): Boolean {
        return ColorUtils.calculateLuminance(bgColor) < 0.5
    }

    // Converted from Java:
    // https://github.com/Aefyr/SAI/blob/55505d231b1390e824d1cc0c8f4fa35fd4677105/app/src/main/java/com/aefyr/sai/utils/Utils.java#L68
    @SuppressLint("PrivateApi")
    fun tryGetSystemProperty(key: String?): String? {
        try {
            return Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java)
                .invoke(null, key) as String
        } catch (e: Exception) {
            Log.w(LOGGING_TAG, "Unable to use SystemProperties.get", e)
            return null
        }
    }

    private fun parseVersionIntoParts(version: String): IntArray? {
        val versionNumbers = versionNumbersRegex.find(version)?.value ?: return null

        return versionNumbers.split('.').map { it.toInt() }.toIntArray()
    }

    /**
     * @return 0 if versions are equal, values less than 0 if ver1 is lower than ver2, value more than 0 if ver1 is higher than ver2
     */
    fun compareVersions(version1: String, version2: String): Int? {
        if (version1 == version2) return 0
        val version1Parts = parseVersionIntoParts(version1) ?: return null
        val version2Parts = parseVersionIntoParts(version2) ?: return null
        val minSize = minOf(version1Parts.size, version2Parts.size)
        for (i in 0 until minSize) {
            if (version1Parts[i] < version2Parts[i]) return -1
            if (version1Parts[i] > version2Parts[i]) return 1
        }
        // The shorter version's trailing components are implicitly zero: "1.0" equals "1.0.0",
        // otherwise a trailing-zero difference would trigger a spurious "update available".
        if (version1Parts.size < version2Parts.size) {
            for (i in minSize until version2Parts.size)
                if (version2Parts[i] != 0) return -1
            return 0
        }
        if (version1Parts.size > version2Parts.size) {
            for (i in minSize until version1Parts.size)
                if (version1Parts[i] != 0) return 1
            return 0
        }
        return 0
    }

    fun isVersionNewer(maybeNewerVersion: String, currentVersion: String): Boolean? {
        val comparisonResult = compareVersions(maybeNewerVersion, currentVersion) ?: return null
        return comparisonResult > 0
    }

    fun <T : Service> checkServiceRunning(context: Context, serviceClass: Class<T>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun logDebug(tag: String, string: String) {
        if (BuildConfig.DEBUG) {
            for (s in string.chunked(1000)) {
                Log.d(tag, s)
            }
        }
    }

    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        return if (DataURL.isValid(url)) {
            val extension = mimeType?.let {
                val fixedMimeType = if (mimeType == "text/json")
                    "application/json"
                else
                    mimeType
                MimeTypes.findExtensionsByMimeTypes(fixedMimeType, false)
                    .firstOrNull()
            }
            if (extension == null) {
                "download"
            } else {
                "download.$extension"
            }
        } else if (mimeType == "application/octet-stream") {
            URLUtil.guessFileName(url, contentDisposition, null)
        } else {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }
    }

    /**
     * itch.io HTML5 uploads can declare a fixed screen orientation, which the site encodes as an
     * `orientation` query parameter on the game's embed URL (stored as [Game.webEntryPoint], e.g.
     * https://itch.io/embed-upload/123?orientation=landscape_left). The game player uses the
     * returned value to rotate the screen to match the developer's intent instead of running
     * landscape-designed games in portrait mode.
     *
     * Kept framework-free (no android.net.Uri) so it can be exercised by unit tests.
     *
     * @return one of "portrait", "landscape", "landscape_left", "landscape_right",
     *         or null when the game declares no orientation.
     */
    fun parseWebGameOrientation(entryPointUrl: String?): String? {
        if (entryPointUrl == null)
            return null
        return try {
            val orientation = getQueryParameter(entryPointUrl, "orientation")
                ?.replace(" ", "_")
            when (orientation) {
                "portrait", "landscape", "landscape_left", "landscape_right" -> orientation
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getQueryParameter(url: String, key: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart == url.length - 1)
            return null
        val queryEnd = url.indexOf('#', queryStart + 1)
        val query = if (queryEnd < 0)
            url.substring(queryStart + 1)
        else
            url.substring(queryStart + 1, queryEnd)
        for (part in query.split('&')) {
            if (part.startsWith("$key=")) {
                return java.net.URLDecoder.decode(part.substring(key.length + 1), "UTF-8")
            }
        }
        return null
    }
}
