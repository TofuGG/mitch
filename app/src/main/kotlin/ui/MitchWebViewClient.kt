package garden.appl.mitch.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import garden.appl.mitch.ItchWebsiteUtils
import garden.appl.mitch.R
import java.io.ByteArrayInputStream

open class MitchWebViewClient : WebViewClient() {
    companion object {
        private const val ERROR_PAGE_HTML = """<html>
            <head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { font-family: Roboto, sans-serif; background: #f7f7f7; color: #333;
                       margin: 0; height: 100vh; display: flex; align-items: center;
                       justify-content: center; text-align: center; }
                .box { max-width: 24em; padding: 2em; }
                h2 { font-size: 1.4em; margin: 0 0 .5em; }
                a { display: inline-block; margin-top: 1em; color: #fff; background: #fa5c5c;
                    border-radius: 4px; padding: .6em 1.6em; text-decoration: none; }
            </style></head>
            <body><div class="box"><h2>Can't load this page</h2>
            <p>There may be a problem with your connection or the host is unreachable.</p>
            <a href="#" onclick="location.reload(); return false;">Retry</a></div></body>
        </html>"""
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        // A failing subresource (game asset, iframe, ad, font...) is not a page failure.
        if (!request.isForMainFrame)
            return
        // DNS/network hiccups shouldn't nuke the current page with the system error page;
        // show a friendly retry page instead. (mentioned in https://todo.sr.ht/~gardenapple/mitch/50)
        when (error.errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT -> showErrorPage(view, request.url.toString())
            else -> super.onReceivedError(view, request, error)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT -> showErrorPage(view, failingUrl)
            else -> super.onReceivedError(view, errorCode, description, failingUrl)
        }
    }

    private fun showErrorPage(view: WebView, failingUrl: String) {
        // Loading the page with the failed URL as the base URL makes the in-page
        // "Retry" action reload the original URL instead of the error page itself.
        view.loadDataWithBaseURL(failingUrl, ERROR_PAGE_HTML, "text/html", "UTF-8", null)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return shouldOverrideUrlLoading(view, url.toUri())
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return shouldOverrideUrlLoading(view, request.url)
    }

    protected open fun shouldOverrideUrlLoading(view: WebView, uri: Uri): Boolean {
        if (ItchWebsiteUtils.isItchWebPageOrCDN(uri)) {
            return false
        } else {
            val context = view.context
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                Toast.makeText(
                    context,
                    context.resources.getString(R.string.popup_handler_app_not_found, uri),
                    Toast.LENGTH_LONG
                ).show()
            }
            return true
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(view.context)
        val blockTrackers = sharedPreferences.getBoolean("preference_block_trackers", true)

        if (blockTrackers) {
            arrayOf(
                "google-analytics.com",
                "adservice.google.com",
                "googlesyndication.com",
                "doubleclick.net",
                "crashlytics.com"
            ).forEach { trackerHostUrl ->
                if (request.url.host == trackerHostUrl ||
                    request.url.host?.endsWith('.' + trackerHostUrl) == true) {

                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream("tracker_blocked".toByteArray())
                    )
                }
            }
        }
        return null
    }
}