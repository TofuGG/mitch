package garden.appl.mitch.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import garden.appl.mitch.ItchWebsiteUtils
import garden.appl.mitch.PREF_GAME_RESTORE_AUTOROTATE
import garden.appl.mitch.PREF_GAME_RESTORE_ROTATION
import tofu.gg.mitchy.R
import garden.appl.mitch.Utils
import garden.appl.mitch.database.AppDatabase
import tofu.gg.mitchy.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The [MainActivity] handles a lot of things, including day/night themes and languages
 */
class MainActivity : MitchActivity(), CoroutineScope by MainScope() {

    private lateinit var browseFragment: BrowseFragment
    private lateinit var currentFragmentTag: String

    private var bottomNavHidden = false

    // Uptime millis of the last auto-hide/show flip; used to debounce flips caused by
    // noisy scroll deltas (see BOTTOM_NAV_TOGGLE_COOLDOWN).
    private var lastNavToggleTime = 0L

    lateinit var binding: ActivityMainBinding
        private set


    companion object {
        const val LOGGING_TAG = "MainActivity"

        const val EXTRA_SHOULD_OPEN_LIBRARY = "SHOULD_OPEN_LIBRARY"
        
        private const val ACTIVE_FRAGMENT_KEY: String = "fragment"

        // Scroll distance (in px) that must be crossed in one direction before the bottom
        // navigation bar auto-hides/reappears; prevents jitter from tiny scroll deltas.
        private const val BOTTOM_NAV_AUTO_HIDE_THRESHOLD = 12

        // Slide duration for the auto-hiding navigation bar and the content that expands into
        // the space it vacates.
        private const val BOTTOM_NAV_ANIM_DURATION = 200L

        // Minimum time between auto-hide/show flips triggered by scrolling. A noisy scroll
        // delta right after a flip would otherwise cancel the running slide animation mid-flight.
        private const val BOTTOM_NAV_TOGGLE_COOLDOWN = 200L

        const val BROWSE_FRAGMENT_TAG: String = "browse"
        const val LIBRARY_FRAGMENT_TAG: String = "library"
        const val SETTINGS_FRAGMENT_TAG: String = "settings"
        const val UPDATES_FRAGMENT_TAG: String = "updates"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        //Initially set to SplashScreenTheme during loading, this sets the proper theme
        setTheme(R.style.AppTheme_NoActionBar)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Add app bar, hidden by default
        setSupportActionBar(binding.toolbar)

        // The content area fills the whole screen and the bottom bar (nav + game bar) overlays
        // it, so reserve space for it with a bottom margin. Keep that margin in sync whenever the
        // bar's height changes (e.g. the game bar appearing on a game page).
        binding.bottomView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateContentBottomInset()
        }

        // Handle back through the dispatcher instead of overriding onBackPressed(), so
        // the system predictive back animation works. The Browse tab intercepts back to
        // navigate its WebView history first; anything it doesn't handle (or a different
        // tab) falls through to the default back behavior (finish / fragment back stack).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // `browseFragment` is assigned after this callback is registered; guard
                // against a back press arriving on a slow first frame.
                val browseHandled = ::browseFragment.isInitialized
                    && browseFragment.isVisible
                    && !browseFragment.onBackPressed()
                if (browseHandled)
                    return
                // Delegate to the default back behavior (finish / fragment back stack).
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                // Re-enable so future back presses still route through the Browse tab;
                // the re-dispatch above either finished the activity or popped a
                // back-stack entry, and the callback must stay usable either way.
                isEnabled = true
            }
        })
        supportActionBar!!.hide()


        currentFragmentTag = savedInstanceState?.getString(ACTIVE_FRAGMENT_KEY) ?: BROWSE_FRAGMENT_TAG

        //Fragments aren't destroyed on configuration changes
        
        val tryBrowseFragment = supportFragmentManager.findFragmentByTag(BROWSE_FRAGMENT_TAG)
        if (tryBrowseFragment != null) {
            browseFragment = tryBrowseFragment as BrowseFragment
        } else {
            browseFragment = BrowseFragment()
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragmentContainer, browseFragment, BROWSE_FRAGMENT_TAG)
                if (currentFragmentTag != BROWSE_FRAGMENT_TAG)
                    hide(browseFragment)
                commit()
            }
        }

        if (currentFragmentTag == LIBRARY_FRAGMENT_TAG &&
                supportFragmentManager.findFragmentByTag(LIBRARY_FRAGMENT_TAG) == null) {
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragmentContainer, LibraryFragment(), LIBRARY_FRAGMENT_TAG)
                commit()
            }
        }

        if (currentFragmentTag == UPDATES_FRAGMENT_TAG && 
                supportFragmentManager.findFragmentByTag(UPDATES_FRAGMENT_TAG) == null) {
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragmentContainer, SettingsFragment(), SETTINGS_FRAGMENT_TAG)
                commit()
            }
        }

        if (currentFragmentTag == SETTINGS_FRAGMENT_TAG &&
                supportFragmentManager.findFragmentByTag(SETTINGS_FRAGMENT_TAG) == null) {
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragmentContainer, SettingsFragment(), SETTINGS_FRAGMENT_TAG)
                commit()
            }
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val newFragmentTag = if (browseFragment.isVisible)
                BROWSE_FRAGMENT_TAG
            else if (supportFragmentManager.findFragmentByTag(LIBRARY_FRAGMENT_TAG)?.isVisible == true)
                LIBRARY_FRAGMENT_TAG
            else if (supportFragmentManager.findFragmentByTag(UPDATES_FRAGMENT_TAG)?.isVisible == true)
                UPDATES_FRAGMENT_TAG
            else if (supportFragmentManager.findFragmentByTag(SETTINGS_FRAGMENT_TAG)?.isVisible == true)
                SETTINGS_FRAGMENT_TAG
            else {
                Log.w(LOGGING_TAG, "no visible fragment?")
                return@addOnBackStackChangedListener
            }
            onFragmentSet(newFragmentTag, true)
        }

        val navView = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        navView.setOnNavigationItemSelectedListener { item ->
            val fragmentChanged = setActiveFragment(getFragmentTag(item.itemId), false)

            if (!fragmentChanged && currentFragmentTag == BROWSE_FRAGMENT_TAG)
                browseFragment.loadUrl(ItchWebsiteUtils.getMainBrowsePage(this))

            return@setOnNavigationItemSelectedListener fragmentChanged
        }
    }

    override fun onStart() {
        super.onStart()

        if (intent.action == Intent.ACTION_VIEW &&
                intent.data?.let { ItchWebsiteUtils.isItchWebPage(it) } == true) {
            browseUrl(intent.data.toString())
        } else if (intent.getBooleanExtra(EXTRA_SHOULD_OPEN_LIBRARY, false)) {
            setActiveFragment(LIBRARY_FRAGMENT_TAG)
        } else {
            setActiveFragment(currentFragmentTag)
        }

        launch {
            // Force lazy-init database to fully initialize, in the background
            val db = AppDatabase.getDatabase(this@MainActivity)
            if (!db.isOpen)
                db.installDao.getInstallationByPackageName(packageName)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tapping a download progress notification lands here when the activity is already
        // running (e.g. the app was sent to the background during the download); open Library.
        if (intent.getBooleanExtra(EXTRA_SHOULD_OPEN_LIBRARY, false))
            setActiveFragment(LIBRARY_FRAGMENT_TAG)
    }

    override fun onResume() {
        super.onResume()
        // The game player persisted the user's pre-game rotation when it closed, because
        // some devices (MIUI) keep the rotation the game forced. Apply it here — the
        // player window alone can't change the display back. Only applied when auto-rotate
        // was off, so sensor users are never locked to one rotation.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rotation = prefs.getInt(PREF_GAME_RESTORE_ROTATION, -1)
        if (rotation >= 0) {
            val autoRotateWasOff = !prefs.getBoolean(PREF_GAME_RESTORE_AUTOROTATE, true)
            prefs.edit()
                .remove(PREF_GAME_RESTORE_ROTATION)
                .remove(PREF_GAME_RESTORE_AUTOROTATE)
                .apply()
            if (autoRotateWasOff) {
                requestedOrientation = when (rotation) {
                    Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ACTIVE_FRAGMENT_KEY, currentFragmentTag)
    }

    // Handle light/dark theme changes
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val backgroundColor = Utils.getColor(this, R.color.colorBackground)
        val backgroundMainColor = Utils.getColor(this, R.color.colorBackgroundMain)
        val foregroundColor = Utils.getColor(this, R.color.colorForeground)
        val accentColor = Utils.getColor(this, R.color.colorAccent)

        val itemColorStateList = Utils.colorStateListOf(
            intArrayOf(android.R.attr.state_selected) to accentColor,
            intArrayOf() to foregroundColor
        )

        binding.bottomNavigationView.apply {
            setBackgroundColor(backgroundColor)
            itemBackground = ColorDrawable(backgroundColor)
            itemIconTintList = itemColorStateList
            itemTextColor = itemColorStateList
        }
        binding.mainLayout.setBackgroundColor(backgroundMainColor)

        //Handle system bar color
        if (browseFragment.isVisible) {
            //BrowseFragment has special handling
            browseFragment.updateUI()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = backgroundColor

                val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightMode == Configuration.UI_MODE_NIGHT_NO) {
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
    }

    /**
     * @param newFragmentTag one of: [BROWSE_FRAGMENT_TAG], [LIBRARY_FRAGMENT_TAG] etc
     * @param resetNavBar forcibly change the highlighted option in the bottom navigation bar
     * @return true if the current fragment has changed
     */
    fun setActiveFragment(newFragmentTag: String, resetNavBar: Boolean = true): Boolean {
        Log.d(LOGGING_TAG, "current: $currentFragmentTag, new: $newFragmentTag")
        if (newFragmentTag == currentFragmentTag)
            return false

        supportFragmentManager.beginTransaction().apply {
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_CLOSE)
            if (currentFragmentTag == BROWSE_FRAGMENT_TAG)
                hide(browseFragment)
            else
                remove(supportFragmentManager.findFragmentByTag(currentFragmentTag)!!)

            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            if (newFragmentTag == BROWSE_FRAGMENT_TAG)
                show(browseFragment)
            else
                add(R.id.fragmentContainer, getFragmentClass(newFragmentTag), Bundle.EMPTY, newFragmentTag)

            addToBackStack(null)

            commit()
        }

        onFragmentSet(newFragmentTag, resetNavBar)

        return true
    }

    fun browseUrl(url: String) {
        setActiveFragment(BROWSE_FRAGMENT_TAG)
        browseFragment.loadUrl(url)
    }

    private fun onFragmentSet(newFragmentTag: String, resetNavBar: Boolean) {
        if (resetNavBar)
            navBarSelectItem(getItemId(newFragmentTag))


        if (currentFragmentTag == BROWSE_FRAGMENT_TAG && newFragmentTag != BROWSE_FRAGMENT_TAG)
            browseFragment.restoreDefaultUI()

        currentFragmentTag = newFragmentTag

        // A tab switch always brings the auto-hidden navigation bar back.
        resetBottomNav()

        if (newFragmentTag == BROWSE_FRAGMENT_TAG) {
            browseFragment.updateUI()
            binding.speedDial.show()
        } else {
            binding.speedDial.hide()
        }
    }

    /**
     * Scroll-aware auto-hide for the bottom navigation bar. Each tab's scrollable reports its
     * vertical scroll delta here (positive = scrolling down). The bar slides out while scrolling
     * down and slides back in while scrolling up. Never fights the Browse game-page logic, which
     * hides the bar entirely ([View.GONE]): no-op unless the bar is currently visible.
     */
    fun onContentScrolled(dy: Int) {
        val nav = binding.bottomNavigationView
        if (nav.visibility != View.VISIBLE)
            return
        // Debounce: ignore scroll deltas that arrive right after a flip so they can't
        // cancel the slide animation mid-flight (e.g. a fast fling overshooting).
        if (SystemClock.uptimeMillis() - lastNavToggleTime < BOTTOM_NAV_TOGGLE_COOLDOWN)
            return
        if (dy > BOTTOM_NAV_AUTO_HIDE_THRESHOLD)
            setBottomNavHidden(true)
        else if (dy < -BOTTOM_NAV_AUTO_HIDE_THRESHOLD)
            setBottomNavHidden(false)
    }

    /**
     * Makes sure the bottom navigation bar is back on screen, cancelling any pending hide
     * animation and restoring the content to sit above it. Called on tab switches and after any
     * Browse page change, so a stale hidden state can never leave the bar missing or the
     * game-install bar overlaying content.
     */
    fun resetBottomNav() {
        setBottomNavHidden(false)
    }

    private fun setBottomNavHidden(hidden: Boolean) {
        if (bottomNavHidden == hidden)
            return
        bottomNavHidden = hidden
        lastNavToggleTime = SystemClock.uptimeMillis()

        // Only the bar itself animates (a cheap translation, GPU-only). The content is already
        // full-height underneath it, so we just drop the reserved bottom margin; no layout
        // animation on the content, which would be laggy with a WebView.
        val nav = binding.bottomNavigationView
        nav.animate().cancel()
        nav.animate()
            .translationY(if (hidden) nav.height.toFloat() else 0f)
            .setDuration(BOTTOM_NAV_ANIM_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()

        updateContentBottomInset()
    }

    /**
     * Reserves the bottom bar's height (nav + game bar) below the content by setting a bottom
     * margin on the content area and the speed dial FAB. The content always fills the whole
     * screen and the bar overlays it, so when the nav auto-hides the margin drops to 0 and the
     * already-present content fills the vacated space — no blank strip.
     */
    private fun updateContentBottomInset() {
        val inset = if (bottomNavHidden) 0 else binding.bottomView.height
        val containerParams = binding.fragmentContainer.layoutParams as ConstraintLayout.LayoutParams
        val fabParams = binding.speedDial.layoutParams as ConstraintLayout.LayoutParams
        if (containerParams.bottomMargin != inset) {
            containerParams.bottomMargin = inset
            binding.fragmentContainer.requestLayout()
        }
        if (fabParams.bottomMargin != inset) {
            fabParams.bottomMargin = inset
            binding.speedDial.requestLayout()
        }
    }

    private fun navBarSelectItem(itemId: Int) {
        binding.bottomNavigationView.post {
            val menu = binding.bottomNavigationView.menu
            
            for (index in 0 until menu.size()) {
                val item = binding.bottomNavigationView.menu.getItem(index)
                if (item.itemId == itemId) {
                    item.isChecked = true
                    break
                }
            }
        }
    }

    private fun getItemId(tag: String): Int {
        return when (tag) {
            BROWSE_FRAGMENT_TAG -> R.id.navigation_website_view
            LIBRARY_FRAGMENT_TAG -> R.id.navigation_library
            UPDATES_FRAGMENT_TAG -> R.id.navigation_updates
            SETTINGS_FRAGMENT_TAG -> R.id.navigation_settings
            else -> throw IllegalArgumentException()
        }
    }
    
    private fun getFragmentTag(itemId: Int): String {
        return when (itemId) {
            R.id.navigation_website_view -> BROWSE_FRAGMENT_TAG
            R.id.navigation_library -> LIBRARY_FRAGMENT_TAG
            R.id.navigation_updates -> UPDATES_FRAGMENT_TAG
            R.id.navigation_settings -> SETTINGS_FRAGMENT_TAG
            else -> throw IllegalArgumentException()
        }
    }
    
    private fun getFragmentClass(tag: String): Class<out Fragment> {
        return when (tag) {
            BROWSE_FRAGMENT_TAG -> BrowseFragment::class.java
            LIBRARY_FRAGMENT_TAG -> LibraryFragment::class.java
            UPDATES_FRAGMENT_TAG -> UpdatesFragment::class.java
            SETTINGS_FRAGMENT_TAG -> SettingsFragment::class.java
            else -> throw IllegalArgumentException()
        }
    }


    override fun makeIntentForRestart(): Intent {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(browseFragment.url),
            applicationContext,
            MainActivity::class.java
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * Redirect for <application> "manageSpaceActivity" attribute
     */
    class LibraryActivity : AppCompatActivity() {
        override fun onStart() {
            super.onStart()
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra(EXTRA_SHOULD_OPEN_LIBRARY, true)
            startActivity(intent)
            finish()
        }
    }
}
