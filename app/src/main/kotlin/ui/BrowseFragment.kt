package garden.appl.mitch.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.SpannedString
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ShareCompat
import androidx.core.net.toUri
import androidx.core.view.MenuCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.textfield.TextInputEditText
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import com.leinardi.android.speeddial.SpeedDialView.OnChangeListener
import tofu.gg.mitchy.BuildConfig
import garden.appl.mitch.ItchWebsiteUtils
import garden.appl.mitch.PREF_BROWSE_GENRES_FILTER
import garden.appl.mitch.PREF_BROWSE_LAST_SCROLL
import garden.appl.mitch.PREF_BROWSE_LAST_URL
import garden.appl.mitch.PREF_BROWSE_TAGS_FILTER
import garden.appl.mitch.PREF_BOTTOM_NAV_ALWAYS_VISIBLE
import garden.appl.mitch.PREF_DEBUG_WEB_GAMES_IN_BROWSE_TAB
import garden.appl.mitch.PREF_DESKTOP_MODE
import garden.appl.mitch.PREF_GENRE_EXCLUSION_ENABLED
import garden.appl.mitch.PREF_SCROLL_TO_TOP_ENABLED
import garden.appl.mitch.PREF_SEARCH_ENABLED
import garden.appl.mitch.PREF_TAG_EXCLUSION_ENABLED
import garden.appl.mitch.PREF_UPDATE_TRACKING_ENABLED
import garden.appl.mitch.PREF_WARN_WRONG_OS
import garden.appl.mitch.PREF_WEB_ANDROID_FILTER
import tofu.gg.mitchy.R
import garden.appl.mitch.SessionCookieStore
import garden.appl.mitch.Utils
import garden.appl.mitch.client.ItchBrowseHandler
import garden.appl.mitch.client.ItchTag
import garden.appl.mitch.client.ItchTagsParser
import garden.appl.mitch.client.ItchWebsiteParser
import garden.appl.mitch.client.SpecialBundleHandler
import garden.appl.mitch.data.ItchGenre
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.installation.Installation
import tofu.gg.mitchy.databinding.BrowseFragmentBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.SecureRandom
import java.util.HashMap
import java.util.Locale


class BrowseFragment : Fragment(), CoroutineScope by MainScope() {
    companion object {
        private const val LOGGING_TAG = "BrowseFragment"
        private const val WEB_VIEW_STATE_KEY: String = "WebView"
        private const val GENRES_EXCLUSION_FILTER: String = "GenresFilter"
        private const val TAGS_EXCLUSION_FILTER: String = "TagsFilter"

        private const val APP_BAR_ACTIONS_DEFAULT = 1
        private const val APP_BAR_ACTIONS_FROM_HTML = 2
        private const val APP_BAR_ACTIONS_GAME_JAM = 3

        // Desktop-mode rendering for the in-app browser.
        // (mentioned in https://itch.io/t/6622118/any-way-to-export-in-app-browser-data-or-put-in-app-browser-into-desktop-mode)
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
    
    private var _binding: BrowseFragmentBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var chromeClient: MitchBrowserWebChromeClient
    private lateinit var webView: MitchWebView
    private var webViewJSNonce: Long = 0

    private var browseHandler: ItchBrowseHandler? = null
    private var currentDoc: Document? = null
    private var currentInfo: ItchBrowseHandler.Info? = null

    /**
     * True while a page load is in flight. Used by the scroll-aware bottom-nav auto-hide to
     * ignore the big programmatic scroll jumps caused by page loads and scroll restoration.
     */
    private var pageLoading = false

    /**
     * True while a remembered scroll position is being programmatically restored. The restore
     * loop keeps retrying after [pageLoading] is cleared (catalogue pages grow in batches),
     * so the scroll listener must ignore those programmatic jumps too.
     */
    private var scrollRestoring = false

    /**
     * Cached value of the "keep the bottom navigation bar always visible" preference. Cached
     * here instead of reading SharedPreferences on every scroll event, which is a disk-backed
     * read per event; refreshed from [onResume] and [onHiddenChanged].
     */
    private var bottomNavAlwaysVisible = false

    /**
     * Vertical scroll position remembered per page URL, so going back from a game page
     * lands where the user left off instead of at the top of the list. WebView's own
     * history usually restores scroll position, but itch.io catalogue pages reload on
     * back-navigation and reset to the top, so we save/restore it ourselves.
     */
    private val savedScrollPositions = HashMap<String, Int>()

    val isWebFullscreen: Boolean
        get() = chromeClient.customViewCallback != null
    val url: String?
        get() = webView.url

    /**
     * Game genres to hide from a catalogue page.
     * Persisted in SharedPreferences so the filter is kept when navigating to a game page
     * and back, and across app restarts.
     */
    private var genresExclusionFilter: Set<ItchGenre>? = null

    /**
     * Game tags to hide from a catalogue page, by localized tag name.
     * Persisted in SharedPreferences so the filter is kept when navigating to a game page
     * and back, and across app restarts.
     */
    private var tagsExclusionFilter: Set<String>? = null

    private val openDocumentLauncher = registerForActivityResult(OpenDocument()) { uri ->
        uri?.let { filePathCallback?.onReceiveValue(arrayOf(it)) }
        filePathCallback = null
    }
    private val openMultipleDocumentsLauncher = registerForActivityResult(OpenMultipleDocuments()) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // True right after the back-on-game-page fallback: the next finished load is the start
    // page we escaped to, and its history must be cleared so back can't loop back into the
    // game page (the fallback loadUrl would otherwise leave the game page in the stack).
    private var pendingHistoryClear = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        browseHandler = ItchBrowseHandler(context as MainActivity, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore the exclusion filters. A saved instance state (e.g. a rotation) takes
        // precedence; otherwise fall back to the last persisted values so the filters
        // survive navigation away from the catalogue and app restarts.
        genresExclusionFilter = savedInstanceState?.getStringArray(GENRES_EXCLUSION_FILTER)?.map {
            ItchGenre.valueOf(it)
        }?.toSet()
        tagsExclusionFilter = savedInstanceState?.getStringArray(TAGS_EXCLUSION_FILTER)?.toSet()
        if (genresExclusionFilter == null && tagsExclusionFilter == null)
            loadFiltersFromPrefs()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)

        _binding = BrowseFragmentBinding.inflate(inflater, container, false)
        webView = binding.webView
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chromeClient = MitchBrowserWebChromeClient()

        @SuppressLint("SetJavaScriptEnabled")
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
//        webView.settings.setAppCacheEnabled(true)
//        webView.settings.setAppCachePath(File(requireContext().filesDir, "html5-app-cache").path)
        webView.settings.databaseEnabled = true

        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = MitchBrowserWebViewClient(this)
        webView.webChromeClient = chromeClient

        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webViewJSNonce = SecureRandom().nextLong()
        // JavaScript interface has catastrophic security vulnerabilities in old Android versions.
        // Explicitly disable it even though minSdk is greater than JellyBean.
        @SuppressLint("ObsoleteSdkInt")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN)
            webView.addJavascriptInterface(MitchJavaScriptInterface(this), "mitchCustomJS")

        applyDesktopModeUserAgent()

        updateBottomNavAlwaysVisible()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            Log.d(LOGGING_TAG, "Requesting download...")
            launch(Dispatchers.IO) {
                browseHandler?.onDownloadStarted(url, userAgent, contentDisposition, mimeType,
                    if (contentLength > 0) contentLength else null)
            }
        }

        // Auto-hide the bottom navigation while scrolling the page down, unless the user enabled
        // "keep the bottom navigation bar always visible". Skip while a page is loading or its
        // scroll position is being restored, which produce big programmatic scroll jumps.
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (pageLoading || scrollRestoring || currentDoc == null) return@setOnScrollChangeListener
            if (bottomNavAlwaysVisible) return@setOnScrollChangeListener
            (activity as? MainActivity)?.onContentScrolled(scrollY - oldScrollY)
        }

        webView.setOnLongClickListener { _ ->
            val result = webView.hitTestResult
            val url = result.extra ?: return@setOnLongClickListener false
            when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
                    val data = ClipData.newPlainText("Copied URL", url)
                    (clipboard as ClipboardManager).setPrimaryClip(data)
                    Toast.makeText(requireContext(), R.string.popup_link_copied, Toast.LENGTH_LONG)
                        .show()
                    return@setOnLongClickListener true
                }
                else -> return@setOnLongClickListener false
            }
        }


        //Set up FAB buttons
        //(colors don't matter too much as they will be set by updateUI() anyway)
        val speedDial = (activity as MainActivity).binding.speedDial
        setupSpeedDialActions(speedDial)

        speedDial.setOnActionSelectedListener { actionItem ->
            speedDial.close()

            when (actionItem.id) {
                R.id.browser_reload -> {
                    webView.reload()
                    return@setOnActionSelectedListener true
                }
                R.id.browser_share -> {
                    ShareCompat.IntentBuilder.from(requireActivity())
                        .setType("text/plain")
                        .setChooserTitle(R.string.browser_share)
                        .setText(url)
                        .startChooser()
                    return@setOnActionSelectedListener true
                }
                // https://stackoverflow.com/questions/2201917/how-can-i-open-a-url-in-androids-web-browser-from-my-application#61488105
                R.id.browser_open_in_browser -> {
                    val resolveIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
                    val resolveInfo = requireContext().packageManager
                        .resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    val defaultBrowserPackageName = resolveInfo?.activityInfo?.packageName

                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(url)

                    Log.d(LOGGING_TAG, "Default browser: $defaultBrowserPackageName")
                    if (defaultBrowserPackageName == null ||
                        defaultBrowserPackageName == "android") {
                        // "android" means no default browser is set
                        val title = resources.getString(R.string.browser_open_in_browser)
                        startActivity(Intent.createChooser(intent, title))
                    } else {
                        intent.setPackage(defaultBrowserPackageName)
                        startActivity(intent)
                    }
                    return@setOnActionSelectedListener true
                }
                R.id.browser_search -> {
                    showSearchDialog()
                    return@setOnActionSelectedListener true
                }
                R.id.browser_scroll_to_top -> {
                    webView.evaluateJavascript(
                        "window.scrollTo({top: 0, behavior: 'smooth'});", null
                    )
                    return@setOnActionSelectedListener true
                }
                R.id.browser_filter_exclude_tags -> {
                    val currentExclusions = tagsExclusionFilter?.toMutableSet() ?: mutableSetOf()
                    launch {
                        val tags = try {
                            ItchTagsParser.parseTags(ItchTag.Classification.GAME)
                        } catch (e: Exception) {
                            Log.e(LOGGING_TAG, "Could not load tags", e)
                            Toast.makeText(
                                requireContext(),
                                R.string.settings_exclude_tags_error,
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        if (tags.isEmpty()) {
                            Toast.makeText(
                                requireContext(),
                                R.string.settings_exclude_tags_error,
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        val tagNames = tags.map { it.name }
                        val checked = BooleanArray(tagNames.size) { index ->
                            currentExclusions.contains(tagNames[index])
                        }
                        val dialog = AlertDialog.Builder(requireContext()).run {
                            setTitle(R.string.browser_filter_exclude_tags)
                            setMultiChoiceItems(tagNames.toTypedArray(), checked) { _, index, isChecked ->
                                if (isChecked)
                                    currentExclusions.add(tagNames[index])
                                else
                                    currentExclusions.remove(tagNames[index])
                            }
                            setPositiveButton(R.string.dialog_apply) { _, _ ->
                                tagsExclusionFilter = currentExclusions.toSet()
                                saveFiltersToPrefs()
                                updateUI()
                            }
                            setNegativeButton(R.string.dialog_reset) { _, _ ->
                                tagsExclusionFilter = emptySet()
                                saveFiltersToPrefs()
                                updateUI()
                            }
                            create()
                        }
                        dialog.show()
                    }
                    return@setOnActionSelectedListener true
                }
                R.id.browser_filter_exclude_genres -> {
                    data class GenreChoice(val genre: ItchGenre) {
                        override fun toString() = requireContext().getString(genre.nameResource)
                    }
                    val choices = ItchGenre.entries.map { GenreChoice(it) }

                    val adapter = ArrayAdapter(requireContext(),
                        android.R.layout.simple_list_item_multiple_choice, choices)
                    val newExclusionFilter = genresExclusionFilter?.toMutableSet()
                        ?: return@setOnActionSelectedListener false

                    val listView = ListView(context).apply {
                        choiceMode = ListView.CHOICE_MODE_MULTIPLE
                        setOnItemClickListener { parent, view, position, id ->
                            val choice = parent.getItemAtPosition(position) as GenreChoice
                            if (this@apply.isItemChecked(position))
                                newExclusionFilter.add(choice.genre)
                            else
                                newExclusionFilter.remove(choice.genre)
                        }
                        this.adapter = adapter
                    }

                    val dialog = AlertDialog.Builder(requireContext()).run {
                        setTitle(R.string.browser_filter_exclude_genres)
                        setView(listView)
                        setPositiveButton(R.string.dialog_apply) { _, _ ->
                            genresExclusionFilter = newExclusionFilter
                            saveFiltersToPrefs()
                            updateUI()
                        }
                        setNegativeButton(R.string.dialog_reset) { _, _ ->
                            genresExclusionFilter = emptySet()
                            saveFiltersToPrefs()
                            updateUI()
                        }
                        create()
                    }
                    for (excludedGenre in newExclusionFilter) {
                        listView.setItemChecked(adapter.getPosition(GenreChoice(excludedGenre)), true)
                    }
                    dialog.show()
                    return@setOnActionSelectedListener true
                }
                else -> {
                    return@setOnActionSelectedListener false
                }
            }
        }

        // Load page, this will also update the UI
        val webViewBundle = savedInstanceState?.getBundle(WEB_VIEW_STATE_KEY)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val lastUrl = prefs.getString(PREF_BROWSE_LAST_URL, null)
        // When the last page is reloaded from scratch after a process kill, its history is
        // gone; seed the scroll map so the reload restores the position the user was at.
        val lastScroll = prefs.getInt(PREF_BROWSE_LAST_SCROLL, 0)
        if (lastUrl != null && lastScroll > 0)
            savedScrollPositions[lastUrl] = lastScroll
        Utils.logDebug(LOGGING_TAG, "Restoring $webViewBundle (last URL: $lastUrl)")
        if (webViewBundle != null) {
            webView.restoreState(webViewBundle)
            // WebView.restoreState can be unreliable after the process was killed: it may
            // revive an old history entry ("the page I was browsing yesterday"). If the
            // restored page doesn't match the last page we actually saw, reload the saved URL.
            if (lastUrl != null && webView.url != lastUrl) {
                Log.d(LOGGING_TAG, "Restored page ${webView.url} != saved page $lastUrl, reloading")
                loadUrl(lastUrl)
            }
        } else {
            loadUrl(lastUrl ?: ItchWebsiteUtils.getMainBrowsePage(requireContext()))
        }
    }

    /**
     * @return true if the user can't go back in the web history
     */
    fun onBackPressed(): Boolean {
        // Leave fullscreen before doing anything else, and actually restore the
        // normal UI — otherwise back would just dismiss the custom view while the
        // fullscreen overlay stayed in place (or, previously, close the whole app).
        if (isWebFullscreen) {
            chromeClient.onHideCustomView()
            return false
        }

        if (webView.canGoBack()) {
            rememberCurrentScroll()
            webView.goBack()
            return false
        }

        // After a process restart the WebView can be sitting on a game page with an
        // empty back stack (WebView history is not reliably saved/restored). Exiting
        // the app then would kick the user out instead of returning them to browsing.
        val currentUrl = webView.url
        if (currentUrl != null && ItchWebsiteUtils.isGamePageUrl(Uri.parse(currentUrl))) {
            // Clear the history after this load lands (see onPageFinished), so the start
            // page becomes the history root instead of the game page we're leaving behind.
            pendingHistoryClear = true
            loadUrl(ItchWebsiteUtils.getMainBrowsePage(requireContext()))
            return false
        }

        // On the Browse start page with no history: a back press while scrolled down
        // scrolls to the top instead of closing the app. Only the start page gets this;
        // game pages and other lists keep their existing behavior.
        if (currentUrl != null && isMainBrowsePage(currentUrl) && webView.scrollY > 1) {
            webView.scrollTo(0, 0)
            return false
        }
        return true
    }

    /**
     * @return whether [url] is the configured Browse start page, ignoring the exclude-tag
     * query parameter and trailing-slash differences.
     */
    private fun isMainBrowsePage(url: String): Boolean {
        val current = Uri.parse(url)
        val main = Uri.parse(ItchWebsiteUtils.getMainBrowsePage(requireContext()))
        return current.scheme == main.scheme
                && current.host == main.host
                && (current.path ?: "").removeSuffix("/") == (main.path ?: "").removeSuffix("/")
    }

    /**
     * Remembers the current page's vertical scroll position under its URL, so the
     * position can be restored when the user navigates back to the page.
     */
    private fun rememberCurrentScroll() {
        val url = webView.url ?: return
        savedScrollPositions[url] = webView.scrollY
    }

    /**
     * Restores the remembered scroll position for [url] once the page has loaded.
     * Catalogue pages grow in batches (infinite scroll), so keep retrying until the
     * document is tall enough to honor the scroll (or give up after a while).
     */
    private fun restoreScrollIfNeeded(url: String) {
        val targetY = savedScrollPositions.remove(url) ?: return
        if (targetY <= 0)
            return
        // Keep the scroll listener from reacting to these programmatic jumps: the retries below
        // fire after onPageFinished cleared pageLoading, and each retry jumps by a large delta.
        scrollRestoring = true
        var attempt = 0
        val restore = object : Runnable {
            override fun run() {
                attempt++
                if (webView.url != url) {
                    scrollRestoring = false
                    return
                }
                webView.scrollTo(0, targetY)
                if (webView.scrollY < targetY - 10 && attempt < 40) {
                    webView.postDelayed(this, 500)
                } else {
                    scrollRestoring = false
                }
            }
        }
        restore.run()
    }

    /**
     * Refreshes the cached [bottomNavAlwaysVisible] flag from preferences. Called when the
     * fragment becomes visible or resumes, so the scroll listener always honors the latest
     * setting without paying a SharedPreferences read per scroll event.
     */
    private fun updateBottomNavAlwaysVisible() {
        bottomNavAlwaysVisible = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(PREF_BOTTOM_NAV_ALWAYS_VISIBLE, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArray(GENRES_EXCLUSION_FILTER, genresExclusionFilter?.map {
            it.name
        }?.toTypedArray())
        outState.putStringArray(TAGS_EXCLUSION_FILTER, tagsExclusionFilter?.toTypedArray())

        val webViewState = Bundle()
        webView.saveState(webViewState)
        outState.putBundle(WEB_VIEW_STATE_KEY, webViewState)

        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()

        webView.onPause()
//        webView.pauseTimers()
        CookieManager.getInstance().flush()
        SessionCookieStore.capture(requireContext())

        // Remember the current page and its scroll position so both can be restored if
        // the process is killed while the app is in the background (WebView.saveState
        // alone is unreliable, and it doesn't survive a process kill).
        webView.url?.let { url ->
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putString(PREF_BROWSE_LAST_URL, url)
                .putInt(PREF_BROWSE_LAST_SCROLL, webView.scrollY)
                .apply()
        }
    }

    override fun onResume() {
        super.onResume()

        webView.onResume()
//        webView.resumeTimers()
        chromeClient.onResume()

        // Re-apply the dark theme whenever the fragment resumes: changing the app theme in
        // settings recreates the activity, and WebView.restoreState revives the page without
        // firing onPageStarted, so the previously injected `dark_theme` class would otherwise
        // stay behind after switching back to light mode.
        webView.evaluateJavascript(darkThemeInjectionJs(isAppDarkMode()), null)

        // Reflect preference changes (e.g. enabling/disabling the scroll-to-top button
        // or the tag exclusion filter) made while another fragment was visible.
        updateBottomNavAlwaysVisible()
        (activity as? MainActivity)?.binding?.speedDial?.let { speedDial ->
            setupSpeedDialActions(speedDial)
            updateUI()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // The Browse tab is kept alive while other tabs are shown (hidden via hide()/show()),
        // so its lifecycle stays RESUMED and onResume() does not fire again on tab switches.
        // Rebuild the speed dial when the tab is shown again so preference changes made in
        // another tab (e.g. toggling the scroll-to-top/search buttons) take effect.
        if (!hidden) {
            updateBottomNavAlwaysVisible()
            (activity as? MainActivity)?.binding?.speedDial?.let { speedDial ->
                setupSpeedDialActions(speedDial)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // MainActivity handles uiMode in configChanges, so an OS-level dark/light toggle does
        // not recreate the activity; re-apply the page theme so it follows the app theme live.
        webView.evaluateJavascript(darkThemeInjectionJs(isAppDarkMode()), null)
    }

    override fun onDestroy() {
        super.onDestroy()

        cancel()
        webView.destroy()
    }

    override fun onDetach() {
        super.onDetach()

        browseHandler = null
    }

    override fun onDestroyView() {
        super.onDestroyView()

        _binding = null
    }

    fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    /**
     * Restores the genre/tag exclusion filters from SharedPreferences.
     */
    private fun loadFiltersFromPrefs() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        genresExclusionFilter = prefs.getStringSet(PREF_BROWSE_GENRES_FILTER, null)
            ?.mapNotNull { name -> runCatching { ItchGenre.valueOf(name) }.getOrNull() }
            ?.toSet()
        tagsExclusionFilter = prefs.getStringSet(PREF_BROWSE_TAGS_FILTER, null)?.toSet()
    }

    /**
     * Persists the genre/tag exclusion filters to SharedPreferences so they survive
     * navigation away from the catalogue page and app restarts.
     */
    private fun saveFiltersToPrefs() {
        val editor = PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
        if (genresExclusionFilter != null)
            editor.putStringSet(PREF_BROWSE_GENRES_FILTER, genresExclusionFilter!!.map { it.name }.toSet())
        else
            editor.remove(PREF_BROWSE_GENRES_FILTER)
        if (tagsExclusionFilter != null)
            editor.putStringSet(PREF_BROWSE_TAGS_FILTER, tagsExclusionFilter!!)
        else
            editor.remove(PREF_BROWSE_TAGS_FILTER)
        editor.apply()
    }

    /**
     * Adapts the app's UI to the theme of the current web page.
     */
    fun updateUI() {
        updateUI(currentDoc, currentInfo)
    }

    fun restoreDefaultUI() {
        updateUI(null, null)
    }

    /**
     * Adapts the app's UI to the theme of a web page. Should only affect the UI while the browse
     * fragment is selected.
     * Must run on the UI thread!
     * @param doc the parsed DOM of the page the user is currently on. Null if the UI shouldn't adapt to any web page at all
     * @param info metadata about the page
     */
    private fun updateUI(doc: Document?, info: ItchBrowseHandler.Info?) {
        if (!this::chromeClient.isInitialized || isWebFullscreen)
            return

        val mainActivity = activity as? MainActivity ?: return
        if (!isVisible && doc != null)
            return

        val navBar = mainActivity.binding.bottomNavigationView
        val bottomGameBar = mainActivity.binding.bottomGameBar
        val speedDial = mainActivity.binding.speedDial
        // When enabled, the bottom navigation bar stays visible on game, creator and
        // forum pages instead of being hidden; the toggle lives in Settings.
        val keepBottomNavVisible = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(PREF_BOTTOM_NAV_ALWAYS_VISIBLE, false)
        val supportAppBar = mainActivity.supportActionBar
            ?: run {
                Log.e(LOGGING_TAG, "supportActionBar not ready yet, skipping page UI update")
                return
            }
        val appBar = mainActivity.binding.toolbar
        val gameButton = mainActivity.binding.gameButton
        val gameButtonInfo = mainActivity.binding.gameButtonInfo

        updateFiltersAndAction(speedDial)
        // Exclusion filters only make sense on catalogue pages; since they are now kept
        // across navigation, don't run their JS elsewhere. Each filter also respects its
        // settings toggle so a disabled filter stops hiding games even if a set was saved.
        if (url?.let { ItchWebsiteUtils.isGameCataloguePage(Uri.parse(it)) } == true) {
            if (PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean(PREF_GENRE_EXCLUSION_ENABLED, true)) {
                filterExcludedGenres()
            }
            if (PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean(PREF_TAG_EXCLUSION_ENABLED, true)) {
                filterExcludedTags()
            }
        }

        if (doc?.let { ItchWebsiteUtils.isGamePage(doc) } == true) {
            // Hide app's navbar after hiding web navbar
            val navBarHideCallback: (String) -> Unit = navBarHide@{
                if (!isVisible)
                    return@navBarHide
                if (!keepBottomNavVisible)
                    navBar.visibility = View.GONE

                val actions = ArrayList<Triple<String, Spanned, View.OnClickListener>>()
                var filesRequirePayment = false
                if (info?.purchasedInfo?.isNotEmpty() == true) {
                    for (purchasedInfo in info.purchasedInfo) {
                        val buttonText = if (info.hasAndroidVersion)
                            getString(R.string.game_install)
                        else
                            getString(R.string.game_download)
                        val buttonLabel = Utils.spannedFromHtml(purchasedInfo.ownershipReasonHtml)
                        val onButtonClick = View.OnClickListener {
                            mainActivity.browseUrl(purchasedInfo.downloadPage)
                        }
                        actions.add(Triple(buttonText, buttonLabel, onButtonClick))
                    }
                } else if (info?.bundleDownloadLink != null) {
                    val buttonText = getString(R.string.game_bundle_claim)
                    val buttonLabel = SpannedString(getString(resources.getIdentifier(
                        "game_bundle_" + info.specialBundle!!.slug,
                        "string",
                        requireContext().packageName
                    )))
                    val onButtonClick = View.OnClickListener {
                        lifecycleScope.launch {
                            SpecialBundleHandler.claimGame(
                                info.bundleDownloadLink,
                                info.game!!,
                                webView.settings.userAgentString
                            )
                            webView.reload()
                        }
                    }
                    actions.add(Triple(buttonText, buttonLabel, onButtonClick))
                } else if (info?.paymentInfo != null) {
                    val buttonText = if (!info.paymentInfo.isPaymentOptional) {
                        filesRequirePayment = true
                        getString(R.string.game_buy)
                    } else {
                        if (info.hasAndroidVersion)
                            getString(R.string.game_install)
                        else
                            getString(R.string.game_download)
                    }

                    val buttonLabel = Utils.spannedFromHtml(info.paymentInfo.messageHtml)
                    val onButtonClick = View.OnClickListener {
                        val purchaseUri = Uri.parse(info.game!!.storeUrl)
                            .buildUpon()
                            .appendPath("purchase")
                        goToPurchasePage(doc, info, purchaseUri.toString())
                    }
                    actions.add(Triple(buttonText, buttonLabel, onButtonClick))
                }
                if (info?.game?.webEntryPoint != null && info.webLaunchLabel != null) {
                    val buttonText = info.webLaunchLabel
                    val buttonLabel = SpannedString(getString(R.string.game_web_play_desc))
                    val onButtonClick = View.OnClickListener {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(info.game.webEntryPoint),
                            mainActivity,
                            GameActivity::class.java
                        )
                        intent.putExtra(GameActivity.EXTRA_GAME_ID, info.game.gameId)
                        intent.putExtra(GameActivity.EXTRA_LAUNCHED_FROM_INSTALL, false)
                        Log.d(LOGGING_TAG, "Starting $intent")
                        mainActivity.startActivity(intent)
                    }
                    actions.add(Triple(buttonText, buttonLabel, onButtonClick))
                }

                if (actions.size > 1) {
                    bottomGameBar.visibility = View.VISIBLE

                    gameButton.text = getString(R.string.game_multiple_options_get)
                    gameButtonInfo.text = if (info?.game?.webEntryPoint == null)
                        getString(R.string.game_multiple_options_desc_multiple_purchases)
                    else if (filesRequirePayment)
                        getString(R.string.game_multiple_options_desc_web_or_buy)
                    else
                        getString(R.string.game_multiple_options_desc_web_or_download)
                    gameButton.setOnClickListener {
                        val viewInflated: View = LayoutInflater.from(context)
                            .inflate(R.layout.dialog_game_get, view as ViewGroup, false)
                        val dialog = AlertDialog.Builder(requireContext()).run {
                            setTitle(info?.game?.name)
                            setView(viewInflated)
                            show()
                        }
                        val buttonsColumn =
                            viewInflated.findViewById<LinearLayout>(R.id.dialog_game_get_button_column)
                        val labelsColumn =
                            viewInflated.findViewById<LinearLayout>(R.id.dialog_game_get_desc_column)

                        for ((buttonText, label, onButtonClick) in actions) {
                            val button = LayoutInflater.from(context)
                                .inflate(
                                    R.layout.dialog_game_get_button,
                                    view as ViewGroup,
                                    false
                                )
                            (button as Button).apply {
                                text = buttonText
                                setOnClickListener { view ->
                                    dialog.hide()
                                    onButtonClick.onClick(view)
                                }
                                buttonsColumn.addView(this)
                            }

                            val labelView = LayoutInflater.from(context)
                                .inflate(
                                    R.layout.dialog_game_get_label,
                                    view as ViewGroup,
                                    false
                                )
                            labelView.findViewById<TextView>(R.id.game_get_dialog_option_label)
                                .text = label
                            labelsColumn.addView(labelView)
                        }
                    }
                } else if (actions.size == 1) {
                    bottomGameBar.visibility = View.VISIBLE

                    val (text, label, onButtonClick) = actions[0]
                    gameButton.text = text
                    gameButtonInfo.text = label
                    gameButton.setOnClickListener(onButtonClick)
                } else {
                    bottomGameBar.visibility = View.GONE
                }
            }
            if (ItchWebsiteUtils.siteHasNavbar(webView, doc)) {
                setSiteNavbarVisibility(false, navBarHideCallback)
            } else {
                setSiteNavbarVisibility(true, navBarHideCallback)
            }

            supportAppBar.title =
                Utils.spannedFromHtml("<b>${Html.escapeHtml(ItchWebsiteParser.getGameName(doc))}</b>")

            appBar.menu.clear()
            MenuCompat.setGroupDividerEnabled(appBar.menu, true)
            addAppBarActionsFromHtml(appBar, doc)
            addDefaultAppBarActions(appBar)
            supportAppBar.show()
        } else if (doc?.let { ItchWebsiteUtils.isUserPage(it) } == true) {
            val appBarTitle =
                "<b>${Html.escapeHtml(ItchWebsiteParser.getUserName(doc))}</b>"
            supportAppBar.title = Utils.spannedFromHtml(appBarTitle)

            appBar.menu.clear()
            addDefaultAppBarActions(appBar)
            supportAppBar.show()

            bottomGameBar.visibility = View.GONE
            if (!keepBottomNavVisible)
                navBar.visibility = View.GONE
        } else if (doc?.let { ItchWebsiteUtils.isJamOrForumPage(it) } == true) {
            val appBarTitle =
                "<b>${Html.escapeHtml(ItchWebsiteParser.getForumOrJamName(doc))}</b>"
            supportAppBar.title = Utils.spannedFromHtml(appBarTitle)

            appBar.menu.clear()
            addDefaultAppBarActions(appBar)
            supportAppBar.show()

            bottomGameBar.visibility = View.GONE
            if (!keepBottomNavVisible)
                navBar.visibility = View.GONE
        } else {
            navBar.visibility = View.VISIBLE
            bottomGameBar.visibility = View.GONE
            supportAppBar.hide()
        }

        // Any page change can leave the nav auto-hidden from a previous page; bring it back so
        // the content sits above the nav/game bar again instead of a stale hidden state.
        mainActivity.resetBottomNav()

        // Colors adapt to game theme

        val defaultAccentColor = Utils.getColor(requireContext(), R.color.colorAccent)
        val defaultWhiteColor = Utils.getColor(requireContext(), R.color.colorPrimary)
        val defaultBlackColor = Utils.getColor(requireContext(), R.color.colorPrimaryDark)

        val defaultBgColor = Utils.getColor(requireContext(), R.color.colorBackground)
        val defaultFgColor = Utils.getColor(requireContext(), R.color.colorForeground)

        val gameThemeBgColor = doc?.run { ItchWebsiteUtils.getBackgroundUIColor(doc) }
        val gameThemeButtonColor = doc?.run { ItchWebsiteUtils.getAccentUIColor(doc) }
        val gameThemeButtonFgColor = doc?.run { ItchWebsiteUtils.getAccentFgUIColor(doc) }

//        Log.d(LOGGING_TAG, "game theme bg color: $gameThemeBgColor")
//        Log.d(LOGGING_TAG, "game theme button color: $gameThemeButtonColor")
//        Log.d(LOGGING_TAG, "game theme button fg color: $gameThemeButtonFgColor")

        val accentColor = gameThemeButtonColor ?: defaultAccentColor
        val accentFgColor = gameThemeButtonFgColor ?: defaultWhiteColor

        val bgColor = gameThemeBgColor ?: defaultBgColor
        val fgColor = if (gameThemeBgColor == null) defaultFgColor else defaultWhiteColor

        speedDial.mainFabClosedBackgroundColor = accentColor
        speedDial.mainFabOpenedBackgroundColor = accentColor
        speedDial.mainFabClosedIconColor = accentFgColor
        speedDial.mainFabOpenedIconColor = accentFgColor
        speedDial.setOnChangeListener(object : OnChangeListener {
            override fun onMainActionSelected(): Boolean {
                // NO-OP
                return false
            }

            override fun onToggleChanged(isOpen: Boolean) {
                speedDial.elevation = resources.getDimension(
                    if (isOpen)
                        R.dimen.fab_elevation_open
                    else
                        R.dimen.fab_elevation_closed
                )
            }
        })
        for (actionItem in speedDial.actionItems) {
            val newActionItem = SpeedDialActionItem.Builder(actionItem)
                .setFabBackgroundColor(bgColor)
                .setFabImageTintColor(fgColor)
                .setLabelBackgroundColor(bgColor)
                .setLabelColor(fgColor)
                .create()
            speedDial.replaceActionItem(actionItem, newActionItem)
        }
        binding.progressBar.progressDrawable.setTint(accentColor)
        appBar.setBackgroundColor(bgColor)
        appBar.setTitleTextColor(fgColor)
        appBar.overflowIcon?.setTint(fgColor)

        // When both the install bar and the bottom navigation bar are visible, slightly
        // darken the install bar so the two don't read as one continuous bar.
        val gameBarColor = if (keepBottomNavVisible) Utils.darkenColor(bgColor, 0.15f) else bgColor
        bottomGameBar.setBackgroundColor(gameBarColor)
        gameButtonInfo.setTextColor(defaultWhiteColor)
        gameButton.setTextColor(accentFgColor)
        gameButton.setBackgroundColor(accentColor)

        // Theme the bottom navigation bar to match the page's game/user theme, the same
        // way the toolbar and install bar adapt. On pages without a theme these fall back
        // to the app's default colors, so the look is unchanged there.
        val navItemColorList = Utils.colorStateListOf(
            intArrayOf(android.R.attr.state_checked) to accentColor,
            intArrayOf() to fgColor
        )
        navBar.setBackgroundColor(bgColor)
        navBar.itemBackground = ColorDrawable(bgColor)
        navBar.itemIconTintList = navItemColorList
        navBar.itemTextColor = navItemColorList

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mainActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            mainActivity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            mainActivity.window.statusBarColor = bgColor
            if (fgColor == defaultBlackColor)
                mainActivity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                mainActivity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun setupSpeedDialActions(speedDial: SpeedDialView) {
        speedDial.clearActionItems()
        speedDial.addActionItem(SpeedDialActionItem.Builder(R.id.browser_reload, R.drawable.ic_baseline_refresh_24)
            .setLabel(R.string.browser_reload)
            .create()
        )
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (prefs.getBoolean(PREF_SEARCH_ENABLED, true)) {
            speedDial.addActionItem(SpeedDialActionItem.Builder(R.id.browser_search, R.drawable.ic_baseline_search_24)
                .setLabel(R.string.browser_search)
                .create()
            )
        }
        if (prefs.getBoolean(PREF_SCROLL_TO_TOP_ENABLED, true)) {
            speedDial.addActionItem(SpeedDialActionItem.Builder(
                R.id.browser_scroll_to_top, R.drawable.ic_baseline_keyboard_arrow_up_24)
                .setLabel(R.string.browser_scroll_to_top)
                .create()
            )
        }
        speedDial.addActionItem(SpeedDialActionItem.Builder(R.id.browser_open_in_browser, R.drawable.ic_baseline_open_in_browser_24)
            .setLabel(R.string.browser_open_in_browser)
            .create()
        )
        speedDial.addActionItem(SpeedDialActionItem.Builder(R.id.browser_share, R.drawable.ic_baseline_share_24)
            .setLabel(R.string.browser_share)
            .create()
        )
    }

    private fun updateFiltersAndAction(speedDial: SpeedDialView) {
        if (url == null)
            return
        val uri = Uri.parse(url)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (ItchWebsiteUtils.isGameCataloguePage(uri)) {
            // Normally restored in onCreate; reload from prefs just in case updateUI
            // ever runs before that.
            if (genresExclusionFilter == null && tagsExclusionFilter == null)
                loadFiltersFromPrefs()
            val genreExcludeSet = genresExclusionFilter ?: emptySet<ItchGenre>()
            // updateUI runs on every page load/resize/resume, and addActionItem appends
            // without deduplicating, so drop any existing filter actions first — otherwise
            // the FAB menu accumulates one duplicate filter button per UI update.
            while (speedDial.removeActionItemById(R.id.browser_filter_exclude_genres) != null) { }
            while (speedDial.removeActionItemById(R.id.browser_filter_exclude_tags) != null) { }
            if (prefs.getBoolean(PREF_GENRE_EXCLUSION_ENABLED, true)) {
                speedDial.addActionItem(SpeedDialActionItem.Builder(R.id.browser_filter_exclude_genres, R.drawable.ic_baseline_filter_alt_24).run {
                    if (genreExcludeSet.isEmpty())
                        setLabel(R.string.browser_filter_exclude_genres)
                    else
                        setLabel(resources.getQuantityString(R.plurals.browser_filter_exclude_genres_active,
                            genreExcludeSet.size, genreExcludeSet.size))
                    create()
                })
            }

            if (prefs.getBoolean(PREF_TAG_EXCLUSION_ENABLED, true)) {
                val tagExcludeCount = tagsExclusionFilter?.size ?: 0
                speedDial.addActionItem(SpeedDialActionItem.Builder(R.id.browser_filter_exclude_tags, R.drawable.ic_baseline_filter_alt_24).run {
                    if (tagExcludeCount == 0)
                        setLabel(R.string.browser_filter_exclude_tags)
                    else
                        setLabel(resources.getQuantityString(R.plurals.browser_filter_exclude_tags_active,
                            tagExcludeCount, tagExcludeCount))
                    create()
                })
            }
        } else {
            // Don't forget the exclusion filters here: they must survive a round trip to a
            // game page and back. Just hide the FAB items, which only apply to catalogue pages.
            speedDial.removeActionItemById(R.id.browser_filter_exclude_genres)
            speedDial.removeActionItemById(R.id.browser_filter_exclude_tags)
        }
        speedDial.show()
    }

    /**
     * Go to a game's purchase page, and possibly show a warning dialog
     * if the game is not an Android game
     */
    private fun goToPurchasePage(doc: Document, info: ItchBrowseHandler.Info, url: String) {
        val mainActivity = activity as? MainActivity ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(mainActivity)

        if (!info.hasAndroidVersion && info.hasWindowsMacOrLinuxVersion
            && prefs.getBoolean(PREF_WARN_WRONG_OS, true)) {

            val platforms = ItchWebsiteParser.getInstallationsPlatforms(doc)

            var foundExtras = false
            for (platformBitmap in platforms) {
                if (platformBitmap == Installation.PLATFORM_NONE) {
                    foundExtras = true
                    break
                }
            }

            val dialog = AlertDialog.Builder(mainActivity).run {
                setTitle(android.R.string.dialog_alert_title)
                setIconAttribute(android.R.attr.alertDialogIcon)

                val message = if (foundExtras)
                    R.string.dialog_purchase_wrong_os_has_extras
                else
                    R.string.dialog_purchase_wrong_os
                setMessage(getString(message, info.game!!.name))

                val positiveButton = if (foundExtras) android.R.string.ok else R.string.dialog_yes
                val negativeButton = if (foundExtras) android.R.string.cancel else R.string.dialog_no
                setPositiveButton(positiveButton) { _, _ ->
                    mainActivity.browseUrl(url)
                }
                setNegativeButton(negativeButton) { _, _ ->
                    // no-op
                }

                create()
            }
            dialog.show()
        } else {
            mainActivity.browseUrl(url)
        }
    }

    /**
     * Add app bar actions from itch.io toolbar, which appears on game pages and devlog pages.
     * Also adds a Subscription button for game URLS.
     *
     * Should run on the UI thread!
     *
     * @param doc the parsed HTML document of a game store page or devlog page
     * @param appBar the app UI's top Toolbar
     */
    private fun addAppBarActionsFromHtml(appBar: Toolbar, doc: Document) {
        appBar.menu.clear()

        addSubscriptionAction(appBar, doc)

        val navbarItems = doc.getElementById("user_tools")?.children() ?: return

        while (navbarItems.isNotEmpty()) {
            val item = navbarItems.last()!!
            navbarItems.removeAt(navbarItems.size - 1)

            val url = item.children().firstOrNull()?.attr("href") ?: continue

            if (item.getElementsByClass("related_games_btn").isNotEmpty()) {
                appBar.menu.add(APP_BAR_ACTIONS_FROM_HTML, 7, 7, R.string.menu_game_related)
                    .setOnMenuItemClickListener {
                        loadUrl(url)
                        true
                    }
            } else if (item.getElementsByClass("rate_game_btn").isNotEmpty()) {
                appBar.menu.add(APP_BAR_ACTIONS_FROM_HTML, 6, 6, R.string.menu_game_rate)
                    .setOnMenuItemClickListener {
                        loadUrl(url)
                        true
                    }
                    .setIcon(R.drawable.ic_baseline_rate_review_24)
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            } else if (item.hasClass("devlog_link")) {
                appBar.menu.add(APP_BAR_ACTIONS_FROM_HTML, 5, 5, R.string.menu_game_devlog)
                    .setOnMenuItemClickListener {
                        loadUrl(url)
                        true
                    }
            } else if (item.getElementsByClass("add_to_collection_btn").isNotEmpty()) {
                appBar.menu.add(APP_BAR_ACTIONS_FROM_HTML, 4, 4, R.string.menu_game_collection)
                    .setOnMenuItemClickListener {
                        loadUrl(url)
                        true
                    }
            } else if (item.getElementsByClass("view_more").isNotEmpty()) {
                // Cannot rely on ItchWebsiteParser, because its method requires the current URL,
                // and while loading another page, url changes prematurely
                // (leading to crashes...)
                val authorName =
                    item.getElementsByClass("mobile_label").firstOrNull()?.text() ?: ""

                val menuItemName =
                    if (item.getElementsByClass("full_label")
                            .firstOrNull()?.text()?.contains(authorName) == true)
                        resources.getString(R.string.menu_game_author, authorName)
                    else
                        resources.getString(R.string.menu_game_author_generic)

                appBar.menu.add(APP_BAR_ACTIONS_FROM_HTML, 3, 3, menuItemName)
                    .setOnMenuItemClickListener {
                        loadUrl(url)
                        true
                    }
            } else if (item.hasClass("jam_entry")) {
                val menuItemName = item.children().firstOrNull()?.text() ?: continue

                appBar.menu.add(APP_BAR_ACTIONS_GAME_JAM, 0, 0, menuItemName)
                    .setOnMenuItemClickListener {
                        loadUrl(url)
                        true
                    }
//                    .setIcon(R.drawable.ic_baseline_emoji_events_24)
//                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
    }

    /**
     * Adds a "track updates" action to the app bar for store pages and download pages,
     * letting the user subscribe to individual files of a game, or unsubscribe entirely.
     * Only shown when per-game update tracking is enabled in the settings.
     */
    private fun addSubscriptionAction(appBar: Toolbar, doc: Document) {
        if (!ItchWebsiteUtils.isStorePage(doc) && !ItchWebsiteUtils.isDownloadPage(doc))
            return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (!prefs.getBoolean(PREF_UPDATE_TRACKING_ENABLED, true))
            return

        appBar.menu.add(APP_BAR_ACTIONS_DEFAULT, 0, 0, R.string.menu_game_subscribe)
            .setOnMenuItemClickListener {
                this.launch {
                    handleSubscriptionAction(doc)
                }
                true
            }
    }

    private suspend fun handleSubscriptionAction(doc: Document) {
        val subscribedUploads = getSubscribedUploadsForGame(doc)
        if (subscribedUploads.isEmpty()) {
            showSubscriptionDialog(doc)
            return
        }
        val options = arrayOf(
            requireContext().getString(R.string.menu_game_subscribe_more),
            requireContext().getString(R.string.menu_game_unsubscribe)
        )
        AlertDialog.Builder(requireContext()).run {
            setTitle(R.string.dialog_subscribe_title)
            setItems(options) { _, which ->
                when (which) {
                    0 -> this@BrowseFragment.launch { showSubscriptionDialog(doc) }
                    1 -> this@BrowseFragment.launch { unsubscribeFromGame(doc) }
                }
            }
            setNegativeButton(R.string.dialog_cancel) { _, _ -> /* NO-OP */ }
            show()
        }
    }

    private suspend fun getSubscribedUploadsForGame(doc: Document): List<Installation> {
        val gameId = ItchWebsiteUtils.getGameId(doc) ?: return emptyList()
        val db = AppDatabase.getDatabase(requireContext())
        return db.installDao.getFinishedInstallationsAndSubscriptionsSync()
            .filter { it.status == Installation.STATUS_SUBSCRIPTION && it.gameId == gameId }
    }

    private suspend fun unsubscribeFromGame(doc: Document) {
        val subscriptions = getSubscribedUploadsForGame(doc)
        if (subscriptions.isEmpty())
            return
        val db = AppDatabase.getDatabase(requireContext())
        db.installDao.delete(subscriptions)
        Toast.makeText(requireContext(), R.string.popup_unsubscribed, Toast.LENGTH_LONG).show()
        updateUI()
    }

    private suspend fun showSubscriptionDialog(doc: Document) {
        try {
            val installations = if (ItchWebsiteUtils.hasGameDownloadLinks(doc)) {
                ItchWebsiteParser.getInstallations(doc)
            } else {
                val storeUrl = url ?: return
                val downloadUrl = ItchWebsiteParser.getOrFetchDownloadUrl(storeUrl, doc)?.url
                if (downloadUrl == null) {
                    Toast.makeText(context, R.string.popup_subscribe_game_not_owned, Toast.LENGTH_LONG)
                        .show()
                    return
                }
                ItchWebsiteParser.getInstallations(ItchWebsiteUtils.fetchAndParse(downloadUrl))
            }
            val db = AppDatabase.getDatabase(requireContext())
            val subscriptions = db.installDao.getFinishedInstallationsAndSubscriptionsSync()
            val availableSubscriptions =
                installations.filter { install ->
                    !subscriptions.any { subscription -> subscription.uploadId == install.uploadId }
                }
            if (availableSubscriptions.isEmpty()) {
                Toast.makeText(context, R.string.popup_subscribe_game_all_subscribed, Toast.LENGTH_LONG)
                    .show()
                return
            }
            val subscribeOptions = availableSubscriptions.map { install ->
                val platforms = install.platformsStrings
                if (platforms.isEmpty())
                    return@map install.uploadName
                else
                    return@map "(${platforms.joinToString()}) ${install.uploadName}"
            }.toTypedArray()
            Log.d(LOGGING_TAG, subscribeOptions.joinToString())
            AlertDialog.Builder(requireContext()).run {
                setTitle(R.string.dialog_subscribe_title)
                setMultiChoiceItems(subscribeOptions, null) { _, _, _ -> /* NO-OP */ }
                setPositiveButton(R.string.dialog_subscribe_yes) { dialog, _ ->
                    val checkedPositions = (dialog as AlertDialog).listView.checkedItemPositions
                    val selectedSubscriptions = availableSubscriptions
                        .filterIndexed { index, _ -> checkedPositions.get(index) }
                    this@BrowseFragment.launch {
                        for (subscription in selectedSubscriptions) {
                            db.installDao.insert(subscription.copy(
                                status = Installation.STATUS_SUBSCRIPTION
                            ))
                        }
                        updateUI()
                    }
                }
                setNegativeButton(R.string.dialog_cancel) { _, _ -> /* NO-OP */ }
                setCancelable(true)
                show()
            }
        } catch (e: Exception) {
            Log.e(LOGGING_TAG, "Could not open subscription dialog", e)
            Toast.makeText(context, R.string.popup_subscribe_error, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Adds basic app bar actions for navigating between fragments.
     * Should run on UI thread.
     *
     * @param appBar the application's top toolbar
     */
    private fun addDefaultAppBarActions(appBar: Toolbar) {
        appBar.menu.add(APP_BAR_ACTIONS_DEFAULT, 10, 10, R.string.nav_website_view).setOnMenuItemClickListener {
            loadUrl(ItchWebsiteUtils.getMainBrowsePage(requireContext()))
            true
        }
        appBar.menu.add(APP_BAR_ACTIONS_DEFAULT, 11, 11, R.string.nav_installed).setOnMenuItemClickListener {
            (activity as MainActivity).setActiveFragment(MainActivity.LIBRARY_FRAGMENT_TAG)
            true
        }
        appBar.menu.add(APP_BAR_ACTIONS_DEFAULT, 12, 12, R.string.nav_updates).setOnMenuItemClickListener {
            (activity as MainActivity).setActiveFragment(MainActivity.UPDATES_FRAGMENT_TAG)
            true
        }
        appBar.menu.add(APP_BAR_ACTIONS_DEFAULT, 13, 13, R.string.nav_settings).setOnMenuItemClickListener {
            (activity as MainActivity).setActiveFragment(MainActivity.SETTINGS_FRAGMENT_TAG)
            true
        }
        // Search by game name; also available from the speed dial, but many users never find it.
        // (mentioned in https://itch.io/t/6524479/searching-a-specific-game-in-mitch)
        if (PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean(PREF_SEARCH_ENABLED, true)) {
            appBar.menu.add(APP_BAR_ACTIONS_DEFAULT, 14, 14, R.string.browser_search).setOnMenuItemClickListener {
                showSearchDialog()
                true
            }
        }
        // Toggle desktop rendering of the current page.
        // (mentioned in https://itch.io/t/6622118/any-way-to-export-in-app-browser-data-or-put-in-app-browser-into-desktop-mode)
        appBar.menu.add(APP_BAR_ACTIONS_DEFAULT, 15, 15, R.string.menu_desktop_site).run {
            isCheckable = true
            isChecked = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean(PREF_DESKTOP_MODE, false)
            setOnMenuItemClickListener {
                toggleDesktopMode()
                true
            }
        }
    }

    private fun applyDesktopModeUserAgent() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        webView.settings.userAgentString = if (prefs.getBoolean(PREF_DESKTOP_MODE, false))
            DESKTOP_USER_AGENT
        else
            WebSettings.getDefaultUserAgent(requireContext())
    }

    private fun toggleDesktopMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val enabled = !prefs.getBoolean(PREF_DESKTOP_MODE, false)
        prefs.edit().putBoolean(PREF_DESKTOP_MODE, enabled).apply()
        webView.settings.userAgentString = if (enabled)
            DESKTOP_USER_AGENT
        else
            WebSettings.getDefaultUserAgent(requireContext())
        webView.reload()
        Utils.logDebug(LOGGING_TAG, "Desktop mode: $enabled")
    }

    private fun showSearchDialog() {
        val viewInflated: View = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_search, getView() as ViewGroup?, false)

        val input = viewInflated.findViewById<TextInputEditText>(R.id.input)

        val alertDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.browser_search)
            .setView(viewInflated)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                loadUrl(ItchWebsiteUtils.getSearchUrl(input.text.toString()))
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()

        // Show keyboard automatically
        input.post {
            input.isFocusableInTouchMode = true
            input.requestFocus()

            input.postDelayed({
                val inputMethodManager =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                inputMethodManager.showSoftInput(input,
                    InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                alertDialog.dismiss()
                loadUrl(ItchWebsiteUtils.getSearchUrl(input.text.toString()))
                return@setOnEditorActionListener true
            }
            false
        }
    }

    private fun setSiteNavbarVisibility(visible: Boolean, callback: (String) -> (Unit)) {
        val cssVisibility = if (visible) "visible" else "hidden"
        webView.post {
            webView.evaluateJavascript("""
                {
                    let navbar = document.getElementById("user_tools")
                    if (navbar)
                        navbar.style.visibility = "$cssVisibility"
                }
                """, callback
            )
        }
    }

    private fun filterExcludedGenres() {
        val excludeSet = genresExclusionFilter ?: return

        val englishContext = Utils.makeLocalizedContext(requireContext(), Locale.ENGLISH)

        val excludeString = excludeSet.joinToString(prefix = "[", postfix = "]") { genre ->
            val englishName = englishContext.getString(genre.nameResource)
            if (englishName.contains('"'))
                throw IllegalStateException("Bad English translation!")
            return@joinToString "\"$englishName\""
        }

        Log.d(LOGGING_TAG, "Exclusion filter array: $excludeString")

        webView.post {
            webView.evaluateJavascript("""
                {
                	const excludeFilter = $excludeString
                    const gameGrid = document.querySelector(".browse_game_grid")
                    if (!gameGrid)
                        return

                	for (const gameCell of gameGrid.getElementsByClassName("game_cell")) {
                		const genre = gameCell.querySelector(".game_genre")

                		if (genre && excludeFilter.includes(genre.textContent)) {
                			gameCell.setAttribute("style", "display: none")
                			gameCell.setAttribute("data-mitch-excluded-genre", "true")
                		} else if (gameCell.hasAttribute("data-mitch-excluded-genre")) {
                			gameCell.removeAttribute("style")
                			gameCell.removeAttribute("data-mitch-excluded-genre")
                		}
                    }
                }
                """.trimIndent(), null
            )
        }
    }

    private fun filterExcludedTags() {
        val excludeSet = tagsExclusionFilter ?: return
        if (excludeSet.isEmpty())
            return

        val excludeString = excludeSet.joinToString(prefix = "[", postfix = "]") { tag ->
            if (tag.contains('"'))
                throw IllegalStateException("Bad tag name!")
            "\"$tag\""
        }

        Log.d(LOGGING_TAG, "Tag exclusion filter array: $excludeString")

        webView.post {
            webView.evaluateJavascript("""
                {
                	const excludeFilter = $excludeString
                    const gameGrid = document.querySelector(".browse_game_grid")
                    if (!gameGrid)
                        return

                	for (const gameCell of gameGrid.getElementsByClassName("game_cell")) {
                		const tagLinks = gameCell.querySelectorAll(".game_tags a, a[data-tag]")
                        let excluded = false
                		for (const tagLink of tagLinks) {
                            if (excludeFilter.includes(tagLink.textContent.trim())) {
                                excluded = true
                                break
                            }
                        }

                		if (excluded) {
                			gameCell.setAttribute("style", "display: none")
                			gameCell.setAttribute("data-mitch-excluded-tag", "true")
                		} else if (gameCell.hasAttribute("data-mitch-excluded-tag")) {
                			gameCell.removeAttribute("style")
                			gameCell.removeAttribute("data-mitch-excluded-tag")
                		}
                    }
                }
                """.trimIndent(), null
            )
        }
    }

    @Keep // prevent this class from being removed by compiler optimizations
    private class MitchJavaScriptInterface(val fragment: BrowseFragment) {
        /**
         * Drops JavaScript interface calls that carry a stale nonce instead of crashing.
         *
         * A nonce mismatch used to throw a SecurityException on the main thread, which killed
         * the whole app. That is reachable in practice: after a WebView session restore (process
         * death / tab restore), the page's injected JS still carries the nonce it was loaded
         * with, while this fragment may hold a fresh one — so a legitimate callback from the
         * restored page (e.g. a download button click or a page-ready signal) crashed the app.
         * Stale calls are simply ignored; the security intent (not processing spoofed calls)
         * is unchanged.
         */
        private fun verifyNonce(nonce: String): Boolean {
            if (nonce == fragment.webViewJSNonce.toString())
                return true
            Log.w(LOGGING_TAG, "Ignoring JavaScript interface call with stale nonce " +
                "$nonce (expected ${fragment.webViewJSNonce})")
            return false
        }

        @JavascriptInterface
        fun onDownloadLinkClick(uploadId: String, nonce: String) {
            if (!verifyNonce(nonce)) return
            fragment.launch {
                fragment.browseHandler?.setClickedUploadId(uploadId.toInt())
            }
        }

        @JavascriptInterface
        fun onHtmlLoaded(html: String, url: String, userAgent: String, nonce: String) {
            if (!verifyNonce(nonce)) return
            Log.d(LOGGING_TAG, "loaded UA: $userAgent")
            if (fragment.activity !is MainActivity)
                return

            Log.d(LOGGING_TAG, "current info: ${fragment.currentInfo}")

            fragment.launch(Dispatchers.Default) {
                try {
                    val doc = Jsoup.parse(html)
                    val info = fragment.browseHandler?.onPageVisited(doc, url, userAgent)
                    fragment.currentDoc = doc
                    fragment.currentInfo = info
                    fragment.activity?.runOnUiThread {
                        fragment.updateUI()
                    }
                } catch (e: Exception) {
                    Log.e(LOGGING_TAG, "Failed to parse page or update UI", e)
                }
            }
        }

        @JavascriptInterface
        fun onResize() {
            fragment.activity?.runOnUiThread {
                fragment.updateUI()
            }
        }
    }

    /**
     * True when the app is actually rendering in dark mode, mirroring the preference logic in
     * [Mitch.setThemeFromPreferences]. Used to force itch.io's own dark theme ("Use a dark theme
     * where available") in the browse WebView even when not logged in.
     */
    private fun isAppDarkMode(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return when (prefs.getString("preference_theme", "site")) {
            "dark" -> true
            "light" -> false
            "system" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            else -> prefs.getString("current_site_theme", null) == "dark"
        }
    }

    /**
     * JS that pins the browse page's theme to the app's effective dark mode by toggling itch.io's
     * own `dark_theme` body class ("Use a dark theme where available"). Custom-themed pages
     * (game/profile/jam) keep their own colors when dark, mirroring
     * [ItchWebsiteUtils.shouldHandleDayNightThemes]. Applied on every page load and re-applied on
     * resume, so switching the app theme in settings is reflected without reloading the page.
     */
    private fun darkThemeInjectionJs(active: Boolean): String = """
        (function() {
            var body = document.body;
            if (!body)
                return;
            if ($active) {
                if (!document.getElementById("game_theme")
                    && !document.getElementById("user_theme")
                    && !document.querySelector("[data-page_name='view_jam']")) {
                    body.classList.add("dark_theme");
                }
            } else {
                body.classList.remove("dark_theme");
            }
        })();
    """

    inner class MitchBrowserWebViewClient(
        private val browseFragment: BrowseFragment
    ) : MitchWebViewClient() {
        val githubLoginPathRegex = Regex("""^/?(login|sessions)(/.*)?$""")

        override fun shouldOverrideUrlLoading(view: WebView, uri: Uri): Boolean {
            // Remember where the user is on the current page before it navigates away,
            // so a later back-navigation lands at the same scroll position.
            rememberCurrentScroll()
            if (uri.host == "github.com"
                && uri.path?.matches(githubLoginPathRegex) == true) {
                return false
            }

            return super.shouldOverrideUrlLoading(view, uri)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            // The back-on-game-page fallback navigated away from a game page; drop the
            // leftover history so the start page is the root and back exits normally.
            if (pendingHistoryClear) {
                pendingHistoryClear = false
                view.clearHistory()
            }
            // Restore the saved scroll position first, still under the pageLoading guard, so the
            // auto-hide ignores its programmatic scroll; only then re-enable scroll handling.
            restoreScrollIfNeeded(url)
            pageLoading = false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageLoading = true
            val prefs = PreferenceManager.getDefaultSharedPreferences(browseFragment.requireContext())
            val hiddenElements = if (prefs.getBoolean(PREF_DEBUG_WEB_GAMES_IN_BROWSE_TAB, false) && BuildConfig.DEBUG)
                ".purchase_banner, .header_buy_row, .buy_row, .donate_btn"
            else
                ".purchase_banner, .header_buy_row, .buy_row, .donate_btn, .embed_wrapper, .load_iframe_btn"
            val darkThemeActive = isAppDarkMode()
            view.evaluateJavascript("""
                document.addEventListener("DOMContentLoaded", (event) => {
                    ${darkThemeInjectionJs(darkThemeActive)}
                    // tell Android that the document is ready
                    mitchCustomJS.onHtmlLoaded("<html>" + document.getElementsByTagName("html")[0].innerHTML + "</html>",
                                               window.location.href, 
                                               window.navigator.userAgent,
                                               "${browseFragment.webViewJSNonce}");
                                           
                    // setup download buttons
                    let downloadButtons = document.getElementsByClassName("download_btn");
                    for (var downloadButton of downloadButtons) {
                        let uploadId = downloadButton.getAttribute("data-upload_id");
                        downloadButton.addEventListener("click", (event) => {
                            mitchCustomJS.onDownloadLinkClick(uploadId, "${browseFragment.webViewJSNonce}");
                        });
                    }
                    
                    // remove YouTube banner
                    let ytBanner = document.querySelector(".youtube_mobile_banner_widget");
                    if (ytBanner)
                        ytBanner.style.visibility = "hidden";
                        
                    // remove game purchase banners, we implement our own
                    let elements = document.querySelectorAll("$hiddenElements");
                    for (var element of elements)
                        element.style.display = "none";

                    // Make the screenshot gallery render as a clean full-width column instead of
                    // a clipped horizontal strip on narrow screens.
                    // https://todo.sr.ht/~gardenapple/mitch/77
                    let galleryStyle = document.createElement("style");
                    galleryStyle.textContent = ".responsive .view_game_page .screenshot_list{white-space:normal!important;overflow:visible!important;display:block!important;text-align:center!important;font-size:inherit!important;margin:0!important}.responsive .view_game_page .screenshot_list img{display:block!important;max-width:100%!important;height:auto!important;margin:0 auto 10px!important}";
                    document.head.appendChild(galleryStyle);
                        
                    // stop highlighting download links for non-Android OSs
                    const uploads = document.querySelectorAll(".uploads .upload")
                    for (const upload of uploads) {
                        if (upload.querySelector(".icon-android") != null)
                            continue
                        if (upload.querySelector(".icon-windows8, .icon-tux, .icon-apple") == null)
                            continue
                        const button = upload.querySelector(".download_btn")
                        if (!button)
                            continue
                        let buttonColor = getComputedStyle(button).getPropertyValue("--itchio_button_color")
                        if (!buttonColor)
                            buttonColor = '#FF2449'
                        button.setAttribute("style", "background-color: inherit; border-color: " 
                                + buttonColor + "; color: " + buttonColor + "; text-shadow: none;")
                    }
                });
                window.addEventListener("resize", (event) => {
                    mitchCustomJS.onResize();
                });
                """, null
            )

            val uri = url.toUri()
            if (uri.pathSegments.containsAll(listOf("games", "platform-android"))) {
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(browseFragment.requireContext())
                val androidOnlyFilter = sharedPrefs.getBoolean(PREF_WEB_ANDROID_FILTER, true)
                if (androidOnlyFilter) {
                    browseFragment.webView.evaluateJavascript("""
                        document.addEventListener("DOMContentLoaded", (event) => {
                            // Android-only filter
                            let elements = document.getElementsByClassName("game_cell");
                            for (var element of elements) {
                                if (element.getElementsByClassName("icon-android").length == 0) {
                                    element.style.display = "none";
                                }
                            }
                        });
                        """, null
                    )
                }
            }
            super.onPageStarted(view, url, favicon)
        }
    }

    inner class MitchBrowserWebChromeClient : MitchWebChromeClient(
        openDocumentLauncher,
        openMultipleDocumentsLauncher
    ) {
        private var customView: View? = null
        private var originalUiVisibility: Int = View.SYSTEM_UI_FLAG_VISIBLE
        var customViewCallback: CustomViewCallback? = null
            private set
        var isForcedFullscreen: Boolean = false
            private set

        override fun onShowCustomView(view: View, callback: CustomViewCallback) =
            this.setFullscreen(view, callback)

        private fun setFullscreen(view: View?, callback: CustomViewCallback) {
            if (view != null)
                webView.visibility = View.GONE

            (activity as? MainActivity)?.apply {
                binding.bottomView.visibility = View.GONE
                binding.speedDial.visibility = View.GONE
                binding.toolbar.visibility = View.GONE

                view?.let { binding.fragmentContainer.addView(it) }

                originalUiVisibility = binding.root.systemUiVisibility
                binding.root.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }

            (view ?: binding.webView).keepScreenOn = true

            if (view != null)
                customView = view
            else
                isForcedFullscreen = true
            customViewCallback = callback
        }

        override fun onHideCustomView() {
            webView.visibility = View.VISIBLE
            (activity as? MainActivity)?.apply {
                binding.bottomView.visibility = View.VISIBLE

                customView?.let { binding.fragmentContainer.removeView(it) }
                binding.root.systemUiVisibility = originalUiVisibility
            }

            customView = null
            isForcedFullscreen = false
            // Tell the WebView the custom view is gone, otherwise it keeps thinking the
            // page is still fullscreen (audio keeps playing, the app bar stays hidden).
            val callback = customViewCallback
            customViewCallback = null
            callback?.onCustomViewHidden()

            updateUI()
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (_binding == null)
                return

            val progressBar = binding.progressBar
            
            if (newProgress < 100 && progressBar.visibility == ProgressBar.GONE)
                progressBar.visibility = ProgressBar.VISIBLE

            progressBar.progress = newProgress

            if (newProgress == 100)
                progressBar.visibility = ProgressBar.GONE
        }

        fun onResume() {
            if (isWebFullscreen) {
                binding.root.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }

        override fun setFileChooserCallback(callback: ValueCallback<Array<Uri>>) {
            this@BrowseFragment.filePathCallback = callback
        }
    }
}