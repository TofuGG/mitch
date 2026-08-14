package garden.appl.mitch.client

import garden.appl.mitch.Mitch
import garden.appl.mitch.client.ItchLibraryParser.PAGE_SIZE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

object ItchLibraryParser {
    private const val LOGGING_TAG = "ItchLibraryParser"

    private val thumbnailCssPattern = Regex("""background-image:\s+url\('([^']*)'\)""")

    const val PAGE_SIZE = 50

    /**
     * @param androidOnly request only Android games from itch.io itself. Filtering server-side
     *  (via the same `platform=android` parameter itch.io's own "Only Android" checkbox uses)
     *  is what actually makes the filter work: the per-game platform icons that used to mark
     *  cells (`icon-android`) disappeared from itch.io's markup, so client-side filtering broke.
     *  (mentioned in https://itch.io/t/2192884/only-android-filter-not-working)
     * @return null if user is not logged in and has no access, otherwise a list of items (if size == [PAGE_SIZE], should request next page)
     */
    suspend fun parsePage(page: Int, androidOnly: Boolean): List<ItchLibraryItem>? {
        val result = withContext(Dispatchers.IO) {
            val request = Request.Builder().run {
                val url = if (androidOnly)
                    "https://itch.io/my-purchases?format=json&page=$page&platform=android"
                else
                    "https://itch.io/my-purchases?format=json&page=$page"
                url(url)
                get()
                build()
            }
            Mitch.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                    throw IOException("Unexpected code $response")

                if (response.isRedirect)
                    return@withContext null

                response.body.string()
            }
        } ?: return null

        val resultJson = try {
            JSONObject(result)
        } catch (_: JSONException) {
            //Invalid JSON == we got redirected to login page
            return null
        }

        val itemCount = resultJson.getInt("num_items")
        val items = ArrayList<ItchLibraryItem>(itemCount)

        val document = Jsoup.parse(resultJson.getString("content"))

        var lastPurchaseDate: String? = null
        for (gameDiv in document.getElementsByClass("game_cell")) {
            var purchaseDate: String? = gameDiv.selectFirst(".date_header")
                ?.getElementsByTag("span")?.text()

            if (purchaseDate.isNullOrEmpty())
                purchaseDate = lastPurchaseDate
            else
                lastPurchaseDate = purchaseDate

            val thumbnailLink = gameDiv.selectFirst(".thumb_link")
            val title = gameDiv.selectFirst(".game_title")?.text()
            val author = gameDiv.selectFirst(".game_author")?.text()

            // A game can be removed from itch.io or the page can be malformed mid-scrape;
            // never crash the whole list over a single broken cell, just skip it.
            // (mentioned in https://todo.sr.ht/~gardenapple/mitch/76)
            if (thumbnailLink == null || title.isNullOrEmpty() || author.isNullOrEmpty() || purchaseDate == null)
                continue

            val downloadUrl = thumbnailLink.attr("href")
            val thumbnailImg = thumbnailLink.selectFirst("img")
            var thumbnailUrl = thumbnailImg?.attr("data-lazy_src")
            if (thumbnailUrl.isNullOrEmpty())
                thumbnailUrl = thumbnailImg?.attr("href")
            val isAndroid = isAndroidCell(gameDiv)

            items.add(
                ItchLibraryItem(
                    purchaseDate = purchaseDate,
                    downloadUrl = downloadUrl,
                    thumbnailUrl = thumbnailUrl,
                    title = title,
                    author = author,
//                    description = description,
                    isAndroid = isAndroid
                )
            )
        }

        return items
    }

    /**
     * Best-effort detection of an Android game from a `game_cell`. itch.io removed the
     * `.icon-android` class that this used to rely on, so check a few remaining signals.
     * This is only used for the "Android" text label on the item and as a client-side
     * fallback — the actual "Only Android" filter is applied by itch.io server-side.
     */
    private fun isAndroidCell(gameDiv: org.jsoup.nodes.Element): Boolean {
        if (gameDiv.selectFirst(".icon-android") != null)
            return true
        val platforms = gameDiv.selectFirst(".game_platforms") ?: return false
        return platforms.select("i, span, a, .platform_android").any {
            it.hasClass("platform_android") ||
                it.className().contains("android", ignoreCase = true) ||
                it.attr("title").contains("android", ignoreCase = true) ||
                it.ownText().contains("android", ignoreCase = true)
        }
    }
}