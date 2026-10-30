package com.hiron.rivet.voice

import com.hiron.rivet.core.Freshness
import com.hiron.rivet.core.ImageItem
import com.hiron.rivet.core.PlaceItem
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.core.RivetStore
import com.hiron.rivet.core.SearchKind
import com.hiron.rivet.core.SearchQuery
import com.hiron.rivet.core.SearchResults
import com.hiron.rivet.core.VideoItem
import com.hiron.rivet.core.WebItem
import io.elevenlabs.ClientTool
import io.elevenlabs.ClientToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The agents' seven search tools as client tools: each runs through the brain's search proxy, shows its results on
 * screen, and tells the agent what is shown.
 */
object SearchTools {
    val NAMES = mapOf(
        "web_search" to SearchKind.Web, "news_search" to SearchKind.News, "image_search" to SearchKind.Images,
        "video_search" to SearchKind.Videos, "place_search" to SearchKind.Places, "answers" to SearchKind.Answers,
        "llm_context" to SearchKind.Context,
    )

    /** The tool definitions say "1-20, default 10". */
    const val DEFAULT_COUNT = 10
    const val MAX_COUNT = 20

    /**
     * How long a search tool waits for the brain before answering [TOO_LONG]: inside the 20 seconds the agents give
     * a tool ([com.hiron.rivet.core.SearchClient.SEARCH_TIMEOUT_S] is shorter still, so the client's own error usually comes first).
     */
    const val BUDGET_MS = 17_000L

    fun build(
        search: suspend (SearchQuery) -> Result<SearchResults>,
        update: ((RivetState) -> RivetState) -> Unit,
        budgetMs: Long = BUDGET_MS,
    ): Map<String, ClientTool> = NAMES.mapValues { (_, kind) ->
        object : ClientTool {
            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult {
                val query = query(kind, parameters) ?: return ClientToolResult.failure("Say what to search for.")
                val id = "agent-search:" + UUID.randomUUID().toString().take(12)
                update { RivetStore.searchStarted(it, id, kind) }
                val result = try {
                    withTimeoutOrNull(budgetMs) { search(query) } ?: Result.failure(IllegalStateException(TOO_LONG))
                } catch (e: CancellationException) {
                    update { RivetStore.searchFailed(it, id, null) }
                    throw e
                }
                return result.fold(
                    onSuccess = { results ->
                        update { RivetStore.searchShown(it, id, results) }
                        ClientToolResult.success(SearchSummary.forAgent(results))
                    },
                    onFailure = { error ->
                        val said = error.message ?: TOO_LONG
                        update { RivetStore.searchFailed(it, id, "Search didn't work: $said") }
                        ClientToolResult.failure(said)
                    },
                )
            }
        }
    }

    private const val TOO_LONG = "Search took too long. Try again."

    /**
     * The agent's parameters (Gson numbers and strings) as a query; null when there is nothing to search for. A
     * freshness other than Brave's four codes is ignored. For [SearchKind.Context] the count is the number of pages.
     */
    internal fun query(kind: SearchKind, parameters: Map<String, Any>): SearchQuery? {
        fun text(key: String) = (parameters[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun number(key: String) = (parameters[key] as? Number)?.toInt()
        val q = text("q")
        val location = if (kind == SearchKind.Places) text("location") else null
        if (q == null && location == null) return null
        return SearchQuery(
            kind = kind,
            q = q.orEmpty(),
            count = if (kind in COUNTED) (number("count") ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT) else null,
            freshness = if (kind in FRESH) Freshness.of(text("freshness")) else null,
            location = location,
            maxTokens = if (kind == SearchKind.Context) number("maximum_number_of_tokens") else null,
        )
    }

    private val COUNTED = setOf(SearchKind.Web, SearchKind.News, SearchKind.Images, SearchKind.Videos, SearchKind.Places, SearchKind.Context)
    private val FRESH = setOf(SearchKind.Web, SearchKind.News, SearchKind.Videos, SearchKind.Context)
}

/** What a search tool tells the agent about the cards on screen: numbered like the cards, at most [LIMIT] characters. */
object SearchSummary {
    const val LIMIT = 8_000
    private const val NOTE = "The user sees these as numbered cards. Refer to them by number or name; don't read links aloud."

    fun forAgent(results: SearchResults): String {
        if (results.kind == SearchKind.Answers) {
            val citations = results.citations.map { JSONObject().put("n", it.number).put("url", it.url).maybe("snippet", it.snippet?.take(200)) }
            return fit(citations) {
                JSONObject().put("shown_on_screen", true).put("answer", results.answer.orEmpty().take(4_000)).put("citations", it)
                    .put("note", "The answer is on screen with its numbered sources; give the gist in a sentence or two.")
            }
        }
        // Each page's text gets a share of the cap, so more pages means shorter extracts rather than fewer pages.
        val text = (6_000 / results.items.size.coerceAtLeast(1)).coerceIn(300, 1_500)
        val items = results.items.mapIndexed { i, item ->
            val o = JSONObject().put("n", i + 1)
            when (item) {
                is WebItem -> if (results.kind == SearchKind.Context) {
                    o.put("title", item.title).put("url", item.url).maybe("text", item.snippets.joinToString("\n").take(text).ifBlank { null })
                } else {
                    o.put("title", item.title).maybe("site", item.site).maybe("age", item.age).put("url", item.url)
                        .maybe("snippet", item.description?.take(300))
                }
                is ImageItem -> o.put("title", item.title).maybe("source", item.source).put("page_url", item.url)
                    .put("image_url", item.image).put("thumbnail", item.thumbnail).maybe("width", item.width).maybe("height", item.height)
                is VideoItem -> o.put("title", item.title).maybe("creator", item.creator).maybe("duration", item.duration)
                    .maybe("views", item.views).maybe("age", item.age).put("url", item.url).put("plays_in_app", item.youtubeId != null)
                is PlaceItem -> o.put("name", item.title).maybe("address", item.address).maybe("lat", item.at?.lat).maybe("long", item.at?.lng)
                    .maybe("phone", item.phone).maybe("rating", item.rating).maybe("rating_max", item.ratingMax)
                    .maybe("reviews", item.reviews).maybe("price", item.price)
                    .maybe("hours_today", item.hoursToday.joinToString(", ") { "${it.opens}–${it.closes}" }.ifBlank { null })
                    .maybe("distance", item.distance?.let { "${it.value} ${it.units}".trim() })
            }
        }
        return fit(items) {
            JSONObject().put("shown_on_screen", true).put("note", NOTE).put("query", results.query)
                .put("corrected_query", results.altered ?: JSONObject.NULL).put("results", it)
        }
    }

    /** [build] with as many of [items], from the first, as fit in [LIMIT]. */
    private fun fit(items: List<JSONObject>, build: (JSONArray) -> JSONObject): String {
        var kept = items.size
        while (true) {
            val text = build(JSONArray(items.take(kept))).toString()
            if (text.length <= LIMIT || kept == 0) return text
            kept--
        }
    }

    private fun JSONObject.maybe(key: String, value: Any?): JSONObject = if (value == null) this else put(key, value)
}
