package com.hiron.ren.browser

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A favorite link, as ren desktop's favorites store keeps one (`src/stores/favoritesStore.ts`). */
data class Favorite(val id: String, val title: String, val url: String)

/**
 * The favorite links under the home page's search box and on Discover: one list, kept by the app, which ren desktop's
 * own favorites start. Adding one works the way ren desktop's store adds one: the address made an http(s) URL, the host
 * as the title when none is given, and a link already there is not added twice.
 */
class Favorites(read: () -> String?, private val write: (String) -> Unit) {
    private val state = MutableStateFlow(read()?.let { parse(it) } ?: DEFAULTS)
    val list: StateFlow<List<Favorite>> = state.asStateFlow()

    /** The favorite saved for this address, or null when it isn't an http(s) address. */
    fun add(title: String, address: String): Favorite? {
        val url = normalize(address) ?: return null
        state.value.firstOrNull { it.url == url }?.let { return it }
        val favorite = Favorite("fav-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(5)}", title.trim().ifEmpty { Uri.parse(url).host ?: url }, url)
        replace(state.value + favorite)
        return favorite
    }

    fun remove(id: String) = replace(state.value.filterNot { it.id == id })

    /** The list as a page left it (Discover's favorites are these). */
    fun replace(list: List<Favorite>) {
        state.value = list
        write(json(list))
    }

    companion object {
        /** ren desktop's `DEFAULT_FAVORITES`. */
        val DEFAULTS = listOf(
            Favorite("github", "GitHub", "https://github.com"),
            Favorite("hn", "Hacker News", "https://news.ycombinator.com"),
            Favorite("youtube", "YouTube", "https://www.youtube.com"),
            Favorite("wikipedia", "Wikipedia", "https://en.wikipedia.org"),
            Favorite("x", "X", "https://x.com"),
            Favorite("reddit", "Reddit", "https://www.reddit.com"),
        )

        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

        /** ren desktop's `normalizeFavoriteUrl`: `https://` when no scheme is given, and only http(s) addresses. */
        fun normalize(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val uri = Uri.parse(if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed")
            // A host with spaces in it isn't one (`new URL` refuses it).
            if (uri.scheme != "http" && uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.host.orEmpty().any(Char::isWhitespace)) return null
            // `new URL(…).href` gives a bare origin its slash.
            return if (uri.path.isNullOrEmpty()) uri.buildUpon().path("/").build().toString() else uri.toString()
        }

        /** ren desktop's `faviconFor`: the site's icon from Google's favicon service. */
        fun icon(url: String): String? = Uri.parse(url).host?.takeIf(String::isNotEmpty)?.let { "https://www.google.com/s2/favicons?domain=$it&sz=64" }

        fun json(list: List<Favorite>): String = JSONArray(list.map { JSONObject().put("id", it.id).put("title", it.title).put("url", it.url) }).toString()

        /** The favorites in a JSON array; null when it isn't one. Entries without an id, title and URL are left out. */
        fun parse(json: String): List<Favorite>? = runCatching { JSONArray(json) }.getOrNull()?.let { parse(it) }

        fun parse(array: JSONArray): List<Favorite> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { item ->
            val id = item.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val url = item.optString("url").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            Favorite(id, item.optString("title").ifEmpty { url }, url)
        }
    }
}
