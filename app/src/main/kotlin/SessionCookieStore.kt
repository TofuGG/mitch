package garden.appl.mitch

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Persists cookies for the sites Mitchy needs to stay logged in to (itch.io and GitHub).
 *
 * WebView's CookieManager only writes cookies with an expiry date to disk; "session" cookies
 * (which have none) live in memory and are lost when the process dies, logging the user out
 * of itch.io on every reboot. This store snapshots those cookies into SharedPreferences and
 * re-injects them at app startup.
 */
object SessionCookieStore {
    private const val LOGGING_TAG = "SessionCookieStore"
    private const val PREF_KEY = "mitch.session_cookies"

    private val DOMAINS = listOf(
        "itch.io",
        "itch.zone",
        "github.com"
    )

    private fun cookieUrl(domain: String) = "https://$domain"

    /**
     * Snapshots the current cookies for the sites we care about.
     * Call whenever the user might have logged in or out (e.g. on pause).
     */
    fun capture(context: Context) {
        val manager = CookieManager.getInstance()
        val fresh = DOMAINS.mapNotNull { domain ->
            val cookies = manager.getCookie(cookieUrl(domain))
            if (cookies.isNullOrBlank()) null else domain to cookies
        }.toMap()
        if (fresh.isEmpty())
            return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getStringSet(PREF_KEY, emptySet()) ?: emptySet()

        // Keep values for domains that have no fresh cookies right now (the WebView hasn't
        // visited them yet this process), but replace anything we have a fresh snapshot for.
        val merged = stored.mapNotNull { entry ->
            val domain = entry.substringBefore('\n')
            if (fresh.containsKey(domain)) null else entry
        } + fresh.map { (domain, cookies) -> "$domain\n$cookies" }

        prefs.edit(true) {
            putStringSet(PREF_KEY, merged.toSet())
        }
        Log.d(LOGGING_TAG, "Captured session cookies for ${fresh.keys}")
    }

    /**
     * Re-injects the last known cookies into the WebView cookie store.
     * Call once at application startup.
     */
    fun restore(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getStringSet(PREF_KEY, emptySet()) ?: emptySet()
        if (stored.isEmpty())
            return

        val manager = CookieManager.getInstance()
        var restored = 0
        for (entry in stored) {
            val separator = entry.indexOf('\n')
            if (separator <= 0) continue
            val domain = entry.substring(0, separator)
            val cookies = entry.substring(separator + 1)
            for (cookie in cookies.split("; ")) {
                manager.setCookie(cookieUrl(domain), cookie)
                restored++
            }
        }
        manager.flush()
        Log.d(LOGGING_TAG, "Restored $restored cookies")
    }
}
