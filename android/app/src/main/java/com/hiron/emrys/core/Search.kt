package com.hiron.emrys.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A kind of Brave search the agents' search tools run; [wire] is its name. */
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
    /** Set only for `https://www.youtube.com/watch?v=` links; those play inside Emrys. */
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

/** A source Brave's answer cites; the answer marks it as `[number]`. */
data class Citation(val number: Int, val url: String, val snippet: String?)

/** One search's results; [altered] is the query Brave actually ran when it corrected it. */
data class SearchResults(
    val kind: SearchKind,
    val query: String,
    val altered: String?,
    val items: List<SearchItem>,
    val answer: String?,
    val center: LatLng?,
    val citations: List<Citation> = emptyList(),
)

/** Why a search gave no results, in words for the user and the agent. */
sealed class SearchError(override val message: String) : Exception(message) {
    class Busy(val retryAfter: Int?) : SearchError("Search is busy. Try again in a moment.")
    data object NotSetUp : SearchError("Search isn't set up yet: add the Brave key in Settings.")
    data object Offline : SearchError("Emrys can't reach Brave Search right now. Check your connection.")
    class Failed(message: String) : SearchError(message)
}

/** What to search for; [count], [freshness], [location] and [maxTokens] fall back to Brave's defaults here. */
data class SearchQuery(
    val kind: SearchKind,
    val q: String,
    val count: Int? = null,
    val freshness: Freshness? = null,
    val location: String? = null,
    val maxTokens: Int? = null,
)

/**
 * Brave Search, called straight from the phone with the user's keys: the Search plan for web, news, images, videos,
 * places and page text, the Answers plan for `answers`. Nothing is kept: Brave prohibits retaining API results.
 *
 * Upstream facts come from Brave's API reference (https://api-dashboard.search.brave.com/llms.txt): the base URL and
 * `X-Subscription-Token` header, the parameters and their ranges, and the response fields.
 */
class BraveSearch(http: OkHttpClient, private val searchKey: () -> String, private val answersKey: () -> String) {
    private val searching = http.newBuilder().callTimeout(SEARCH_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val answering = http.newBuilder().callTimeout(ANSWERS_TIMEOUT_S, TimeUnit.SECONDS).build()

    suspend fun search(query: SearchQuery): Result<SearchResults> {
        if (query.q.trim().length > QUERY_LIMIT) return Result.failure(SearchError.Failed("Keep the search under $QUERY_LIMIT characters."))
        val answers = query.kind == SearchKind.Answers
        val key = (if (answers) answersKey() else searchKey()).trim()
        if (key.isEmpty()) return Result.failure(SearchError.NotSetUp)
        val request = if (answers) answersRequest(query.q.trim(), key) else searchRequest(query, key)
        return try {
            withContext(Dispatchers.IO) {
                (if (answers) answering else searching).newCall(request).await().use { response ->
                    when (response.code) {
                        200 -> {
                            val body = response.body ?: return@use Result.failure(SearchError.Failed(UNANSWERED))
                            if (answers) {
                                val (answer, citations) = BraveCodec.answer(BraveCodec.stream(body.source()::readUtf8Line))
                                Result.success(SearchResults(query.kind, query.q.trim(), null, emptyList(), answer, null, citations))
                            } else {
                                val data = runCatching { JSONObject(body.string()) }.getOrNull() ?: return@use Result.failure(SearchError.Failed(UNANSWERED))
                                Result.success(BraveCodec.results(query.kind, query.q.trim(), data))
                            }
                        }
                        429 -> Result.failure(SearchError.Busy(BraveCodec.retryAfter(response.header("X-RateLimit-Reset"))))
                        401, 403 -> Result.failure(SearchError.Failed("Brave didn't accept the search key. Check it in Settings."))
                        else -> Result.failure(SearchError.Failed(UNANSWERED))
                    }
                }
            }
        } catch (e: InterruptedIOException) {
            Result.failure(SearchError.Failed("Brave Search took too long."))
        } catch (e: JSONException) {
            Result.failure(SearchError.Failed(UNANSWERED))
        } catch (e: IOException) {
            Result.failure(SearchError.Offline)
        }
    }

    private fun searchRequest(query: SearchQuery, key: String): Request {
        val url = (BRAVE_API + PATHS.getValue(query.kind)).toHttpUrl().newBuilder()
        params(query).forEach { (name, value) -> url.addQueryParameter(name, value) }
        return Request.Builder().url(url.build()).header("X-Subscription-Token", key).header("Accept", "application/json").get().build()
    }

    /** Brave sends citations only on a stream. */
    private fun answersRequest(q: String, key: String): Request {
        val body = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", q)))
            .put("stream", true)
            .put("enable_citations", true)
        return Request.Builder().url(BRAVE_API + PATHS.getValue(SearchKind.Answers))
            .header("X-Subscription-Token", key).header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    companion object {
        const val BRAVE_API = "https://api.search.brave.com/res"

        /** Seconds for one whole Brave call; the ElevenLabs agents cut a client tool at 20 s. */
        const val SEARCH_TIMEOUT_S = 10L
        const val ANSWERS_TIMEOUT_S = 12L
        const val QUERY_LIMIT = 400

        private const val UNANSWERED = "Brave Search didn't answer properly."

        private val PATHS = mapOf(
            SearchKind.Web to "/v1/web/search",
            SearchKind.News to "/v1/news/search",
            SearchKind.Images to "/v1/images/search",
            SearchKind.Videos to "/v1/videos/search",
            SearchKind.Places to "/v1/local/place_search",
            SearchKind.Context to "/v1/llm/context",
            SearchKind.Answers to "/v1/chat/completions",
        )

        /** Default and maximum `count` per kind, within Brave's own ranges. For context it is the number of pages. */
        private val COUNTS = mapOf(
            SearchKind.Web to (8 to 20),
            SearchKind.News to (8 to 20),
            SearchKind.Images to (12 to 50),
            SearchKind.Videos to (8 to 20),
            SearchKind.Places to (8 to 20),
            SearchKind.Context to (5 to 20),
        )

        /** Default, minimum and maximum `maximum_number_of_tokens` for page text. */
        private const val CONTEXT_TOKENS = 2048
        private const val CONTEXT_TOKENS_MIN = 1024
        private const val CONTEXT_TOKENS_MAX = 8192

        /** Brave's query parameters for [query] (not answers, which is a POST). */
        internal fun params(query: SearchQuery): List<Pair<String, String>> {
            val kind = query.kind
            val q = query.q.trim()
            val out = mutableListOf<Pair<String, String>>()
            if (q.isNotEmpty()) out += "q" to q
            val count = COUNTS[kind]?.let { (default, ceiling) -> (query.count ?: default).coerceIn(1, ceiling) }
            if (count != null && kind != SearchKind.Context) out += "count" to count.toString()
            if (kind in FRESH) query.freshness?.let { out += "freshness" to it.wire }
            // With Brave's default, descriptions carry highlight markup.
            if (kind == SearchKind.Web) out += "text_decorations" to "false"
            if (kind == SearchKind.Places) {
                query.location?.replace(",", " ")?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ")
                    ?.takeIf { it.isNotEmpty() }?.let { out += "location" to it }
            }
            if (kind == SearchKind.Context) {
                out += "maximum_number_of_tokens" to (query.maxTokens ?: CONTEXT_TOKENS).coerceIn(CONTEXT_TOKENS_MIN, CONTEXT_TOKENS_MAX).toString()
                out += "maximum_number_of_urls" to (count ?: 5).toString()
                out += "enable_source_metadata" to "true"
            }
            return out
        }

        private val FRESH = setOf(SearchKind.Web, SearchKind.News, SearchKind.Videos, SearchKind.Context)
    }
}

/**
 * Reads Brave's responses. Every field is optional; an item without the fields a card needs (title and URL; a
 * thumbnail for images) is dropped, never the whole response. Unknown fields are ignored, as Brave's versioning
 * guide allows new ones at any time.
 */
internal object BraveCodec {
    private const val SNIPPETS = 5
    private const val SNIPPET_CHARS = 1000
    private const val CITATION_CHARS = 300
    private val YOUTUBE_WATCH = Regex("""^https://www\.youtube\.com/watch\?""")
    private val YOUTUBE_ID = Regex("""^[A-Za-z0-9_-]+$""")
    private val CITATION = Regex("""<citation>(.*?)</citation>""", RegexOption.DOT_MATCHES_ALL)
    private val USAGE = Regex("""<usage>.*?</usage>""", RegexOption.DOT_MATCHES_ALL)
    private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    fun results(kind: SearchKind, q: String, data: JSONObject): SearchResults {
        val query = obj(data.opt("query"))
        val original = text(query.opt("original")) ?: q
        val altered = text(query.opt("altered"))
        if (kind == SearchKind.Context) {
            val sources = obj(data.opt("sources"))
            val items = list(obj(data.opt("grounding")).opt("generic")).mapNotNull { context(obj(it), sources) }
            return SearchResults(kind, original, altered, items, null, null)
        }
        val listing = if (kind == SearchKind.Web) obj(data.opt("web")).opt("results") else data.opt("results")
        val items = list(listing).mapNotNull { raw ->
            val o = obj(raw)
            when (kind) {
                SearchKind.Web, SearchKind.News -> web(o)
                SearchKind.Images -> image(o)
                SearchKind.Videos -> video(o)
                SearchKind.Places -> place(o)
                SearchKind.Context, SearchKind.Answers -> null
            }
        }
        val center = if (kind == SearchKind.Places) latLng(obj(data.opt("location")).opt("coordinates")) else null
        return SearchResults(kind, original, altered, items, null, center)
    }

    /** The `delta.content` of each `data:` chunk of a server-sent event stream, up to `data: [DONE]`. */
    fun stream(line: () -> String?): List<String> {
        val parts = mutableListOf<String>()
        while (true) {
            val next = line() ?: break
            if (!next.startsWith("data:")) continue
            val payload = next.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            val choices = list(JSONObject(payload).opt("choices"))
            val content = if (choices.isEmpty()) null else obj(obj(choices[0]).opt("delta")).opt("content")
            if (content is String) parts += content
        }
        return parts
    }

    /** The streamed answer with each `<citation>` tag swapped for its `[number]` and `<usage>` dropped. */
    fun answer(parts: List<String>): Pair<String?, List<Citation>> {
        val citations = linkedMapOf<Int, Citation>()
        val swapped = CITATION.replace(USAGE.replace(parts.joinToString(""), "")) { match ->
            val raw = runCatching { JSONObject(match.groupValues[1]) }.getOrNull() ?: return@replace ""
            val number = count(raw.opt("number"))
            val url = text(raw.opt("url"))
            if (number == null || url == null) return@replace ""
            citations.getOrPut(number) { Citation(number, url, text(raw.opt("snippet"))?.take(CITATION_CHARS)) }
            "[$number]"
        }
        return text(swapped) to citations.values.sortedBy { it.number }
    }

    /** The first value of `X-RateLimit-Reset` (`"1, 224521"`: per-second window first). */
    fun retryAfter(header: String?): Int? = header.orEmpty().split(",")[0].trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    private fun web(raw: JSONObject): WebItem? {
        val title = text(raw.opt("title")) ?: return null
        val url = text(raw.opt("url")) ?: return null
        val meta = obj(raw.opt("meta_url"))
        return WebItem(
            title = title,
            url = url,
            description = text(raw.opt("description")),
            site = text(obj(raw.opt("profile")).opt("name")) ?: text(meta.opt("hostname")),
            favicon = text(meta.opt("favicon")),
            thumbnail = src(raw.opt("thumbnail")),
            age = text(raw.opt("age")),
            breaking = raw.opt("breaking") == true,
        )
    }

    private fun image(raw: JSONObject): ImageItem? {
        val title = text(raw.opt("title")) ?: return null
        val url = text(raw.opt("url")) ?: return null
        val thumbnail = src(raw.opt("thumbnail")) ?: return null
        val properties = obj(raw.opt("properties"))
        val full = text(properties.opt("url"))
        val (width, height) = size(if (full != null) properties else obj(raw.opt("thumbnail")))
        return ImageItem(title, url, full ?: thumbnail, thumbnail, width, height, text(raw.opt("source")))
    }

    /** Both dimensions of one image, or neither. */
    private fun size(raw: JSONObject): Pair<Int?, Int?> {
        val width = count(raw.opt("width"))
        val height = count(raw.opt("height"))
        return if (width != null && width != 0 && height != null && height != 0) width to height else null to null
    }

    private fun video(raw: JSONObject): VideoItem? {
        val title = text(raw.opt("title")) ?: return null
        val url = text(raw.opt("url")) ?: return null
        val video = obj(raw.opt("video"))
        return VideoItem(
            title = title,
            url = url,
            description = text(raw.opt("description")),
            thumbnail = src(raw.opt("thumbnail")),
            duration = text(video.opt("duration")),
            views = long(video.opt("views")),
            creator = text(video.opt("creator")),
            publisher = text(video.opt("publisher")),
            age = text(raw.opt("age")),
            youtubeId = youtubeId(url),
        )
    }

    private fun youtubeId(url: String): String? {
        if (!YOUTUBE_WATCH.containsMatchIn(url)) return null
        val ids = url.toHttpUrlOrNull()?.queryParameterValues("v").orEmpty()
        return ids.singleOrNull()?.takeIf { YOUTUBE_ID.matches(it) }
    }

    private fun place(raw: JSONObject): PlaceItem? {
        val title = text(raw.opt("title")) ?: return null
        val url = text(raw.opt("url")) ?: return null
        val where = latLng(raw.opt("coordinates")) ?: return null
        val rating = obj(raw.opt("rating"))
        val distance = obj(raw.opt("distance"))
        val categories = (list(raw.opt("categories")) + list(raw.opt("serves_cuisine"))).mapNotNull(::text).distinct()
        val hours = list(obj(raw.opt("opening_hours")).opt("current_day")).mapNotNull { period ->
            val opens = text(obj(period).opt("opens"))
            val closes = text(obj(period).opt("closes"))
            if (opens != null && closes != null) Hours(opens, closes) else null
        }
        val pictures = list(obj(raw.opt("pictures")).opt("results"))
        val value = number(distance.opt("value"))
        val units = text(distance.opt("units"))
        return PlaceItem(
            id = text(raw.opt("id")),
            title = title,
            url = url,
            at = where,
            address = text(obj(raw.opt("postal_address")).opt("displayAddress")),
            phone = text(obj(raw.opt("contact")).opt("telephone")),
            rating = number(rating.opt("ratingValue")),
            ratingMax = number(rating.opt("bestRating")),
            reviews = count(rating.opt("reviewCount")),
            price = text(raw.opt("price_range")),
            categories = categories,
            thumbnail = src(raw.opt("thumbnail")) ?: pictures.firstOrNull()?.let(::src),
            hoursToday = hours,
            timezone = text(raw.opt("timezone")),
            distance = if (value != null && units != null) Distance(value, units) else null,
        )
    }

    private fun context(raw: JSONObject, sources: JSONObject): WebItem? {
        val title = text(raw.opt("title")) ?: return null
        val url = text(raw.opt("url")) ?: return null
        val snippets = list(raw.opt("snippets")).mapNotNull(::text).map { it.take(SNIPPET_CHARS) }.take(SNIPPETS)
        val source = obj(sources.opt(url))
        // Brave's age is [full date, YYYY-MM-DD, relative, ISO-8601].
        val age = list(source.opt("age"))
        return WebItem(
            title = title,
            url = url,
            description = null,
            site = text(source.opt("site_name")) ?: text(source.opt("hostname")),
            favicon = text(source.opt("favicon")),
            thumbnail = src(source.opt("thumbnail")),
            age = if (age.size == 4) text(age[2]) else null,
            snippets = snippets,
        )
    }

    /** Brave gives coordinates as `[lat, long]`. */
    private fun latLng(value: Any?): LatLng? {
        val pair = list(value)
        if (pair.size != 2) return null
        val lat = number(pair[0]) ?: return null
        val lng = number(pair[1]) ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) LatLng(lat, lng) else null
    }

    private fun obj(value: Any?): JSONObject = value as? JSONObject ?: JSONObject()

    private fun list(value: Any?): List<Any?> = (value as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) } }.orEmpty()

    private fun text(value: Any?): String? = (value as? String)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    private fun src(value: Any?): String? = text(obj(value).opt("src"))

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** A whole number as Brave sends counts; a fraction is not a count. */
    private fun long(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is BigInteger -> value.toLong()
        else -> null
    }

    private fun count(value: Any?): Int? = long(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** HTML character references (`&amp;`, `&#39;`, `&#x27;`) as their characters, as Python's `html.unescape` does. */
    private fun unescape(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        val ref = m.groupValues[1]
        val code = when {
            ref.startsWith("#x") || ref.startsWith("#X") -> ref.drop(2).toIntOrNull(16)
            ref.startsWith("#") -> ref.drop(1).toIntOrNull()
            else -> null
        }
        (if (code != null) runCatching { String(Character.toChars(code)) }.getOrNull() else NAMED[ref]) ?: m.value
    }
}

/** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}
