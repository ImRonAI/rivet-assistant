package com.hiron.rivet.voice

import com.hiron.rivet.core.Freshness
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
        assertEquals(SearchQuery(SearchKind.Web, "shoes", count = 5), SearchTools.query(SearchKind.Web, mapOf("q" to "shoes")))
        assertEquals(
            SearchQuery(SearchKind.News, "race", count = 12, freshness = Freshness.Day),
            SearchTools.query(SearchKind.News, mapOf("q" to "race", "count" to 12.0, "freshness" to "pd")),
        )
        assertEquals(SearchQuery(SearchKind.Images, "lugs", count = 5), SearchTools.query(SearchKind.Images, mapOf("q" to "lugs", "freshness" to "pd")))
        assertEquals(
            SearchQuery(SearchKind.Places, "coffee", count = 5, location = "austin tx united states"),
            SearchTools.query(SearchKind.Places, mapOf("q" to "coffee", "location" to "austin tx united states")),
        )
        assertEquals(SearchQuery(SearchKind.Answers, "why"), SearchTools.query(SearchKind.Answers, mapOf("q" to "why", "count" to 3.0)))
        assertEquals(
            SearchQuery(SearchKind.Context, "lugs", maxTokens = 2048),
            SearchTools.query(SearchKind.Context, mapOf("q" to "lugs", "maximum_number_of_tokens" to 2048.0)),
        )
        assertNull(SearchTools.query(SearchKind.Web, mapOf("q" to "  ")))
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
        assertEquals("photos.example.com", first(SearchFixtures.imageResults).getString("source"))
        assertEquals("https://photos.example.com/1", first(SearchFixtures.imageResults).getString("page_url"))
        val video = first(SearchFixtures.videoResults)
        assertTrue(video.getBoolean("plays_in_app"))
        assertEquals(1_250_000L, video.getLong("views"))
        val place = first(SearchFixtures.placeResults)
        assertEquals("Summit Coffee", place.getString("name"))
        assertEquals("07:00–19:00", place.getString("hours_today"))
        assertEquals("0.4 miles", place.getString("distance"))
        val answer = JSONObject(SearchSummary.forAgent(SearchFixtures.answerResults))
        assertTrue(answer.getBoolean("shown_on_screen"))
        assertTrue(answer.getString("answer").startsWith("For mud"))
        val context = SearchResults(SearchKind.Context, "q", null, listOf(WebItem("T", "https://u", null, null, null, null, null, snippets = listOf("a", "b"))), null, null)
        assertEquals("a\nb", first(context).getString("text"))
    }

    @Test
    fun theSummaryStaysUnderItsCap() {
        val long = "x".repeat(1_400)
        val items = (1..20).map { WebItem("Title $it", "https://example.com/$it", null, null, null, null, null, snippets = listOf(long)) }
        val text = SearchSummary.forAgent(SearchResults(SearchKind.Context, "q", null, items, null, null))
        assertTrue(text.length <= SearchSummary.LIMIT)
        val kept = JSONObject(text).getJSONArray("results")
        assertTrue(kept.length() in 1..19)
        assertEquals(1, kept.getJSONObject(0).getInt("n"))
    }
}
