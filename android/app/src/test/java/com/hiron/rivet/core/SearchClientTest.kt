package com.hiron.rivet.core

import com.hiron.rivet.testing.SearchFixtures
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SearchClientTest {
    private val server = MockWebServer()
    private val client = SearchClient(OkHttpClient()) { GatewayConfig(server.url("/").toString(), "device-token", "s1") }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(body: JSONObject, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body.toString()))

    private fun search(kind: SearchKind, q: String = "trail running shoes", count: Int? = null) =
        runBlocking { client.search(SearchQuery(kind, q, count = count)) }

    @Test
    fun sendsTheDeviceTokenAndTheQuery() {
        reply(SearchFixtures.web)
        runBlocking { client.search(SearchQuery(SearchKind.Web, " trail shoes ", count = 5, freshness = Freshness.Week)) }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer device-token", request.getHeader("Authorization"))
        assertEquals("/v1/search/web", request.requestUrl!!.encodedPath)
        assertEquals("trail shoes", request.requestUrl!!.queryParameter("q"))
        assertEquals("5", request.requestUrl!!.queryParameter("count"))
        assertEquals("pw", request.requestUrl!!.queryParameter("freshness"))
    }

    @Test
    fun parsesWebDroppingItemsWithoutAUrl() {
        reply(SearchFixtures.web)
        val results = search(SearchKind.Web).getOrThrow()
        assertEquals(SearchKind.Web, results.kind)
        assertEquals(2, results.items.size)
        val first = results.items[0] as WebItem
        assertEquals("Example Gear", first.site)
        assertEquals("2 days ago", first.age)
        assertNull((results.items[1] as WebItem).description)
    }

    @Test
    fun parsesEveryKind() {
        reply(SearchFixtures.news)
        assertTrue((search(SearchKind.News).getOrThrow().items.single() as WebItem).breaking)

        reply(SearchFixtures.images)
        val image = search(SearchKind.Images).getOrThrow().items.single() as ImageItem
        assertEquals("https://photos.example.com/full.jpg", image.image)
        assertEquals(1200 to 800, image.width to image.height)

        reply(SearchFixtures.videos)
        val videos = search(SearchKind.Videos).getOrThrow().items.map { it as VideoItem }
        assertEquals("abc_DEF-123", videos[0].youtubeId)
        assertEquals(1_250_000L, videos[0].views)
        assertNull(videos[1].youtubeId)

        reply(SearchFixtures.places)
        val places = search(SearchKind.Places).getOrThrow()
        val place = places.items.single() as PlaceItem
        assertEquals(LatLng(30.27, -97.74), place.at)
        assertEquals(listOf("Coffee shop", "Bakery"), place.categories)
        assertEquals(listOf(Hours("07:00", "19:00")), place.hoursToday)
        assertEquals(Distance(0.4, "miles"), place.distance)
        assertEquals(LatLng(30.27, -97.74), places.center)

        reply(SearchFixtures.context)
        assertEquals(2, (search(SearchKind.Context).getOrThrow().items.single() as WebItem).snippets.size)

        reply(SearchFixtures.answers)
        val answer = search(SearchKind.Answers).getOrThrow()
        assertTrue(answer.items.isEmpty())
        assertEquals("Pick a shoe with **deep lugs** for mud.[1]", answer.answer)
        // A repeated number keeps the first; one without a number is dropped.
        assertEquals(
            listOf(Citation(1, "https://gear.example.com/lugs", "Lugs of 5 mm or more shed mud."), Citation(2, "https://rei.example.com/advice", null)),
            answer.citations,
        )
        reply(SearchFixtures.web)
        assertTrue(search(SearchKind.Web).getOrThrow().citations.isEmpty())
    }

    @Test
    fun errorsAreTypedWithPlainWords() {
        reply(JSONObject().put("error", "unauthorized"), 401)
        assertEquals("Rivet's brain didn't accept this phone's device token. Check it in Settings.", search(SearchKind.Web).exceptionOrNull()?.message)

        reply(JSONObject().put("error", "Search is busy. Try again in a moment.").put("retry_after", 1), 429)
        val busy = search(SearchKind.Web).exceptionOrNull() as SearchError.Busy
        assertEquals(1, busy.retryAfter)

        reply(JSONObject().put("error", "Search is busy. Try again in a moment.").put("retry_after", JSONObject.NULL), 429)
        assertNull((search(SearchKind.Web).exceptionOrNull() as SearchError.Busy).retryAfter)

        reply(JSONObject().put("error", "Search isn't set up on Rivet's brain yet."), 503)
        assertEquals(SearchError.NotSetUp, search(SearchKind.Web).exceptionOrNull())

        reply(JSONObject().put("error", "Brave Search didn't answer properly.").put("upstream", 422), 502)
        assertEquals("Brave Search didn't answer properly.", search(SearchKind.Web).exceptionOrNull()?.message)

        reply(JSONObject().put("error", "Brave Search took too long."), 504)
        assertEquals("Brave Search took too long.", search(SearchKind.Web).exceptionOrNull()?.message)

        reply(JSONObject().put("error", "Type something to search for."), 400)
        assertEquals("Type something to search for.", search(SearchKind.Web).exceptionOrNull()?.message)
    }

    @Test
    fun aSilentBrainTimesOutAndADeadOneIsOffline() {
        val quick = SearchClient(OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()) {
            GatewayConfig(server.url("/").toString(), "t", "s1")
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slow = runBlocking { quick.search(SearchQuery(SearchKind.Web, "x")) }.exceptionOrNull()
        assertEquals("Search took too long. Try again.", slow?.message)

        server.shutdown()
        assertEquals(SearchError.Offline, search(SearchKind.Web).exceptionOrNull())
    }

    @Test
    fun withoutSettingsNothingIsSent() {
        val unset = SearchClient(OkHttpClient()) { GatewayConfig("", "", "") }
        assertEquals(SearchClient.NOT_CONFIGURED, runBlocking { unset.search(SearchQuery(SearchKind.Web, "x")) }.exceptionOrNull()?.message)
        assertEquals(emptyList<String>(), runBlocking { unset.suggest("tra") })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun suggestAndSpellcheck() {
        reply(JSONObject().put("query", "tra").put("suggestions", JSONArray().put("trail shoes").put("trail shoes").put("trader joes")))
        assertEquals(listOf("trail shoes", "trader joes"), runBlocking { client.suggest("tra") })
        assertEquals("/v1/suggest", server.takeRequest().requestUrl!!.encodedPath)

        reply(JSONObject().put("query", "trial shoes").put("corrected", "trail shoes"))
        assertEquals("trail shoes", runBlocking { client.spellcheck("trial shoes") })
        reply(JSONObject().put("query", "trail").put("corrected", JSONObject.NULL))
        assertNull(runBlocking { client.spellcheck("trail") })

        reply(JSONObject().put("error", "busy"), 429)
        assertEquals(emptyList<String>(), runBlocking { client.suggest("tra") })
        reply(JSONObject().put("error", "nope"), 503)
        assertNull(runBlocking { client.spellcheck("trial") })
        assertFalse(server.requestCount == 0)
    }
}
