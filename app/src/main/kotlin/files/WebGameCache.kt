package garden.appl.mitch.files

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import garden.appl.mitch.Mitch
import garden.appl.mitch.Utils
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.installation.Installation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebGameCache(context: Context) {
    companion object {
        private const val LOGGING_TAG = "WebGameCache"

        // Query parameter names itch.io and friends use for cache busting (e.g. ?v=123).
        // They don't change what the URL returns, but they DO change OkHttp's cache key, so a
        // drifted version would miss when playing offline. We strip them for fallback lookups.
        private val CACHE_BUSTER_PARAM_NAMES =
            setOf("v", "t", "cache", "timestamp", "_", "ver", "version")

        // Don't prefetch the unversioned copy of a document bigger than this: self-contained
        // exports (wasm inlined as data URIs) can be tens of megabytes and aren't worth doubling.
        private const val MAX_PREFETCH_BYTES = 512 * 1024L
    }

    private val cacheDirLegacy = File(context.cacheDir, "webgames")
    private val cacheDir by lazy { context.getDir("webgames", Context.MODE_PRIVATE) }
    private val cacheHttpClients = HashMap<Int, OkHttpClient>()

    suspend fun request(
        gameId: Int,
        request: WebResourceRequest,
        isOfflineMode: Boolean
    ): WebResourceResponse? = withContext(Dispatchers.IO) {
        val url = request.url.toString()
//        Utils.logDebug(LOGGING_TAG, "$url, force cache?: $isOfflineMode")
        val httpRequest = Request.Builder().run {
            url(url)
            headers(request.requestHeaders.toHeaders())
            get()
            build()
        }
        val httpClient = getOkHttpClientForGame(gameId)

        request(httpClient, httpRequest, forceCache = isOfflineMode)
    }

    private suspend fun request(httpClient: OkHttpClient, request: Request, forceCache: Boolean): WebResourceResponse? {
        val httpRequest = request.newBuilder().apply {
            // OkHttp's cache cannot store or serve ranged responses, and Godot/Emscripten issue
            // ranged fetches (instantiateStreaming) for the wasm. Serving the whole body to a
            // ranged request is always valid and makes those responses cacheable for offline play.
            if (request.headers("Range").isNotEmpty())
                removeHeader("Range")

            if (forceCache)
                cacheControl(CacheControl.FORCE_CACHE)
            else
                cacheControl(CacheControl.Builder().run {
                    minFresh(10, TimeUnit.MINUTES)
                    build()
                })

            // A bad workaround for https://todo.sr.ht/~gardenapple/mitch/31
            if (!request.url.host.endsWith(".hwcdn.net")
                && (request.url.encodedPath.endsWith(".ttf")
                    || request.url.encodedPath.endsWith(".woff")
                    || request.url.encodedPath.endsWith(".woff2"))) {

                header("Host", request.url.host)
                header("Sec-Fetch-Dest", "font")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "cross-site")
            }
        }.build()

        val response = suspendCancellableCoroutine { continuation ->
            httpClient.newCall(httpRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (e is UnknownHostException)
                        continuation.resume(null)
                    else
                        continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body
                    continuation.invokeOnCancellation {
                        response.close()
                    }
                    if (!response.isSuccessful) {
                        continuation.resume(null)
                        return
                    }

                    // While caching online, also stash a copy without cache-buster query params
                    // (e.g. ?v=...) so a drifted version still resolves when playing offline.
                    if (!forceCache && request.url.encodedPath.endsWith(".html"))
                        prefetchUnversionedCopy(httpClient, request, body.contentLength())

                    val contentType = body.contentType()
                    val storedMime = contentType?.let { "${it.type}/${it.subtype}" }
                    val sniffed = SniffedBody(body.byteStream())
                    val servedMime = correctMimeType(request.url.encodedPath.lowercase(),
                        storedMime, sniffed)
                    val servedEncoding = if (isTextType(servedMime))
                        contentType?.charset()?.name() ?: "UTF-8"
                    else
                        contentType?.charset()?.name()

                    val responseHeaders = response.headers.toMultimap()
                        .mapValues { kvp -> kvp.value.joinToString(separator = ",") }
                        .toMutableMap()
                    if (servedMime != null) {
                        // Keep the Content-Type header consistent with the mimeType we pass, so
                        // WebView can't render a document as raw text because of a stale/odd type.
                        responseHeaders["content-type"] = if (servedEncoding != null)
                            "$servedMime; charset=$servedEncoding"
                        else
                            servedMime
                    }

                    continuation.resume(
                        WebResourceResponse(
                            servedMime,
                            servedEncoding,
                            response.code,
                            response.message.ifEmpty { "(empty)" },
                            responseHeaders,
                            sniffed.stream
                        )
                    )
                }
            })
        }
        Utils.logDebug(LOGGING_TAG, "${if (forceCache) "offline" else "online"} ${request.url}: " +
            "${response?.mimeType ?: "MISS"}")

        return when {
            response != null -> response
            !forceCache -> request(httpClient, request, forceCache = true)
            else -> {
                val stripped = stripCacheBuster(request)
                if (stripped != null)
                    request(httpClient, stripped, forceCache = true)
                else
                    null
            }
        }
    }

    /**
     * Serves an HTML document instead of the raw source when the stored/cached content type is
     * missing or generic (e.g. application/octet-stream), and maps well-known extensions to their
     * proper types. WebView does not MIME-sniff responses served through WebResourceResponse the
     * same way it does real network responses, so a wrong type makes it render the page's source.
     */
    private fun correctMimeType(path: String, storedMime: String?, sniffed: SniffedBody?): String? {
        if (sniffed?.looksLikeHtml == true)
            return "text/html"
        val extType = mimeTypeForExtension(path)
        if (storedMime == null)
            return extType
        return when (storedMime) {
            "application/octet-stream", "text/plain" -> extType ?: storedMime
            else -> storedMime
        }
    }

    private fun isTextType(mime: String?): Boolean =
        mime?.startsWith("text/") == true || mime == "application/json"
            || mime == "application/xml" || mime == "application/javascript"

    private fun mimeTypeForExtension(path: String): String? {
        return when {
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript"
            path.endsWith(".wasm") -> "application/wasm"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".avif") -> "image/avif"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".otf") -> "font/otf"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".ogg") -> "audio/ogg"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".xml") -> "application/xml"
            path.endsWith(".txt") -> "text/plain"
            else -> null
        }
    }

    private fun stripCacheBuster(request: Request): Request? {
        val url = request.url
        val paramNames = url.queryParameterNames
        if (paramNames.isEmpty())
            return null
        val busters = paramNames.filter { it.lowercase() in CACHE_BUSTER_PARAM_NAMES }
        if (busters.isEmpty())
            return null
        val builder = url.newBuilder()
        busters.forEach { builder.removeAllQueryParameters(it) }
        return request.newBuilder().url(builder.build()).build()
    }

    /**
     * Downloads and caches the same URL without cache-buster query parameters, so an offline load
     * of a (possibly drifted) versioned entry point can still be satisfied from the cache.
     */
    private fun prefetchUnversionedCopy(httpClient: OkHttpClient, request: Request, contentLength: Long) {
        if (contentLength <= 0 || contentLength > MAX_PREFETCH_BYTES)
            return
        val stripped = stripCacheBuster(request) ?: return
        Utils.logDebug(LOGGING_TAG, "prefetching unversioned copy of ${request.url}")
        httpClient.newCall(stripped).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                // Draining the body lets OkHttp store the response in its cache.
                response.use { it.body?.bytes() }
            }
        })
    }

    /**
     * Reads up to 1 KiB off the front of the body so we can sniff whether a document is HTML,
     * then hands WebView a stream containing those bytes plus the untouched remainder.
     */
    private class SniffedBody(input: InputStream) {
        private val head = ByteArray(1024)
        private var headLength = 0
        private val sniffed: String
        val stream: InputStream

        init {
            while (headLength < head.size) {
                val n = input.read(head, headLength, head.size - headLength)
                if (n == -1)
                    break
                headLength += n
            }
            sniffed = String(head, 0, headLength, Charsets.ISO_8859_1)
            stream = if (headLength == 0) input
            else SequenceInputStream(ByteArrayInputStream(head, 0, headLength), input)
        }

        val looksLikeHtml: Boolean
            get() = sniffed.contains("<html", ignoreCase = true)
                || sniffed.contains("<!doctype", ignoreCase = true)
    }

    private fun getOkHttpClientForGame(gameId: Int): OkHttpClient {
        return cacheHttpClients.getOrPut(gameId) { ->
            val cacheDir = getCacheDir(gameId)
            Utils.logDebug(LOGGING_TAG, "new client; cache dir: $cacheDir")
            Mitch.httpClient.newBuilder().let {
                it.cache(Cache(cacheDir, Long.MAX_VALUE))
                it.build()
            }
        }
    }

    private fun getCacheDir(gameId: Int): File {
        return migrateCacheDir(gameId, mkdir = true)
    }

    suspend fun makeGameWebCached(context: Context, gameId: Int) {
        val db = AppDatabase.getDatabase(context)
        // Do not create an Installation whose game_id does not exist in the games table,
        // as that would violate the foreign key constraint. https://todo.sr.ht/~gardenapple/mitch/81
        if (db.gameDao.getGameByIdSync(gameId) == null)
            return
        val install = db.installDao.getWebInstallationForGame(gameId)

        val newInstall = Installation(
            internalId = install?.internalId ?: 0,
            gameId = gameId,
            uploadId = Installation.WEB_UPLOAD_ID,
            availableUploadIds = null,
            status = Installation.STATUS_WEB_CACHED,
            uploadName = Installation.WEB_UPLOAD_NAME,
            fileSize = Installation.WEB_FILE_SIZE
        )
        db.installDao.upsert(newInstall)
    }

    suspend fun isGameWebCached(context: Context, gameId: Int): Boolean {
        val db = AppDatabase.getDatabase(context)
        return db.installDao.getWebInstallationForGame(gameId) != null
    }

    suspend fun deleteCacheForGame(context: Context, gameId: Int) {
        withContext(Dispatchers.IO) {
            cacheHttpClients.remove(gameId)?.run {
                cache?.delete()
            }
            val cacheDir = getCacheDir(gameId)
            Utils.logDebug(LOGGING_TAG, "Deleting $cacheDir")
            cacheDir.deleteRecursively()
            Utils.logDebug(LOGGING_TAG, "exists? ${cacheDir.exists()}")
        }

        val db = AppDatabase.getDatabase(context)
        db.installDao.deleteWebInstallationForGame(gameId)
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        for (httpClient in cacheHttpClients) {
            httpClient.value.cache?.flush()
        }
    }

    suspend fun cleanCaches(db: AppDatabase) {
        val installs = db.installDao.getWebInstallationsSync()

        val dirsLegacy = cacheDirLegacy.listFiles()
        dirsLegacy?.forEach { dir ->
            migrateCacheDir(dir.name.toInt(), false)
        }
        cacheDirLegacy.deleteRecursively()

        val dirs = cacheDir.listFiles() ?: return

        Log.d(LOGGING_TAG, "Cleaning up...")
        for (cacheDir in dirs) {
            try {
                val cacheGameId = Integer.parseInt(cacheDir.name)
                if (installs.find { install -> install.gameId == cacheGameId } == null) {
                    Log.d(LOGGING_TAG, "Cleaning up $cacheGameId")
                    cacheDir.deleteRecursively()
                }
            } catch (e: NumberFormatException) {
                //Directory name is not a number; assume it's not a game cache directory
                continue
            }
        }
        Log.d(LOGGING_TAG, "Cleaning up done.")
    }

    private fun migrateCacheDir(gameId: Int, mkdir: Boolean): File {
        val dir = File(cacheDir, gameId.toString())
        val legacyDir = File(cacheDirLegacy, gameId.toString())

        try {
            if (legacyDir.renameTo(dir)) {
                Log.d(LOGGING_TAG, "Renamed from $legacyDir to $dir")
            } else {
                legacyDir.copyRecursively(dir)
                legacyDir.deleteRecursively()
                Log.d(LOGGING_TAG, "Moved from $legacyDir to $dir")
            }
            return dir
        } catch (e: NoSuchFileException) {
            //no-op
        } catch (e: Exception) {
            File(cacheDirLegacy, gameId.toString()).deleteRecursively()
            Log.d(LOGGING_TAG, "Failed to move $legacyDir, deleting and returning $dir")
        }

        if (mkdir)
            dir.mkdirs()
        return dir
    }
}