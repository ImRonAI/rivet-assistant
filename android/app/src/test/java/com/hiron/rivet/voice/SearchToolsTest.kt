package com.hiron.rivet.voice

import com.hiron.rivet.core.Citation
import com.hiron.rivet.core.Freshness
import com.hiron.rivet.core.SearchClient
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.core.SearchError
import com.hiron.rivet.core.SearchKind
import com.hiron.rivet.core.SearchQuery
import com.hiron.rivet.core.SearchResults
import com.hiron.rivet.core.WebItem
import com.hiron.rivet.testing.SearchFixtures
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private suspend fun io.elevenlabs.ClientTool.run(parameters: Map<String, Any>) = checkNotNull(execute(parameters))

class SearchToolsTest {
    private var state = RivetState()
    private val asked = mutableListOf<SearchQuery>()

    private fun tools(answer: suspend (SearchQuery) -> Result<SearchResults>, taint: AgentTaint = AgentTaint(), budgetMs: Long = 2_000) =
        SearchTools.build({ asked += it; answer(it) }, { change -> state = change(state) }, taint, budgetMs)

    @Test
    fun theSevenNamesSitNextToThePhoneToolsWithoutClashing() {
        assertEquals(setOf("web_search", "news_search", "image_search", "video_search", "place_search", "answers", "llm_context"), SearchTools.NAMES.keys)
        assertTrue(SearchTools.NAMES.keys.intersect(AgentTools.NAMES.toSet()).isEmpty())
        assertEquals(25, (SearchTools.NAMES.keys + AgentTools.NAMES).size)
    }

    @Test
    fun parametersMapToTheQueryWithDefaults() {
        assertEquals(SearchQuery(SearchKind.Web, "shoes", count = 10), SearchTools.query(SearchKind.Web, mapOf("q" to "shoes")))
        assertEquals(
            SearchQuery(SearchKind.News, "race", count = 12, freshness = Freshness.Day),
            SearchTools.query(SearchKind.News, mapOf("q" to "race", "count" to 12.0, "freshness" to "pd")),
        )
        assertEquals(SearchQuery(SearchKind.Images, "lugs", count = 10), SearchTools.query(SearchKind.Images, mapOf("q" to "lugs", "freshness" to "pd")))
        // The agents send no location: q alone is a place search.
        assertEquals(SearchQuery(SearchKind.Places, "coffee near zilker park", count = 10), SearchTools.query(SearchKind.Places, mapOf("q" to "coffee near zilker park")))
        assertEquals(
            SearchQuery(SearchKind.Places, "coffee", count = 10, location = "austin tx united states"),
            SearchTools.query(SearchKind.Places, mapOf("q" to "coffee", "location" to "austin tx united states")),
        )
        assertEquals(SearchQuery(SearchKind.Answers, "why"), SearchTools.query(SearchKind.Answers, mapOf("q" to "why", "count" to 3.0)))
        assertEquals(
            SearchQuery(SearchKind.Context, "lugs", count = 10, maxTokens = 2048),
            SearchTools.query(SearchKind.Context, mapOf("q" to "lugs", "maximum_number_of_tokens" to 2048.0)),
        )
        assertEquals(SearchQuery(SearchKind.Context, "lugs", count = 4), SearchTools.query(SearchKind.Context, mapOf("q" to "lugs", "count" to 4.0)))
        assertNull(SearchTools.query(SearchKind.Web, mapOf("q" to "  ")))
    }

    @Test
    fun countsClampToOneThroughTwentyAndOnlyBraveFreshnessCodesPass() {
        assertEquals(20, SearchTools.query(SearchKind.Web, mapOf("q" to "x", "count" to 50.0))?.count)
        assertEquals(1, SearchTools.query(SearchKind.Videos, mapOf("q" to "x", "count" to 0.0))?.count)
        assertEquals(20, SearchTools.query(SearchKind.Context, mapOf("q" to "x", "count" to 99.0))?.count)
        val codes = mapOf("pd" to Freshness.Day, "pw" to Freshness.Week, "pm" to Freshness.Month, "py" to Freshness.Year)
        codes.forEach { (code, freshness) -> assertEquals(freshness, SearchTools.query(SearchKind.Videos, mapOf("q" to "x", "freshness" to code))?.freshness) }
        for (other in listOf("past week", "2026-01-01to2026-02-01", "PD", "")) {
            assertNull(SearchTools.query(SearchKind.News, mapOf("q" to "x", "freshness" to other))?.freshness)
        }
    }

    @Test
    fun theSearchPathAnswersBeforeTheAgentsTwentySecondCut() {
        assertTrue(SearchClient.SEARCH_TIMEOUT_S * 1_000 < SearchTools.BUDGET_MS)
        assertTrue(SearchTools.BUDGET_MS <= 20_000 - 2_000)
    }

    @Test
    fun aResultIsShownMarksTheTurnAndTellsTheAgentWhatIsOnScreen() = runBlocking {
        val taint = AgentTaint()
        val result = tools({ Result.success(SearchFixtures.webResults) }, taint).getValue("web_search").run(mapOf("q" to "trail running shoes"))
        assertTrue(result.success)
        assertTrue(taint.tainted)
        val shown = state.messages.single()
        assertEquals(SearchFixtures.webResults, shown.search)
        assertNull(shown.searching)
        val summary = JSONObject(result.result)
        assertTrue(summary.getBoolean("shown_on_screen"))
        val first = summary.getJSONArray("results").getJSONObject(0)
        assertEquals(1, first.getInt("n"))
        assertEquals("Example Gear", first.getString("site"))
        assertEquals(SearchKind.Web, asked.single().kind)
    }

    @Test
    fun aPlaceholderShowsWhileTheSearchRuns() = runBlocking {
        var seen: RivetState? = null
        tools({ seen = state; Result.success(SearchFixtures.placeResults) }).getValue("place_search").run(mapOf("q" to "coffee"))
        assertEquals(SearchKind.Places, seen?.messages?.single()?.searching)
    }

    @Test
    fun failuresArePlainAndNeverAsk() = runBlocking {
        val taint = AgentTaint()
        val busy = tools({ Result.failure(SearchError.Busy(3)) }, taint).getValue("news_search").run(mapOf("q" to "x"))
        assertFalse(busy.success)
        assertEquals("Search is busy. Try again in a moment.", busy.error)
        assertFalse(taint.tainted)
        assertEquals("Search didn't work: Search is busy. Try again in a moment.", state.messages.single().text)
        assertTrue(state.approvals.isEmpty())

        val slow = tools({ delay(5_000); Result.success(SearchFixtures.webResults) }, budgetMs = 100).getValue("web_search").run(mapOf("q" to "x"))
        assertEquals("Search took too long. Try again.", slow.error)

        val nothing = tools({ Result.success(SearchFixtures.webResults) }).getValue("web_search").run(emptyMap())
        assertFalse(nothing.success)
    }

    @Test
    fun summariesPerKind() {
        fun first(results: SearchResults) = JSONObject(SearchSummary.forAgent(results)).getJSONArray("results").getJSONObject(0)
        val image = first(SearchFixtures.imageResults)
        assertEquals("photos.example.com", image.getString("source"))
        assertEquals("https://photos.example.com/1", image.getString("page_url"))
        assertEquals("https://img/full1", image.getString("image_url"))
        assertEquals("https://img/i1", image.getString("thumbnail"))
        assertEquals(1200 to 800, image.getInt("width") to image.getInt("height"))
        val video = first(SearchFixtures.videoResults)
        assertTrue(video.getBoolean("plays_in_app"))
        assertEquals(1_250_000L, video.getLong("views"))
        assertEquals("Trail Lab", video.getString("creator"))
        assertEquals("10:12", video.getString("duration"))
        val place = first(SearchFixtures.placeResults)
        assertEquals("Summit Coffee", place.getString("name"))
        assertEquals("100 Congress Ave, Austin, TX 78701", place.getString("address"))
        assertEquals(30.268 to -97.743, place.getDouble("lat") to place.getDouble("long"))
        assertEquals(4.6 to 5.0, place.getDouble("rating") to place.getDouble("rating_max"))
        assertEquals("07:00–19:00", place.getString("hours_today"))
        assertEquals("0.4 miles", place.getString("distance"))
        val answer = JSONObject(SearchSummary.forAgent(SearchFixtures.answerResults))
        assertTrue(answer.getBoolean("shown_on_screen"))
        assertTrue(answer.getString("answer").startsWith("For mud"))
        val citations = answer.getJSONArray("citations")
        assertEquals(2, citations.length())
        assertEquals(1, citations.getJSONObject(0).getInt("n"))
        assertEquals("https://www.gear.example.com/lugs", citations.getJSONObject(0).getString("url"))
        assertTrue(citations.getJSONObject(0).has("snippet"))
        assertFalse(citations.getJSONObject(1).has("snippet"))
        val context = SearchResults(SearchKind.Context, "q", null, listOf(WebItem("T", "https://u", null, null, null, null, null, snippets = listOf("a", "b"))), null, null)
        assertEquals("a\nb", first(context).getString("text"))
    }

    @Test
    fun theSummaryStaysUnderItsCap() {
        val long = "x".repeat(1_400)
        val items = (1..20).map { WebItem("Title $it", "https://example.com/$it", null, null, null, null, null, snippets = listOf(long)) }
        // Twenty pages, the most the agents ask for, all fit with shorter extracts.
        val pages = SearchSummary.forAgent(SearchResults(SearchKind.Context, "q", null, items, null, null))
        assertTrue(pages.length <= SearchSummary.LIMIT)
        assertEquals(20, JSONObject(pages).getJSONArray("results").length())

        val wordy = (1..20).map { WebItem("Title $it " + "t".repeat(600), "https://example.com/$it", "d".repeat(300), null, null, null, null) }
        val text = SearchSummary.forAgent(SearchResults(SearchKind.Web, "q", null, wordy, null, null))
        assertTrue(text.length <= SearchSummary.LIMIT)
        val kept = JSONObject(text).getJSONArray("results")
        assertTrue(kept.length() in 1..19)
        assertEquals(1, kept.getJSONObject(0).getInt("n"))

        val sources = (1..30).map { Citation(it, "https://example.com/" + "p".repeat(300) + it, "s".repeat(300)) }
        val answer = SearchSummary.forAgent(SearchFixtures.answerResults.copy(answer = "a".repeat(5_000), citations = sources))
        assertTrue(answer.length <= SearchSummary.LIMIT)
        val cited = JSONObject(answer).getJSONArray("citations")
        assertTrue(cited.length() in 1..29)
        assertEquals(1, cited.getJSONObject(0).getInt("n"))
    }
}
