package garden.appl.mitch.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import garden.appl.mitch.ItchWebsiteUtils
import garden.appl.mitch.Mitch
import garden.appl.mitch.PREF_GAME_RESTORE_AUTOROTATE
import garden.appl.mitch.PREF_GAME_RESTORE_ROTATION
import garden.appl.mitch.PREF_TEXT_GAME_FILL
import garden.appl.mitch.PREF_WEB_CACHE_ENABLE
import garden.appl.mitch.PREF_WEB_CACHE_UPDATE
import garden.appl.mitch.PREF_WEB_GAME_ORIENTATION
import garden.appl.mitch.PrefWebGameOrientation
import garden.appl.mitch.PreferenceWebCacheEnable
import garden.appl.mitch.PreferenceWebCacheUpdate
import tofu.gg.mitchy.R
import garden.appl.mitch.Utils
import garden.appl.mitch.client.ItchWebsiteParser
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.game.Game
import tofu.gg.mitchy.databinding.ActivityGameBinding
import garden.appl.mitch.files.DownloadFileListener
import garden.appl.mitch.files.DownloadType
import garden.appl.mitch.files.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ExecutionException

class GameActivity : MitchActivity(), CoroutineScope by MainScope() {
    companion object {
        private const val LOGGING_TAG = "GameActivity"
        const val EXTRA_GAME_ID = "GAME_ID"
        const val EXTRA_LAUNCHED_FROM_INSTALL = "IS_OFFLINE"
        private const val WEB_VIEW_STATE_KEY: String = "WebView"

        /**
         * Replaces a game's service worker with a no-op so every request flows back through
         * `shouldInterceptRequest` / `WebGameCache`. Some itch.io games ship a PWA service
         * worker (Godot exports especially) whose responses are served outside Mitch's
         * network layer, so the game can render as raw HTML source ("html code in tiny
         * font") and is never cached for offline play. The shim keeps a registration alive
         * (so the page's own `register()` call doesn't fight us) but installs with no
         * `fetch` handler, so requests go through the regular network stack where Mitch's
         * cache applies. It claims the page and reloads it once so the stale SW-served copy
         * is replaced immediately instead of on the next launch.
         */
        private val SW_DISABLE_SHIM = """
            self.addEventListener('install', (event) => {
                self.skipWaiting();
            });
            self.addEventListener('activate', (event) => {
                event.waitUntil((async () => {
                    await self.clients.claim();
                    try {
                        const cache = await caches.open('mitch-sw-shim');
                        if ((await cache.match('mitch-sw-shim-done')) == null) {
                            await cache.put('mitch-sw-shim-done', new Response('1'));
                            const clients = await self.clients.matchAll({ includeUncontrolled: false });
                            for (const client of clients) {
                                client.navigate(client.url).catch(() => {});
                            }
                        }
                    } catch (error) {}
                })());
            });
        """.trimIndent()

        /** True for service-worker script URLs, but not plain web workers. */
        private fun isServiceWorkerScript(path: String): Boolean {
            val p = path.lowercase()
            return p.contains("service.worker")
                || p.endsWith("service-worker.js")
                || p.endsWith("/sw.js")
                || p == "sw.js"
        }

        fun getShortcutId(gameId: Int) = "web_game/${gameId}"

        suspend fun makeShortcut(game: Game, context: Context): ShortcutInfoCompat? {
            val game = tryFixBackwardsCompatGame(game, context, null)
            val webEntryPoint = game.webEntryPoint
            if (webEntryPoint == null)
                return null
            val faviconBitmap = game.faviconUrl?.let { url ->
                withContext(Dispatchers.IO) {
                    val bitmap = try {
                        Glide.with(context).asBitmap().run {
                            load(url)
                            submit()
                        }.get()
                    } catch (e: ExecutionException) {
                        Log.e(LOGGING_TAG, "no thumbnail: ${e.cause}")
                        return@withContext null
                    }
                    bitmap.scale(128, 128, false)
                }
            }
            Log.d(LOGGING_TAG, "Game: $game")
            val intent = Intent(Intent.ACTION_VIEW, webEntryPoint.toUri(),
                context, GameActivity::class.java).apply {

                putExtra(EXTRA_GAME_ID, game.gameId)
                putExtra(EXTRA_LAUNCHED_FROM_INSTALL, true)
            }
            val shortcutId = getShortcutId(game.gameId)
            return ShortcutInfoCompat.Builder(context, shortcutId).run {
                setShortLabel(game.name)
                if (faviconBitmap != null)
                    setIcon(IconCompat.createWithBitmap(faviconBitmap))
                setIntent(intent)
                build()
            }
        }

        /**
         * Workaround for https://todo.sr.ht/~gardenapple/mitch/69 as well as a migration from
         * much older database entries
         * @return possibly an updated instance of [Game]
         */
        private suspend fun tryFixBackwardsCompatGame(
            game: Game,
            context: Context,
            userAgent: String?
        ): Game {
            if (game.webIframe != null && game.webEntryPoint != null)
                return game

            Log.d(LOGGING_TAG, "getting iframe and favicon as backwards compat")
            try {
                val doc = ItchWebsiteUtils.fetchAndParse(game.storeUrl, userAgent)
                val parsedGame = ItchWebsiteParser.getGameInfoForStorePage(doc, game.storeUrl)!!
                val newGame = game.copy(
                    webEntryPoint = parsedGame.webEntryPoint,
                    webIframe = parsedGame.webIframe,
                    faviconUrl = parsedGame.faviconUrl
                )
                val db = AppDatabase.getDatabase(context)
                db.gameDao.update(newGame)
                return newGame
            } catch (_: Exception) {
                return game
            }
        }
    }

    private lateinit var binding: ActivityGameBinding
    private lateinit var webView: MitchWebView
    private lateinit var chromeClient: GameChromeClient
    private lateinit var blobDownloadBridge: BlobDownloadBridge
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var isOfflineMode: Boolean = false
    private var isCaching: Boolean = false

    /**
     * The game currently loaded in the player, used to re-apply the screen orientation on
     * every resume (so a preference change or an activity restore takes effect immediately
     * instead of only on the next fresh launch).
     */
    private var gameToOrient: Game? = null

    /**
     * Display rotation the user had before the player forced the game's orientation.
     * Some devices (MIUI) keep the forced rotation after the player closes instead of
     * returning to the user's, so we record it and restore it when the activity finishes.
     * -1 until a game actually forces an orientation.
     */
    private var rotationBeforeGame: Int = -1

    /**
     * Whether auto-rotate was off when the game forced an orientation. Only then does the
     * app itself have to hand the rotation back; with auto-rotate on the sensor does it.
     */
    private var autorotateBeforeGame: Boolean = true

    private val connection = object : ServiceConnection {
        //NO-OP, we bind to a foreground service so that Android does not kill us
        override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {}
        override fun onServiceDisconnected(p0: ComponentName?) {}
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        binding.root.keepScreenOn = true
        webView = binding.gameWebView

        val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { fileChooserCallback?.onReceiveValue(arrayOf(it)) }
            fileChooserCallback = null
        }
        val openMultipleDocumentsLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            fileChooserCallback?.onReceiveValue(uris.toTypedArray())
            fileChooserCallback = null
        }
        chromeClient = GameChromeClient(openDocumentLauncher, openMultipleDocumentsLauncher)

        @SuppressLint("SetJavaScriptEnabled")
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
//        webView.settings.setAppCacheEnabled(true)
//        webView.settings.setAppCachePath(File(filesDir, "html5-app-cache").path)
        webView.settings.databaseEnabled = true

        // Let HTML5 games use the page's viewport meta tag and fill the whole
        // screen instead of being cut off at the top-left corner.
        // https://todo.sr.ht/~gardenapple/mitch/84
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        // Start every game fully zoomed out so the whole page is visible, even for
        // games that ship without a viewport meta tag or with a fixed-size canvas.
        // The user can still pinch-zoom thanks to the settings above.
        webView.setInitialScale(1)

        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = GameWebViewClient()
        webView.webChromeClient = chromeClient

        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false

        blobDownloadBridge = BlobDownloadBridge(this)
        webView.addJavascriptInterface(blobDownloadBridge, "mitchBlobJS")

        webView.setBackgroundColor(Utils.getColor(this, R.color.colorAccent))

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val fileName = Utils.guessFileName(url, contentDisposition, mimeType)
            if (url.startsWith("blob:")) {
                startBlobDownload(url, fileName)
                return@setDownloadListener
            }
            Log.d(LOGGING_TAG, "Guessed file name: $fileName")

            AlertDialog.Builder(this).apply {
                setTitle(R.string.dialog_save_file_title)
                setMessage(fileName)
                setCancelable(true)
                setPositiveButton(R.string.dialog_yes) { _, _ ->
                    requestNotificationPermission(this@GameActivity,
                        R.string.dialog_notification_explain_download,
                        R.string.dialog_notification_cancel_download
                    )
                    Mitch.externalFileManager.requestPermissionIfNeeded(this@GameActivity) {
                        Toast.makeText(context, R.string.popup_download_started, Toast.LENGTH_SHORT)
                            .show()
                        this@GameActivity.launch {
                            Downloader.requestDownload(
                                this@GameActivity, url, userAgent,
                                install = null,
                                fileName = fileName,
                                contentLength = contentLength,
                                downloadDir = null,
                                tempDownloadDir = true,
                                installer = null
                            )
                        }
                    }
                }
                setNegativeButton(R.string.dialog_no) { _, _ -> /* NO-OP */ }

                show()
            }
        }

        val gameId = intent?.getIntExtra(EXTRA_GAME_ID, -1) ?: -1
        val foregroundServiceIntent = Intent(this, GameForegroundService::class.java)
        foregroundServiceIntent.putExtra(GameForegroundService.EXTRA_ORIGINAL_INTENT, intent)
        foregroundServiceIntent.putExtra(GameForegroundService.EXTRA_GAME_ID, gameId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(foregroundServiceIntent)
        } else {
            startService(foregroundServiceIntent)
        }
        bindService(foregroundServiceIntent, connection, 0)


        val bundle = savedInstanceState?.getBundle(WEB_VIEW_STATE_KEY)
        if (bundle != null) {
            webView.restoreState(bundle)
        } else {
            launch {
                showLaunchDialog()
            }
        }

        // Handle back through the dispatcher instead of overriding onBackPressed(), so
        // the system predictive back animation works. The player first exits fullscreen
        // and walks the game's own WebView history; only when the game can't go back
        // does it ask the user whether they want to leave.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chromeClient.isFullscreen()) {
                    chromeClient.onHideCustomView()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                showExitDialog()
            }
        })
    }

    /**
     * Downloads a page-provided blob URL by streaming its bytes to [BlobDownloadBridge].
     * Passing large blobs to the Downloader as a single data: URL would exceed the WebView's
     * serialization limit ("Cannot serialize data"). https://todo.sr.ht/~gardenapple/mitch/71
     */
    private fun startBlobDownload(blobUrl: String, fileName: String) {
        val nonce = SecureRandom().nextLong().toString()
        blobDownloadBridge.startBlobDownload(nonce, fileName)
        webView.evaluateJavascript(
            """
            (function() {
                var toBase64 = function(u8) {
                    var binary = "";
                    var subChunk = 0x8000;
                    for (var i = 0; i < u8.length; i += subChunk) {
                        binary += String.fromCharCode.apply(null, u8.subarray(i, i + subChunk));
                    }
                    return btoa(binary);
                };
                fetch(${JSONObject.quote(blobUrl)}).then(function(response) {
                    return response.arrayBuffer();
                }).then(function(buffer) {
                    var bytes = new Uint8Array(buffer);
                    var chunkSize = 256 * 1024;
                    mitchBlobJS.onBlobStart(${JSONObject.quote(nonce)});
                    for (var offset = 0; offset < bytes.length; offset += chunkSize) {
                        mitchBlobJS.onBlobChunk(${JSONObject.quote(nonce)},
                            toBase64(bytes.subarray(offset, offset + chunkSize)));
                    }
                    mitchBlobJS.onBlobEnd(${JSONObject.quote(nonce)});
                }).catch(function() {
                    mitchBlobJS.onBlobError(${JSONObject.quote(nonce)});
                });
            })();
            """, null
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getIntExtra(EXTRA_GAME_ID, -1)
            != this.intent?.getIntExtra(EXTRA_GAME_ID, -2)) {

            launch {
                showLaunchDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
//        webView.resumeTimers()
        webView.onResume()
        val game = gameToOrient
        if (game != null) {
            applyGameOrientation(game)
        } else {
            launch {
                val gameId = intent.getIntExtra(EXTRA_GAME_ID, -1)
                AppDatabase.getDatabase(this@GameActivity)
                    .gameDao.getGameByIdSync(gameId)
                    ?.let { loaded ->
                        gameToOrient = loaded
                        applyGameOrientation(loaded)
                    }
            }
        }
    }

    private suspend fun showLaunchDialog() {
        val gameId = intent.getIntExtra(EXTRA_GAME_ID, -1)

        // The game may have been removed from the library or cleaned up since this launcher
        // shortcut was created. In that case, its row is gone from the database and we must
        // not try to create an Installation for it (which would violate a foreign key).
        // https://todo.sr.ht/~gardenapple/mitch/81
        val db = AppDatabase.getDatabase(this)
        if (db.gameDao.getGameByIdSync(gameId) == null) {
            ShortcutManagerCompat.removeDynamicShortcuts(this, listOf(getShortcutId(gameId)))
            Toast.makeText(this, R.string.popup_web_game_not_available, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val installedOffline = Mitch.webGameCache.isGameWebCached(this, gameId)

        val webCacheEnabled = try {
            prefs.getString(PREF_WEB_CACHE_ENABLE, PreferenceWebCacheEnable.DEFAULT)
                ?.toBooleanStrictOrNull()
        } catch (_: ClassCastException) {
            if (prefs.getBoolean("mitch.web_cache_dialog_hide", false))
                false
            else
                null
        }

        if (installedOffline || intent.getBooleanExtra(EXTRA_LAUNCHED_FROM_INSTALL, false)) {
            afterDialogShown(installedOffline, true)
            return
        } else if (webCacheEnabled != null) {
            afterDialogShown(installedOffline, webCacheEnabled)
            return
        }
        val dialog = AlertDialog.Builder(this).apply {
            setTitle(R.string.dialog_web_cache_info_title)
            setMessage(R.string.dialog_web_cache_info)

            val hideCheckBox = CheckBox(context).apply {
                setText(R.string.dialog_dont_ask_again)
            }

            setView(FrameLayout(context).apply {
                addView(hideCheckBox, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(50, 25, 50, 0)
                })
            })

            setPositiveButton(R.string.dialog_yes) { _, _ ->
                prefs.edit(commit = true) {
                    if (hideCheckBox.isChecked) {
                        putString(PREF_WEB_CACHE_ENABLE, PreferenceWebCacheEnable.ALWAYS)
                    }
                }
                launch { afterDialogShown(installedOffline, true) }
            }
            setNegativeButton(R.string.dialog_no) { _, _ ->
                prefs.edit(commit = true) {
                    if (hideCheckBox.isChecked) {
                        putString(PREF_WEB_CACHE_ENABLE, PreferenceWebCacheEnable.NEVER)
                    }
                }
                launch { afterDialogShown(installedOffline, false) }
            }
            setCancelable(true)
            setOnCancelListener {
                launch { afterDialogShown(installedOffline, false) }
            }

            create()
        }
        dialog.show()
    }

    private suspend fun afterDialogShown(installedOffline: Boolean, shouldCache: Boolean) {
        val gameId = intent.getIntExtra(EXTRA_GAME_ID, -1)

        Log.d(LOGGING_TAG, "Running $gameId, installed offline: $installedOffline, should cache: $shouldCache")

        if (shouldCache && !installedOffline) {
            Toast.makeText(this, R.string.popup_web_game_cached, Toast.LENGTH_LONG)
                .show()
            Mitch.webGameCache.makeGameWebCached(this, gameId)
            this.isOfflineMode = false
        } else if (installedOffline) {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
            when (sharedPrefs.getString(PREF_WEB_CACHE_UPDATE, PreferenceWebCacheUpdate.NEVER)) {
                PreferenceWebCacheUpdate.NEVER ->
                    this.isOfflineMode = true
                PreferenceWebCacheUpdate.UNMETERED ->
                    this.isOfflineMode = !Utils.isNetworkConnected(this, requireUnmetered = true)
                else ->
                    this.isOfflineMode = !Utils.isNetworkConnected(this)
            }
        } else {
            this.isOfflineMode = false
        }

        if (this.isOfflineMode)
            Toast.makeText(this, R.string.popup_web_game_offline_mode, Toast.LENGTH_LONG)
                .show()
        this.isCaching = shouldCache

        val db = AppDatabase.getDatabase(this)
        val gameFromDb = db.gameDao.getGameByIdSync(gameId)
            ?: run {
                // See showLaunchDialog; the game's row is missing, so don't crash.
                ShortcutManagerCompat.removeDynamicShortcuts(this, listOf(getShortcutId(gameId)))
                Toast.makeText(this, R.string.popup_web_game_not_available, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        val game = tryFixBackwardsCompatGame(gameFromDb, this,
            webView.settings.userAgentString)

        loadGame(game)
        makeShortcut(game, this@GameActivity)?.let { shortcut ->
            ShortcutManagerCompat.pushDynamicShortcut(this@GameActivity, shortcut)
        }
    }

    private fun loadGame(game: Game) {
        val webEntryPoint = game.webEntryPoint
        if (webEntryPoint == null) {
            // The game page was removed/delisted, so it has no playable iframe anymore.
            // Don't crash on a null base URL — tell the user and leave gracefully.
            Toast.makeText(this, R.string.popup_web_game_not_available, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        gameToOrient = game
        applyGameOrientation(game)
        // itch.io's embed iframe carries an inline pixel height, so text games (Twine &
        // friends) would otherwise render as a tiny strip instead of filling the player.
        // Overridable in settings to restore the default embed sizing.
        // (mentioned in https://itch.io/t/2393827/cant-play-text-based-games)
        val iframeHeightRule = if (PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(PREF_TEXT_GAME_FILL, true))
            "height: 100% !important;"
        else
            "height: 100%;"
        val html = """<html>
            <head>
                <style type="text/css">
                    html {
                        overflow: auto;
                    }
                    
                    html, body, div, iframe {
                        margin: 0px; 
                        padding: 0px; 
                        height: 100%; 
                        border: none;
                        /* background-color: #FA5C5C; */
                    }
                    iframe {
                        display: block; 
                        width: 100%; 
                        $iframeHeightRule
                        border: none; 
                        overflow-y: auto; 
                        overflow-x: hidden;
                    }
                </style>
            </head>
            <body>${game.webIframe}</body>
        </html>""".trimIndent()
        webView.loadDataWithBaseURL(webEntryPoint, html, "text/html", "UTF-8", null)
    }

    /**
     * Rotates the player to the orientation the developer declared for the web game (itch.io
     * encodes it as the `orientation` query parameter on the embed URL, e.g. landscape_left).
     * A declared orientation always wins, so portrait-designed games keep running portrait.
     * Games without a declaration are left untouched by default; the "Web game orientation"
     * setting lets the user force an orientation for exactly those games (otherwise
     * landscape-designed games run letterboxed small inside a portrait player).
     * The activity handles configChanges, so rotating does not reload or restart the game.
     */
    private fun applyGameOrientation(game: Game) {
        when (Utils.parseWebGameOrientation(game.webEntryPoint)) {
            "landscape", "landscape_left" -> {
                rememberRotationBeforeGame()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Log.d(LOGGING_TAG, "Game orientation: landscape (declared)")
                return
            }
            "landscape_right" -> {
                rememberRotationBeforeGame()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                Log.d(LOGGING_TAG, "Game orientation: reverse landscape (declared)")
                return
            }
            "portrait" -> {
                rememberRotationBeforeGame()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Log.d(LOGGING_TAG, "Game orientation: portrait (declared)")
                return
            }
            else -> {}
        }
        val forced = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_WEB_GAME_ORIENTATION, PrefWebGameOrientation.DEFAULT)
        requestedOrientation = when (forced) {
            PrefWebGameOrientation.PORTRAIT -> {
                rememberRotationBeforeGame()
                Log.d(LOGGING_TAG, "Game orientation: portrait (forced by setting)")
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            PrefWebGameOrientation.LANDSCAPE -> {
                rememberRotationBeforeGame()
                Log.d(LOGGING_TAG, "Game orientation: landscape (forced by setting)")
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            else -> {
                Log.d(LOGGING_TAG, "Game orientation: unspecified (no declaration, setting off)")
                return
            }
        }
    }

    /**
     * Records the display rotation the user had before the player forced one, so it can
     * be restored when the game closes. Only the first recording counts: re-applying the
     * orientation on later resumes must not overwrite it with the game's own rotation.
     */
    @Suppress("DEPRECATION")
    private fun rememberRotationBeforeGame() {
        if (rotationBeforeGame >= 0)
            return
        rotationBeforeGame = windowManager.defaultDisplay.rotation
        // Auto-rotate off means the display will only come back if the app itself
        // restores the rotation; with auto-rotate on the sensor does it on its own.
        autorotateBeforeGame = Settings.System.getInt(
            contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
        ) == 1
    }

    /**
     * Hands the user's pre-game rotation back to the app when the player closes. Devices
     * like MIUI keep the rotation the game forced (and even change the global rotation
     * setting), so a plain finish() would leave the rest of the app stuck in
     * landscape/portrait. The rotation is persisted for [MainActivity] to apply on
     * resume, because the player window alone cannot change the display back.
     */
    private fun restoreOrientationThenFinish() {
        if (rotationBeforeGame >= 0) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt(PREF_GAME_RESTORE_ROTATION, rotationBeforeGame)
                .putBoolean(PREF_GAME_RESTORE_AUTOROTATE, autorotateBeforeGame)
                .apply()
        }
        finish()
    }

    override fun onPause() {
        super.onPause()

        webView.onPause()
        CookieManager.getInstance().flush()
        runBlocking {
            Mitch.webGameCache.flush()
        }
    }

    override fun onDestroy() {
        unbindService(connection)
        stopService(Intent(this, GameForegroundService::class.java))
        blobDownloadBridge.cancel()
        webView.destroy()
        super.onDestroy()
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this).apply {
            setMessage(R.string.popup_web_game_exit)
            setPositiveButton(R.string.dialog_yes) { _, _ ->
                restoreOrientationThenFinish()
            }
            setNegativeButton(R.string.dialog_no) { _, _ -> /* NO-OP */ }
            setCancelable(true)
        }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val webViewState = Bundle()
        webView.saveState(webViewState)
        outState.putBundle(WEB_VIEW_STATE_KEY, webViewState)

        super.onSaveInstanceState(outState)
    }

    override fun makeIntentForRestart(): Intent {
        val newIntent = Intent(Intent.ACTION_VIEW, intent.data, applicationContext,
            GameActivity::class.java)
        newIntent.putExtra(EXTRA_GAME_ID, intent.getIntExtra(EXTRA_GAME_ID, -1))
        newIntent.putExtra(EXTRA_LAUNCHED_FROM_INSTALL, intent.getBooleanExtra(EXTRA_LAUNCHED_FROM_INSTALL, false))
        return newIntent
    }

    /**
     * Receives the bytes of a blob URL from the page in chunks and saves them to a temporary
     * file, avoiding the WebView serialization limit for huge data: URLs.
     * https://todo.sr.ht/~gardenapple/mitch/71
     */
    @Keep // prevent this class from being removed by compiler optimizations
    private class BlobDownloadBridge(private val activity: GameActivity) {
        private val LOGGING_TAG = "BlobDownload"

        @Volatile
        private var currentNonce: String? = null
        @Volatile
        private var outputFile: File? = null
        @Volatile
        private var output: OutputStream? = null

        fun startBlobDownload(nonce: String, fileName: String) {
            currentNonce = nonce
            try {
                val tempDir = File(activity.cacheDir, "blob_downloads")
                    .resolve(System.nanoTime().toString()).apply { mkdirs() }
                val file = File(tempDir, fileName)
                outputFile = file
                output = FileOutputStream(file)
            } catch (e: Exception) {
                Log.e(LOGGING_TAG, "Could not open blob download file", e)
                currentNonce = null
            }
        }

        @JavascriptInterface
        @Synchronized
        fun onBlobStart(nonce: String) {
            if (nonce != currentNonce)
                Log.e(LOGGING_TAG, "Unexpected blob download start")
        }

        @JavascriptInterface
        @Synchronized
        fun onBlobChunk(nonce: String, base64Chunk: String) {
            if (nonce != currentNonce)
                return
            val output = output ?: return
            try {
                output.write(Base64.decode(base64Chunk, Base64.DEFAULT))
            } catch (e: Exception) {
                Log.e(LOGGING_TAG, "Failed to write blob chunk", e)
            }
        }

        @JavascriptInterface
        @Synchronized
        fun onBlobEnd(nonce: String) {
            if (nonce != currentNonce)
                return
            val file = outputFile ?: return
            currentNonce = null
            outputFile = null
            try {
                output?.close()
                output = null
            } catch (e: Exception) {
                Log.e(LOGGING_TAG, "Failed to close blob download file", e)
            }
            activity.launch {
                DownloadFileListener().onCompleted(
                    activity, file.name, null,
                    System.nanoTime(), DownloadType.NORMAL_FILE, file
                )
            }
        }

        @JavascriptInterface
        @Synchronized
        fun onBlobError(nonce: String) {
            if (nonce != currentNonce)
                return
            currentNonce = null
            outputFile = null
            try {
                output?.close()
            } catch (_: Exception) {
            }
            output = null
            Toast.makeText(activity, R.string.notification_download_unknown_error, Toast.LENGTH_LONG)
                .show()
        }

        fun cancel() {
            currentNonce = null
            outputFile = null
            try {
                output?.close()
            } catch (_: Exception) {
            }
            output = null
        }
    }

    inner class GameWebViewClient : MitchWebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (request.url.scheme != "http" && request.url.scheme != "https")
                return super.shouldInterceptRequest(view, request)
            if (isServiceWorkerScript(request.url.encodedPath.orEmpty())) {
                // Replace the game's service worker with a no-op shim so requests are
                // handled by WebGameCache instead of the SW (which can serve the page as
                // raw HTML source and bypasses offline caching entirely).
                return WebResourceResponse(
                    "application/javascript", "UTF-8", 200, "OK", null,
                    ByteArrayInputStream(SW_DISABLE_SHIM.toByteArray(Charsets.UTF_8))
                )
            }
            if (!request.method.equals("GET", ignoreCase = true))
                return super.shouldInterceptRequest(view, request)
            if (!this@GameActivity.isCaching)
                return super.shouldInterceptRequest(view, request)

            val gameId = intent.getIntExtra(EXTRA_GAME_ID, -1)
            return runBlocking(Dispatchers.IO) {
                Mitch.webGameCache.request(gameId, request, this@GameActivity.isOfflineMode)
            }
        }
    }

    inner class GameChromeClient(
        openDocumentLauncher: ActivityResultLauncher<Array<String>>,
        openMultipleDocumentsLauncher: ActivityResultLauncher<Array<String>>
    ) : MitchWebChromeClient(openDocumentLauncher, openMultipleDocumentsLauncher) {
        private var customView: View? = null
        private var customViewCallback: CustomViewCallback? = null

        fun isFullscreen(): Boolean = customView != null

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) {
                // WebView can report "fullscreen" without an actual view; tell it the
                // custom view was already hidden instead of adding a null child (NPE).
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            webView.visibility = View.GONE
            binding.root.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        override fun onHideCustomView() {
            webView.visibility = View.VISIBLE
            customView?.let { binding.root.removeView(it) }
            customView = null
            // Without this the WebView keeps thinking the page is still fullscreen
            // (audio keeps playing, exit controls stay hidden).
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }

        override fun setFileChooserCallback(callback: ValueCallback<Array<Uri>>) {
            this@GameActivity.fileChooserCallback = callback
        }
    }
}