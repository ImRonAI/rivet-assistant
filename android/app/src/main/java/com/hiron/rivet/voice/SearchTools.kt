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
 * The agents' seven search tools as client tools (the definitions are in rivet-search-client-tools.json): each
 * runs through the brain's search proxy, shows its results on screen, and tells the agent what is shown. These are
 * reads, so nothing asks first; their web text marks the turn untrusted, like [AgentTools.UNTRUSTED].
 */
object SearchTools {
    val NAMES = mapOf(
        "web_search" to SearchKind.Web, "news_search" to SearchKind.News, "image_search" to SearchKind.Images,
        "video_search" to SearchKind.Videos, "place_search" to SearchKind.Places, "answers" to SearchKind.Answers,
        "llm_context" to SearchKind.Context,
    )

    /** The tool descriptions say "Default 5"; the brain clamps larger counts. */
    const val DEFAULT_COUNT = 5

    fun build(
        search: suspend (SearchQuery) -> Result<SearchResults>,
        update: ((RivetState) -> RivetState) -> Unit,
        taint: AgentTaint,
        budgetMs: Long = AgentTools.TOOL_BUDGET_MS,
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
                        taint.mark()
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

    /** The agent's parameters (Gson numbers and strings) as a query; null when there is nothing to search for. */
    internal fun query(kind: SearchKind, parameters: Map<String, Any>): SearchQuery? {
        fun text(key: String) = (parameters[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun number(key: String) = (parameters[key] as? Number)?.toInt()
        val q = text("q")
        val location = if (kind == SearchKind.Places) text("location") else null
        if (q == null && location == null) return null
        return SearchQuery(
            kind = kind,
            q = q.orEmpty(),
            count = if (kind in COUNTED) number("count") ?: DEFAULT_COUNT else null,
            freshness = if (kind in FRESH) Freshness.of(text("freshness")) else null,
            location = location,
            maxTokens = if (kind == SearchKind.Context) number("maximum_number_of_tokens") else null,
        )
    }

    private val COUNTED = setOf(SearchKind.Web, SearchKind.News, SearchKind.Images, SearchKind.Videos, SearchKind.Places)
    private val FRESH = setOf(SearchKind.Web, SearchKind.News, SearchKind.Videos, SearchKind.Context)
}

/** What a search tool tells the agent about the cards on screen: numbered like the cards, at most [LIMIT] characters. */
object SearchSummary {
    const val LIMIT = 8_000
    private const val NOTE = "The user sees these as numbered cards. Refer to them by number or name; don't read links aloud."

    fun forAgent(results: SearchResults): String {
        if (results.kind == SearchKind.Answers) {
            return JSONObject().put("shown_on_screen", true).put("answer", results.answer.orEmpty().take(4_000))
                .put("note", "The answer is on screen; give the gist in a sentence or two.").toString()
        }
        val items = results.items.mapIndexed { i, item ->
            val o = JSONObject().put("n", i + 1)
            when (item) {
                is WebItem -> if (results.kind == SearchKind.Context) {
                    o.put("title", item.title).put("url", item.url).maybe("text", item.snippets.joinToString("\n").take(1_500).ifBlank { null })
                } else {
                    o.put("title", item.title).maybe("site", item.site).maybe("age", item.age).put("url", item.url)
                        .maybe("snippet", item.description?.take(300))
                }
                is ImageItem -> o.put("title", item.title).maybe("source", item.source).put("page_url", item.url)
                is VideoItem -> o.put("title", item.title).maybe("creator", item.creator).maybe("duration", item.duration)
                    .maybe("views", item.views).maybe("age", item.age).put("url", item.url).put("plays_in_app", item.youtubeId != null)
                is PlaceItem -> o.put("name", item.title).maybe("address", item.address).maybe("phone", item.phone)
                    .maybe("rating", item.rating).maybe("reviews", item.reviews).maybe("price", item.price)
                    .maybe("hours_today", item.hoursToday.joinToString(", ") { "${it.opens}–${it.closes}" }.ifBlank { null })
                    .maybe("distance", item.distance?.let { "${it.value} ${it.units}".trim() })
            }
        }
        var kept = items.size
        while (true) {
            val text = JSONObject().put("shown_on_screen", true).put("note", NOTE).put("query", results.query)
                .put("corrected_query", results.altered ?: JSONObject.NULL).put("results", JSONArray(items.take(kept))).toString()
            if (text.length <= LIMIT || kept == 0) return text
            kept--
        }
    }

    private fun JSONObject.maybe(key: String, value: Any?): JSONObject = if (value == null) this else put(key, value)
}
