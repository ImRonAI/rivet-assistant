package com.hiron.rivet.core

import org.json.JSONArray
import org.json.JSONObject

/** A kind of search the brain's proxy runs (brain/src/rivet_brain/search.py `SearchKind`); [wire] is its route. */
enum class SearchKind(val wire: String, val label: String, val noun: String) {
    Web("web", "Web", "web"),
    News("news", "News", "news"),
    Images("images", "Images", "image"),
    Videos("videos", "Videos", "video"),
    Places("places", "Places", "place"),
    Context("context", "Pages", "web"),
    Answers("answers", "Answer", "answer"),
    ;

    companion object {
        fun of(wire: String?): SearchKind? = entries.firstOrNull { it.wire == wire }

        /** The brain's own search tools (search_tools.py), by name. */
        private val BRAIN_TOOLS = mapOf(
            "search_web" to Web, "search_news" to News, "search_images" to Images, "search_videos" to Videos,
            "search_places" to Places, "search_answer" to Answers, "search_context" to Context,
        )

        fun ofBrainTool(name: String): SearchKind? = BRAIN_TOOLS[name]
    }
}

/** Brave's recency filter. */
enum class Freshness(val wire: String) {
    Day("pd"), Week("pw"), Month("pm"), Year("py");

    companion object {
        fun of(wire: String?): Freshness? = entries.firstOrNull { it.wire == wire }
    }
}

data class LatLng(val lat: Double, val lng: Double)

/** One result; every kind has a title and the page it came from. */
sealed interface SearchItem {
    val title: String
    val url: String
}

/** A web page, a news article ([breaking]) or a page's extracted text ([snippets], from `context`). */
data class WebItem(
    override val title: String,
    override val url: String,
    val description: String?,
    val site: String?,
    val favicon: String?,
    val thumbnail: String?,
    val age: String?,
    val breaking: Boolean = false,
    val snippets: List<String> = emptyList(),
) : SearchItem

data class ImageItem(
    override val title: String,
    /** The page the image is on. */
    override val url: String,
    /** The full-size image. */
    val image: String,
    val thumbnail: String,
    val width: Int?,
    val height: Int?,
    val source: String?,
) : SearchItem

data class VideoItem(
    override val title: String,
    override val url: String,
    val description: String?,
    val thumbnail: String?,
    val duration: String?,
    val views: Long?,
    val creator: String?,
    val publisher: String?,
    val age: String?,
    /** Set only for `https://www.youtube.com/watch?v=` links; those play inside Rivet. */
    val youtubeId: String?,
) : SearchItem

data class Hours(val opens: String, val closes: String)

data class Distance(val value: Double, val units: String)

data class PlaceItem(
    val id: String?,
    override val title: String,
    override val url: String,
    /** Null when Brave gave no coordinates; such a place has no pin. */
    val at: LatLng?,
    val address: String?,
    val phone: String?,
    val rating: Double?,
    val ratingMax: Double?,
    val reviews: Int?,
    val price: String?,
    val categories: List<String>,
    val thumbnail: String?,
    val hoursToday: List<Hours>,
    val timezone: String?,
    val distance: Distance?,
) : SearchItem

/** One search as the brain's proxy returned it; [altered] is the query Brave actually ran when it corrected it. */
data class SearchResults(
    val kind: SearchKind,
    val query: String,
    val altered: String?,
    val items: List<SearchItem>,
    val answer: String?,
    val center: LatLng?,
)

/** Why a search gave no results, in words for the user. */
sealed class SearchError(override val message: String) : Exception(message) {
    class Busy(val retryAfter: Int?) : SearchError("Search is busy. Try again in a moment.")
    data object NotSetUp : SearchError("Search isn't set up on Rivet's brain yet.")
    data object Offline : SearchError("Rivet can't reach its brain right now. Check your connection.")
    class Failed(message: String) : SearchError(message)
}

/** What to search for; [count], [freshness], [location] and [maxTokens] fall back to the brain's defaults. */
data class SearchQuery(
    val kind: SearchKind,
    val q: String,
    val count: Int? = null,
    val freshness: Freshness? = null,
    val location: String? = null,
    val maxTokens: Int? = null,
)

/** A result the user added to the conversation ("Add to Rivet"). */
data class ContextItem(
    val kind: SearchKind,
    val title: String,
    val url: String,
    val description: String?,
    val watchUrl: String?,
    val imageUrl: String?,
) {
    companion object {
        const val DESCRIPTION_LIMIT = 500
        const val URL_LIMIT = 2048

        /** Null unless [item]'s link is one the brain accepts (search_tools.py `parse_attachments`: https only). */
        fun of(kind: SearchKind, item: SearchItem): ContextItem? {
            val url = accepted(item.url) ?: return null
            val description = when (item) {
                is WebItem -> item.description ?: item.snippets.firstOrNull()
                is ImageItem -> item.source
                is VideoItem -> item.description
                is PlaceItem -> item.address
            }
            return ContextItem(
                kind = kind,
                title = item.title,
                url = url,
                description = description?.take(DESCRIPTION_LIMIT),
                watchUrl = (item as? VideoItem)?.let { accepted(it.url) },
                imageUrl = (item as? ImageItem)?.let { accepted(it.image) ?: accepted(it.thumbnail) },
            )
        }

        private fun accepted(url: String): String? =
            url.takeIf { it.startsWith("https://") && it.length <= URL_LIMIT && it.none(Char::isWhitespace) }
    }
}

/** The models Rivet's own brain can run (brain/src/rivet_brain/config.py `model_provider`). */
enum class BrainModel(val wire: String, val label: String) {
    Perplexity("perplexity", "Perplexity"),
    Groq("groq", "Groq · Qwen 3.8 27B"),
    ;

    companion object {
        fun of(wire: String?): BrainModel? = entries.firstOrNull { it.wire == wire }
    }
}

/** Reads the brain's search envelope (search.py `SearchResults.to_json`); the only code that parses it. */
internal object SearchCodec {
    private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]+$""")

    fun results(o: JSONObject?): SearchResults? {
        if (o == null) return null
        val kind = SearchKind.of(o.optStringOrNull("kind")) ?: return null
        val list = o.optJSONArray("items")
        val items = if (list == null) emptyList() else (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { item(kind, it) } }
        return SearchResults(
            kind = kind,
            query = o.optStringOrNull("query").orEmpty(),
            altered = o.optStringOrNull("altered")?.takeIf { it.isNotBlank() },
            items = items,
            answer = o.optStringOrNull("answer")?.takeIf { it.isNotBlank() },
            center = o.optJSONObject("center")?.let(::latLng),
        )
    }

    fun suggestions(o: JSONObject): List<String> = o.optJSONArray("suggestions").strings().filter { it.isNotBlank() }.distinct()

    fun corrected(o: JSONObject): String? = o.optStringOrNull("corrected")?.takeIf { it.isNotBlank() }

    private fun item(kind: SearchKind, o: JSONObject): SearchItem? {
        val title = o.optStringOrNull("title")?.takeIf { it.isNotBlank() } ?: return null
        val url = o.optStringOrNull("url")?.takeIf { it.isNotBlank() } ?: return null
        return when (kind) {
            SearchKind.Web, SearchKind.News, SearchKind.Context -> WebItem(
                title, url, o.optStringOrNull("description"), o.optStringOrNull("site"), o.optStringOrNull("favicon"),
                o.optStringOrNull("thumbnail"), o.optStringOrNull("age"), o.optBoolean("breaking", false),
                o.optJSONArray("snippets").strings().filter { it.isNotBlank() },
            )
            SearchKind.Images -> {
                val thumbnail = o.optStringOrNull("thumbnail")
                val image = o.optStringOrNull("image") ?: thumbnail ?: return null
                ImageItem(title, url, image, thumbnail ?: image, o.optIntOrNull("width"), o.optIntOrNull("height"), o.optStringOrNull("source"))
            }
            SearchKind.Videos -> VideoItem(
                title, url, o.optStringOrNull("description"), o.optStringOrNull("thumbnail"), o.optStringOrNull("duration"),
                o.optLongOrNull("views"), o.optStringOrNull("creator"), o.optStringOrNull("publisher"), o.optStringOrNull("age"),
                o.optStringOrNull("youtube_id")?.takeIf { VIDEO_ID.matches(it) },
            )
            SearchKind.Places -> PlaceItem(
                id = o.optStringOrNull("id"),
                title = title,
                url = url,
                at = latLng(o),
                address = o.optStringOrNull("address"),
                phone = o.optStringOrNull("phone"),
                rating = o.optDoubleOrNull("rating"),
                ratingMax = o.optDoubleOrNull("rating_max"),
                reviews = o.optIntOrNull("reviews"),
                price = o.optStringOrNull("price"),
                categories = o.optJSONArray("categories").strings().filter { it.isNotBlank() },
                thumbnail = o.optStringOrNull("thumbnail"),
                hoursToday = hours(o.optJSONArray("hours_today")),
                timezone = o.optStringOrNull("timezone"),
                distance = o.optJSONObject("distance")?.let { d ->
                    d.optDoubleOrNull("value")?.let { v -> Distance(v, d.optStringOrNull("units").orEmpty()) }
                },
            )
            SearchKind.Answers -> null
        }
    }

    private fun latLng(o: JSONObject): LatLng? {
        val lat = o.optDoubleOrNull("lat") ?: return null
        val lng = o.optDoubleOrNull("lng") ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) LatLng(lat, lng) else null
    }

    private fun hours(list: JSONArray?): List<Hours> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val h = list.optJSONObject(i) ?: return@mapNotNull null
            val opens = h.optStringOrNull("opens") ?: return@mapNotNull null
            val closes = h.optStringOrNull("closes") ?: return@mapNotNull null
            Hours(opens, closes)
        }

    private fun JSONObject.optIntOrNull(key: String): Int? = optDoubleOrNull(key)?.toInt()

    private fun JSONObject.optLongOrNull(key: String): Long? = optDoubleOrNull(key)?.toLong()
}
