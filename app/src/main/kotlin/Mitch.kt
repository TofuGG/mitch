package garden.appl.mitch

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import garden.appl.mitch.client.UpdateChecker
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.DatabaseCleanup
import garden.appl.mitch.files.ExternalFileManager
import garden.appl.mitch.files.WebGameCache
import garden.appl.mitch.files.WebViewDataBackup
import garden.appl.mitch.install.InstallationDatabaseManager
import garden.appl.mitch.install.InstallationDownloadManager
import garden.appl.mitch.ui.CrashDialog
import garden.appl.mitch.ui.MitchContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.dialog
import org.acra.config.mailSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import tofu.gg.mitchy.BuildConfig
import tofu.gg.mitchy.R
import java.io.File
import java.util.concurrent.TimeUnit


const val PERMISSION_REQUEST_DOWNLOADS_VIEW_INTENT = 1
const val PERMISSION_REQUEST_MOVE_TO_DOWNLOADS = 2
const val PERMISSION_REQUEST_NOTIFICATION = 3
const val PERMISSION_REQUEST_START_DOWNLOAD = 4

const val FILE_PROVIDER = "${BuildConfig.APPLICATION_ID}.fileprovider"

const val NOTIFICATION_CHANNEL_ID_UPDATES = "updates_available"
const val NOTIFICATION_CHANNEL_ID_INSTALL_NEEDED = "updates"
const val NOTIFICATION_CHANNEL_ID_INSTALLING = "installing"
const val NOTIFICATION_CHANNEL_ID_WEB_RUNNING = "web_running"

const val NOTIFICATION_TAG_UPDATE_CHECK = "UpdateCheck"
const val NOTIFICATION_TAG_DOWNLOAD = "DownloadResult"
const val NOTIFICATION_TAG_DOWNLOAD_LONG = "DownloadResultLong"
const val NOTIFICATION_TAG_INSTALL_RESULT = "InstallResult"
const val NOTIFICATION_TAG_INSTALL_RESULT_LONG = "NativeInstallResult"

const val UPDATE_CHECK_TASK_TAG = "update_check"
const val DB_CLEAN_TASK_TAG = "db_clean"

const val HEADER_UA = "User-Agent"


// Remember to exclude sensitive info from ACRA reports
const val PREF_DB_RAN_CLEANUP_ONCE = "tofu.gg.mitchy.db_cleanup_once"
const val PREF_INSTALLER = "tofu.gg.mitchy.installer"
const val PREF_WEB_ANDROID_FILTER = "tofu.gg.mitchy.web_android_filter"
// Bundles: mitch.{racial, palestine, ukraine, trans_texas}
const val PREF_LANG = "mitch.lang"
/**
 * Locale is not controlled directly by the user; instead, Mitch.kt applies
 * [PREF_LANG_LOCALE_NEXT], and then [PREF_LANG_LOCALE] gets applied on app restart
 */
const val PREF_LANG_LOCALE = "mitch.lang_locale"
const val PREF_LANG_LOCALE_NEXT = "mitch.lang_locale_next"
const val PREF_LANG_SITE_LOCALE = "mitch.lang_site_locale"
const val PREF_WARN_WRONG_OS = "mitch.warn_wrong_os"
const val PREF_WEB_CACHE_ENABLE = "mitch.web_cache_enable"
object PreferenceWebCacheEnable {
    const val NEVER = "false"
    const val ASK = "ask"
    const val ALWAYS = "true"
    const val DEFAULT = ASK
}
//const val PREF_WEB_CACHE_DIALOG_HIDE = "mitch.web_cache_dialog_hide"
// Last page the Browse tab was on, so it can be restored after the app process dies.
const val PREF_BROWSE_LAST_URL = "mitch.browse_last_url"
// Vertical scroll position of the last page, so the user lands where they left off
// instead of at the top of the page.
const val PREF_BROWSE_LAST_SCROLL = "mitch.browse_last_scroll"
// Rotation the user had before the player forced a game's orientation (and whether
// auto-rotate was off), so the app can hand the rotation back when the player closes.
// Some devices (MIUI) keep the game's forced rotation and leave the whole app rotated.
const val PREF_GAME_RESTORE_ROTATION = "mitch.game_restore_rotation"
const val PREF_GAME_RESTORE_AUTOROTATE = "mitch.game_restore_autorotate"
// Client-side genre/tag exclusion filters for catalogue pages, persisted across
// navigation so they aren't lost when viewing a game and coming back.
const val PREF_BROWSE_GENRES_FILTER = "mitch.browse_genres_exclusion_filter"
const val PREF_BROWSE_TAGS_FILTER = "mitch.browse_tags_exclusion_filter"
const val PREF_WEB_CACHE_UPDATE = "mitch.web_cache_update"
object PreferenceWebCacheUpdate {
    const val NEVER = "never"
    const val UNMETERED = "unmetered"
    const val ALWAYS = "always"
}
const val PREF_BROWSE_START_PAGE = "mitch.browse_start_page"
const val PREF_START_PAGE_EXCLUDE = "mitch.start_page_exclude"
const val PREF_START_PAGE_EXCLUDE_DISPLAY_STRING = "mitch.start_page_exclude.display_string"
const val PREF_NO_NOTIFICATIONS = "mitch.no_notifications"

const val PREF_UPDATE_CHECK_ENABLED = "mitch.update_check_enabled"
const val PREF_UPDATE_TRACKING_ENABLED = "mitch.per_game_update_tracking"
const val PREF_TAG_EXCLUSION_ENABLED = "mitch.tag_exclusion_enabled"
const val PREF_SCROLL_TO_TOP_ENABLED = "mitch.scroll_to_top_enabled"
const val PREF_BOTTOM_NAV_ALWAYS_VISIBLE = "mitch.bottom_nav_always_visible"

const val PREF_DEBUG_WEB_GAMES_IN_BROWSE_TAB = "mitch.debug.web_games_in_browse"

// Render itch.io with a desktop user agent when enabled.
// (mentioned in https://itch.io/t/6622118/any-way-to-export-in-app-browser-data-or-put-in-app-browser-into-desktop-mode)
const val PREF_DESKTOP_MODE = "mitch.browse_desktop_mode"

// Feature toggles. Defaults are on so existing behaviour is preserved unless the user opts out.
const val PREF_SEARCH_ENABLED = "mitch.search_enabled"
const val PREF_GENRE_EXCLUSION_ENABLED = "mitch.genre_exclusion_enabled"
const val PREF_TEXT_GAME_FILL = "mitch.text_game_fill"

// Orientation used by the web game player. Overrides the orientation the developer
// declared on the itch.io embed URL, which is missing for many games and leaves
// landscape-designed games letterboxed small inside a portrait player.
const val PREF_WEB_GAME_ORIENTATION = "mitch.web_game_orientation"
object PrefWebGameOrientation {
    const val GAME = "game"
    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"
    const val DEFAULT = GAME
}

// Browser-data export/import. The import is deferred to the next app launch so the swap
// happens before any WebView is created (see WebViewDataBackup).
const val PREF_BROWSER_DATA_EXPORT = "mitch.export_browser_data"
const val PREF_BROWSER_DATA_IMPORT = "mitch.import_browser_data"
const val PREF_PENDING_BROWSER_DATA_IMPORT = "mitch.pending_browser_data_import"



class Mitch : Application() {

    companion object {
        const val LOGGING_TAG: String = "MitchApp"

        // Number of activities currently resumed. Used to keep the site theme from
        // being applied while the app is in the background.
        @Volatile
        var foregroundActivityCount: Int = 0
            private set

        // Used for lazy initialization, and for locale stuff
        private lateinit var mitchContext: MitchContextWrapper
        private lateinit var cacheDir: File

        // Be careful with lazy init to avoid circular dependency, I'm stupid

        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder().run {
                val okHttpCacheDir = File(cacheDir, "OkHttp")
                okHttpCacheDir.mkdirs()
                cookieJar(WebViewCookieJar())
                // https://stackoverflow.com/a/53233345/5701177
                addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(request.newBuilder().run {
//                        Log.d(LOGGING_TAG, "app url: ${request.url}")
//                        Log.d(LOGGING_TAG, "app cookies: ${request.header(HEADER_COOKIE)}")
//                        Log.d(LOGGING_TAG, "app user agent: ${request.header(HEADER_UA)}")
                        if (request.header(HEADER_UA).isNullOrBlank()) {
                            if (BuildConfig.DEBUG)
                                addHeader(HEADER_UA, "Mitch dev.")
                            else
                                addHeader(HEADER_UA, "Mitchy v${BuildConfig.VERSION_NAME}")
                        }
                        build()
                    })
                }
//                addNetworkInterceptor { chain ->
//                    val request = chain.request()
//                    Log.d(LOGGING_TAG, "url: ${request.url}")
//                    Log.d(LOGGING_TAG, "cookies: ${request.header(HEADER_COOKIE)}")
//                    Log.d(LOGGING_TAG, "user agent: ${request.header(HEADER_UA)}")
//                    chain.proceed(request)
//                }
                cache(Cache(
                    directory = okHttpCacheDir,
                    maxSize = 50L * 1024 * 1024 //50 MB
                ))
                build()
            }
        }
        val installDownloadManager: InstallationDownloadManager by lazy {
            InstallationDownloadManager(mitchContext).apply {
                setup()
            }
        }
        val externalFileManager = ExternalFileManager()
        val databaseHandler: InstallationDatabaseManager by lazy {
            InstallationDatabaseManager(mitchContext)
        }
        val webGameCache: WebGameCache by lazy {
            WebGameCache(mitchContext)
        }
    }

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                "preference_update_check_if_metered" -> {
                    if (prefs.getBoolean(PREF_UPDATE_CHECK_ENABLED, true)) {
                        registerUpdateCheckTask(prefs.getBoolean(key, false),
                                ExistingPeriodicWorkPolicy.UPDATE)
                    }
                }
                PREF_UPDATE_CHECK_ENABLED -> {
                    if (prefs.getBoolean(key, true)) {
                        registerUpdateCheckTask(
                            prefs.getBoolean("preference_update_check_if_metered", false),
                            ExistingPeriodicWorkPolicy.UPDATE)
                    } else {
                        WorkManager.getInstance(applicationContext)
                            .cancelUniqueWork(UPDATE_CHECK_TASK_TAG)
                    }
                }
                "preference_theme",
                "current_site_theme" -> {
                    // A page can finish loading while the app is in the background (or the
                    // WebView otherwise isn't visible); don't flip the whole app's night mode
                    // for it. The theme is applied again when the app returns to the foreground.
                    // (mentioned in https://todo.sr.ht/~gardenapple/mitch/78)
                    if (foregroundActivityCount > 0)
                        setThemeFromPreferences(prefs)
                }
                PREF_LANG,
                PREF_LANG_SITE_LOCALE -> setLangFromPreferences(prefs)
            }
        }

    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            foregroundActivityCount++
        }

        override fun onActivityPaused(activity: Activity) {
            if (foregroundActivityCount == 0)
                return
            foregroundActivityCount--
            if (foregroundActivityCount == 0) {
                // The whole app is going to the background: snapshot session cookies so a
                // process kill right after this can't lose an itch.io login made on any screen
                // (game player, GitHub OAuth, etc.), not just the Browse tab.
                // (mentioned in https://itch.io/t/3985413/mitch-is-not-maintaining-itch-logins)
                SessionCookieStore.capture(activity.applicationContext)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG)
            enableStrictMode()
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        if (ACRA.isACRASenderServiceProcess())
            return


        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        sharedPreferences.edit(true) {
            val nextLocale = sharedPreferences.getString(PREF_LANG_LOCALE_NEXT, null)
            if (nextLocale != null) {
                remove(PREF_LANG_LOCALE_NEXT)
                putString(PREF_LANG_LOCALE, nextLocale)
            }
        }
        setLangFromPreferences(sharedPreferences)
        setThemeFromPreferences(sharedPreferences)

        mitchContext = MitchContextWrapper.wrap(applicationContext,
            sharedPreferences.getString(PREF_LANG_LOCALE, "")!!)
        Mitch.cacheDir = cacheDir

        applyPendingBrowserDataImport()



        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var name = getString(R.string.notification_channel_install)
            var descriptionText = getString(R.string.notification_channel_install_desc)
            var importance = NotificationManager.IMPORTANCE_HIGH
            var channel =
                NotificationChannel(NOTIFICATION_CHANNEL_ID_INSTALL_NEEDED, name, importance).apply {
                    description = descriptionText
                }
            // Register the channel with the system
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)


            name = getString(R.string.notification_channel_updates)
            descriptionText = getString(R.string.notification_channel_updates_desc)
            importance = NotificationManager.IMPORTANCE_DEFAULT
            channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_UPDATES, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)


            name = getString(R.string.notification_channel_installing)
            descriptionText = getString(R.string.notification_channel_installing_desc)
            importance = NotificationManager.IMPORTANCE_DEFAULT
            channel =
                NotificationChannel(NOTIFICATION_CHANNEL_ID_INSTALLING, name, importance).apply {
                    description = descriptionText
                }
            notificationManager.createNotificationChannel(channel)


            name = getString(R.string.notification_channel_web_running)
            descriptionText = getString(R.string.notification_channel_web_running_desc)
            importance = NotificationManager.IMPORTANCE_LOW
            channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_WEB_RUNNING, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }

        val workOnMetered = sharedPreferences.getBoolean("preference_update_check_if_metered", false)
        if (sharedPreferences.getBoolean(PREF_UPDATE_CHECK_ENABLED, true))
            registerUpdateCheckTask(!workOnMetered, ExistingPeriodicWorkPolicy.KEEP)

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            DB_CLEAN_TASK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DatabaseCleanup.Worker>(1, TimeUnit.DAYS).build()
        )

        // Build the Room database in the background right now, so the one-time cost (schema
        // migration, the Mitchy self-row upsert, first cleanup) is paid before the user opens
        // the Library/Updates tabs instead of freezing them. The ViewModels still fetch their
        // data asynchronously, so this only removes the cold-start stall.
        GlobalScope.launch(Dispatchers.IO) {
            try {
                AppDatabase.getDatabase(this@Mitch)
            } catch (e: Exception) {
                Log.e(LOGGING_TAG, "Failed to pre-warm the database", e)
            }
        }

        // Restore session cookies (itch.io/GitHub login) that don't survive a reboot
        SessionCookieStore.restore(this)
    }

    /**
     * Debug builds only: flag main-thread disk/network access and leaked objects in logcat,
     * so regressions that block the main thread show up during development. Never enabled in
     * release builds (StrictMode is purely a development aid and adds overhead).
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build()
        )
    }

    /**
     * If the user chose "Import browser data" in the settings, replace the WebView data
     * directory with the contents of the exported zip. Runs at app startup, before any
     * WebView is created, so the swap cannot corrupt live storage.
     * (mentioned in https://itch.io/t/4677526/import-saves-how)
     */
    private fun applyPendingBrowserDataImport() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(PREF_PENDING_BROWSER_DATA_IMPORT, false))
            return
        prefs.edit { remove(PREF_PENDING_BROWSER_DATA_IMPORT) }

        val pendingZip = File(filesDir, WebViewDataBackup.PENDING_BROWSER_DATA_ZIP)
        if (pendingZip.exists()) {
            val ok = WebViewDataBackup.importData(this, pendingZip)
            Log.i(LOGGING_TAG, "Applied pending browser data import: $ok")
        }
        pendingZip.delete()
    }

    private fun registerUpdateCheckTask(
        requiresUnmetered: Boolean,
        existingWorkPolicy: ExistingPeriodicWorkPolicy
    ) {
        val constraints = Constraints.Builder().run {
            if (requiresUnmetered)
                setRequiredNetworkType(NetworkType.UNMETERED)
            else
                setRequiredNetworkType(NetworkType.CONNECTED)
            build()
        }
        val updateCheckRequest =
            PeriodicWorkRequestBuilder<UpdateChecker.Worker>(1, TimeUnit.DAYS).run {
                //addTag(UPDATE_CHECK_TASK_TAG)
                setConstraints(constraints)
                setInitialDelay(10, TimeUnit.HOURS)
                build()
            }

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            UPDATE_CHECK_TASK_TAG,
            existingWorkPolicy,
            updateCheckRequest
        )
    }

    private fun setThemeFromPreferences(prefs: SharedPreferences) {
        when (prefs.getString("preference_theme", "site")) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            "site" -> when (prefs.getString("current_site_theme", null)) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }
    }

    private fun setLangFromPreferences(prefs: SharedPreferences) {
        val systemLocale = Utils.getPreferredLocale(applicationContext).toLanguageTag()
        val newLocale = when (prefs.getString(PREF_LANG, "default")) {
            "system" -> systemLocale
            "site" -> prefs.getString(PREF_LANG_SITE_LOCALE, "en")
            else -> {
                val siteLocale = prefs.getString(PREF_LANG_SITE_LOCALE, "en")
                if (siteLocale == "en")
                    systemLocale
                else
                    siteLocale
            }
        }
        prefs.edit(true) {
            if (newLocale != prefs.getString(PREF_LANG_LOCALE_NEXT,
                    prefs.getString(PREF_LANG_LOCALE, systemLocale)))
                putString(PREF_LANG_LOCALE_NEXT, newLocale)
        }
    }

    /**
     * ACRA crash reports
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.KEY_VALUE_LIST
            reportContent = listOf(
                ReportField.ANDROID_VERSION,
                ReportField.BUILD_CONFIG,
                ReportField.STACK_TRACE,
                ReportField.LOGCAT,
                ReportField.SHARED_PREFERENCES
            )
            excludeMatchingSharedPreferencesKeys = listOf(".*(racial|justice|palestine|ukraine|trans_texas).*")

            mailSender {
                mailTo = "tofu.techzone@gmail.com"
                subject = "[Insert Mitch bug here]"
                //Email body is English only, this is intentional
                body = """
                    > Please describe what you were doing when you got the error.
                    
                    > Thank you for your help!
                """.trimIndent()
                reportFileName = "error-report-and-logs.txt"
            }

            dialog {
                reportDialogClass = CrashDialog::class.java
            }
        }
    }
}
