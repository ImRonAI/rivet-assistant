package com.hiron.rivet.testing

import com.hiron.rivet.core.Citation
import com.hiron.rivet.core.Distance
import com.hiron.rivet.core.Hours
import com.hiron.rivet.core.ImageItem
import com.hiron.rivet.core.LatLng
import com.hiron.rivet.core.PlaceItem
import com.hiron.rivet.core.SearchKind
import com.hiron.rivet.core.SearchResults
import com.hiron.rivet.core.VideoItem
import com.hiron.rivet.core.WebItem
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic search results in the brain's envelope (search.py), made up for tests; never live captures. */
object SearchFixtures {
    fun envelope(kind: String, items: JSONArray, answer: String? = null, center: JSONObject? = null, altered: String? = null): JSONObject =
        JSONObject().put("kind", kind).put("query", "trail running shoes").put("altered", altered ?: JSONObject.NULL)
            .put("items", items).put("answer", answer ?: JSONObject.NULL).put("center", center ?: JSONObject.NULL)

    val web: JSONObject get() = envelope(
        "web",
        JSONArray()
            .put(
                JSONObject().put("title", "The best trail running shoes").put("url", "https://example.com/best")
                    .put("description", "We ran 400 miles in 30 pairs.").put("site", "Example Gear")
                    .put("favicon", "https://example.com/favicon.ico").put("thumbnail", "https://example.com/t.jpg").put("age", "2 days ago")
                    .put("unknown_field", 7),
            )
            .put(JSONObject().put("title", "No URL, so it is dropped"))
            .put(JSONObject().put("title", "Second").put("url", "https://example.org/second").put("description", JSONObject.NULL)),
    )

    val news: JSONObject get() = envelope(
        "news",
        JSONArray().put(JSONObject().put("title", "Race results").put("url", "https://news.example.com/r").put("breaking", true).put("age", "1 hour ago")),
    )

    val images: JSONObject get() = envelope(
        "images",
        JSONArray().put(
            JSONObject().put("title", "Muddy shoes").put("url", "https://photos.example.com/page").put("image", "https://photos.example.com/full.jpg")
                .put("thumbnail", "https://imgs.example.com/thumb.jpg").put("width", 1200).put("height", 800).put("source", "photos.example.com"),
        ),
    )

    val videos: JSONObject get() = envelope(
        "videos",
        JSONArray()
            .put(
                JSONObject().put("title", "How to pick trail shoes").put("url", "https://www.youtube.com/watch?v=abc_DEF-123")
                    .put("thumbnail", "https://imgs.example.com/v.jpg").put("duration", "12:04").put("views", 1_250_000)
                    .put("creator", "Trail Lab").put("publisher", "YouTube").put("age", "3 weeks ago").put("youtube_id", "abc_DEF-123"),
            )
            .put(JSONObject().put("title", "Elsewhere").put("url", "https://vimeo.com/1").put("youtube_id", JSONObject.NULL)),
    )

    val places: JSONObject get() = envelope(
        "places",
        JSONArray().put(
            JSONObject().put("id", "p1").put("title", "Summit Coffee").put("url", "https://summit.example.com").put("lat", 30.27).put("lng", -97.74)
                .put("address", "100 Congress Ave, Austin, TX").put("phone", "+1 512-555-0100").put("rating", 4.6).put("rating_max", 5)
                .put("reviews", 212).put("price", "$$").put("categories", JSONArray().put("Coffee shop").put("Bakery"))
                .put("hours_today", JSONArray().put(JSONObject().put("opens", "07:00").put("closes", "19:00")))
                .put("timezone", "America/Chicago").put("distance", JSONObject().put("value", 0.4).put("units", "miles")),
        ),
        center = JSONObject().put("lat", 30.27).put("lng", -97.74).put("name", "Austin"),
    )

    val context: JSONObject get() = envelope(
        "context",
        JSONArray().put(
            JSONObject().put("title", "Trail shoe guide").put("url", "https://guide.example.com").put("site", "Guide")
                .put("snippets", JSONArray().put("Lugs of 4 to 6 mm grip mud.").put("Rock plates protect your feet.")),
        ),
    )

    val answers: JSONObject get() = envelope("answers", JSONArray(), answer = "Pick a shoe with **deep lugs** for mud.[1]").put(
        "citations",
        JSONArray()
            .put(JSONObject().put("number", 1).put("url", "https://gear.example.com/lugs").put("snippet", "Lugs of 5 mm or more shed mud."))
            .put(JSONObject().put("number", 2).put("url", "https://rei.example.com/advice").put("snippet", JSONObject.NULL))
            .put(JSONObject().put("number", 1).put("url", "https://gear.example.com/again"))
            .put(JSONObject().put("url", "https://no-number.example.com")),
    )

    // Typed results for the screen tests.
    val webResults = SearchResults(
        SearchKind.Web, "trail running shoes", null,
        listOf(
            WebItem("The 12 best trail running shoes of 2026, tested on rock, mud and snow", "https://gear.example.com/best", "We ran more than 400 miles in 30 pairs to find the grippiest, comfiest shoes for every kind of trail.", "Example Gear", null, "https://img/1", "2 days ago"),
            WebItem("How to choose trail running shoes", "https://rei.example.com/advice", "Fit, lugs, drop and rock plates explained.", "REI Co-op", null, null, "1 week ago"),
            WebItem("Trail shoes vs road shoes", "https://runner.example.com/vs", "When you need the extra grip.", "Runner's World", null, "https://img/3", null),
        ),
        null, null,
    )

    val newsResults = SearchResults(
        SearchKind.News, "ultramarathon", null,
        listOf(
            WebItem("Course record falls at the Wasatch 100", "https://news.example.com/wasatch", "A late surge on the final climb gave her the win by 11 minutes.", "Trail News", null, "https://img/n1", "1 hour ago", breaking = true),
            WebItem("New shoe rules for elite races", "https://news.example.com/rules", "Stack heights will be capped next season.", "Run Daily", null, null, "5 hours ago"),
        ),
        null, null,
    )

    val imageResults = SearchResults(
        SearchKind.Images, "muddy trail shoes", null,
        listOf(
            ImageItem("Muddy trail shoes after a race", "https://photos.example.com/1", "https://img/full1", "https://img/i1", 1200, 800, "photos.example.com"),
            ImageItem("Close-up of lugs", "https://photos.example.com/2", "https://img/full2", "https://img/i2", 800, 1000, "gear.example.com"),
            ImageItem("Shoes on a rock", "https://photos.example.com/3", "https://img/full3", "https://img/i3", null, null, "trail.example.com"),
        ),
        null, null,
    )

    val videoResults = SearchResults(
        SearchKind.Videos, "pick trail shoes", null,
        listOf(
            VideoItem("How to pick trail running shoes in 10 minutes", "https://www.youtube.com/watch?v=abc", null, "https://img/v1", "10:12", 1_250_000, "Trail Lab", "YouTube", "3 weeks ago", "abc"),
            VideoItem("Shoe review: the long run", "https://vimeo.com/1", null, null, "22:40", 4_100, "Run Club", "Vimeo", "1 year ago", null),
        ),
        null, null,
    )

    val placeResults = SearchResults(
        SearchKind.Places, "coffee", null,
        listOf(
            PlaceItem("p1", "Summit Coffee", "https://summit.example.com", LatLng(30.268, -97.743), "100 Congress Ave, Austin, TX 78701", "+15125550100", 4.6, 5.0, 212, "$$", listOf("Coffee shop", "Bakery"), null, listOf(Hours("07:00", "19:00")), null, Distance(0.4, "miles")),
            PlaceItem("p2", "Bean Theory", "https://bean.example.com", LatLng(30.262, -97.751), "512 W 2nd St, Austin, TX", "+15125550101", 4.3, 5.0, 98, "$", listOf("Cafe"), null, listOf(Hours("06:30", "15:00")), null, null),
            PlaceItem("p3", "Night Owl Espresso", "https://owl.example.com", LatLng(30.275, -97.738), "8 E 6th St, Austin, TX", null, null, null, null, null, emptyList(), null, emptyList(), null, null),
        ),
        null, LatLng(30.27, -97.74),
    )

    val answerResults = SearchResults(
        SearchKind.Answers, "how deep should trail shoe lugs be", null, emptyList(),
        "For mud, look for **deep lugs of 5 to 6 mm**.[1] For dry, rocky trails, 3 to 4 mm grips well and feels steadier.[2]\n- Wider spacing sheds mud\n- A rock plate helps on sharp ground",
        null,
        listOf(
            Citation(1, "https://www.gear.example.com/lugs", "Lugs of 5 mm or more shed mud and keep their grip."),
            Citation(2, "https://rei.example.com/advice", null),
        ),
    )
}
