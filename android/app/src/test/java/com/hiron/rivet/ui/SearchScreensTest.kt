package com.hiron.rivet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.BrainModel
import com.hiron.rivet.core.ChatMessage
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.ContextItem
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.core.SearchKind
import com.hiron.rivet.core.SearchQuery
import com.hiron.rivet.core.SearchResults
import com.hiron.rivet.core.Suggester
import com.hiron.rivet.core.rivet
import com.hiron.rivet.testing.SearchFixtures
import com.hiron.rivet.testing.shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pictures standing in for result images, one colour per URL. */
private object FakeImages : Images {
    override suspend fun bitmap(url: String, widthPx: Int) = picture(url).asImageBitmap()

    override suspend fun download(url: String, to: File) = false

    fun picture(url: String): Bitmap {
        val hue = (url.hashCode() and 0xFF) / 255f * 360f
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.55f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.6f, 0.25f))
        return Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 320f, 200f, a, b, Shader.TileMode.CLAMP) })
        }
    }
}

private object FakeSuggester : Suggester {
    override suspend fun suggest(q: String) = listOf("trail running shoes", "trail running shoes women", "trail running shoes waterproof", "trail running near me", "trail runner magazine")

    override suspend fun spellcheck(q: String) = if (q.startsWith("trial")) "trail running shoes" else null
}

/**
 * Search results in the conversation, the results page, the image viewer, the composer's suggestions, the widget's
 * search sheet and the new Settings plates, saved to build/screens/search-*.png. [SearchScreensNarrowTest] runs them
 * again on a 360dp phone at 130% text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
open class SearchScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected open val suffix = ""

    private val added = mutableListOf<ContextItem>()
    private val opened = mutableListOf<SearchKind>()

    private fun shot(name: String) = shot(compose, compose.activity, "search-$name$suffix")

    private fun page(content: @Composable () -> Unit) = compose.setContent {
        RivetTheme {
            val actions = ResultActions(add = { added += it }, seeAll = { _, kind -> opened += kind })
            CompositionLocalProvider(LocalImages provides FakeImages, LocalResultActions provides actions) {
                Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) { content() }
            }
        }
    }

    private fun conversation(results: SearchResults?, running: SearchKind? = null, reply: String? = null) = page {
        val messages = listOfNotNull(
            ChatMessage("t1:user", true, "Find me ${results?.query ?: "trail running shoes"}"),
            ChatMessage("s1", false, "", search = results, searching = running),
            reply?.let { ChatMessage("t1:rivet", false, it) },
        )
        Chat(messages, null, Modifier.fillMaxSize())
    }

    @Test
    fun webCarousel() {
        conversation(SearchFixtures.webResults, reply = "Number 1 is the most thorough test. Want the REI guide too?")
        compose.onNodeWithContentDescription("1. The 12 best trail running shoes of 2026, tested on rock, mud and snow, Example Gear").assertIsDisplayed()
        shot("web")
        compose.onAllNodesWithContentDescription("Share")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Add to Rivet", useUnmergedTree = true)[0].performClick()
        assertEquals("https://gear.example.com/best", added.single().url)
    }

    @Test
    fun newsWithBreaking() {
        conversation(SearchFixtures.newsResults)
        compose.onNodeWithText("BREAKING", useUnmergedTree = true).assertIsDisplayed()
        shot("news")
    }

    @Test
    fun imageRowAndViewer() {
        conversation(SearchFixtures.imageResults)
        shot("images")
        compose.onNodeWithContentDescription("1. Muddy trail shoes after a race, photos.example.com").performClick()
        compose.onNodeWithText("1. Muddy trail shoes after a race").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        shot("image-viewer")
        compose.onNodeWithText("Add to Rivet").performClick()
        assertEquals("https://img/full1", added.single().imageUrl)
    }

    @Test
    fun videoCardAndPlayer() {
        InlinePlayer.playing = null
        conversation(SearchFixtures.videoResults)
        compose.onNodeWithContentDescription("Open in YouTube").assertIsDisplayed()
        shot("videos")
        compose.onNodeWithContentDescription("1. How to pick trail running shoes in 10 minutes, Trail Lab, 10:12").performClick()
        assertEquals("s1/0", InlinePlayer.playing)
        shot("video-player")
        InlinePlayer.playing = null
    }

    @Test
    fun placesMapAndCards() {
        conversation(SearchFixtures.placeResults, reply = "Summit Coffee (1) is the closest and open until 7.")
        compose.onNodeWithContentDescription("1. Summit Coffee, 100 Congress Ave, Austin, TX 78701").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Call")[0].assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Directions")[0].assertIsDisplayed()
        shot("places")
        compose.onNodeWithContentDescription("Map of 3 places. Opens the results page.").performClick()
        assertEquals(SearchKind.Places, opened.single())
    }

    @Test
    fun answer() {
        conversation(SearchFixtures.answerResults)
        compose.onNodeWithText("ANSWER · BRAVE").assertIsDisplayed()
        shot("answer")
    }

    @Test
    fun placeholders() {
        conversation(null, running = SearchKind.Web)
        compose.onNodeWithText("SEARCHING WEB").assertIsDisplayed()
        shot("skeleton")
    }

    private fun results(request: ResultsRequest) = page {
        ResultsScreen(
            request,
            search = { q: SearchQuery ->
                Result.success(
                    when (q.kind) {
                        SearchKind.News -> SearchFixtures.newsResults
                        SearchKind.Images -> SearchFixtures.imageResults
                        SearchKind.Videos -> SearchFixtures.videoResults
                        SearchKind.Places -> SearchFixtures.placeResults
                        else -> SearchFixtures.webResults
                    }.copy(query = q.q),
                )
            },
            suggester = FakeSuggester, suggestions = true, onAsk = {}, onAdd = { added += it }, onClose = {},
        )
    }

    @Test
    fun resultsPageTabs() {
        results(ResultsRequest("trail running shoes", SearchKind.Web, SearchFixtures.webResults))
        shot("page-web")
        for (tab in listOf("News", "Images", "Videos", "Places")) {
            compose.onNodeWithText(tab).performScrollTo().performClick()
            compose.waitForIdle()
            shot("page-${tab.lowercase()}")
        }
        compose.onNodeWithText("Ask Rivet about “trail running shoes”").assertIsDisplayed()
    }

    @Test
    fun widgetSearchSheet() {
        results(ResultsRequest.Sheet)
        compose.onNodeWithTag(RESULTS_FIELD_TAG).performClick()
        compose.onNodeWithTag(RESULTS_FIELD_TAG).performTextInput("trial running sho")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("trail running shoes waterproof").assertIsDisplayed()
        compose.onNodeWithText("Did you mean trail running shoes?").assertIsDisplayed()
        shot("sheet")
        compose.onNodeWithText("trail running shoes waterproof").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ask Rivet about “trail running shoes waterproof”").assertIsDisplayed()
        shot("sheet-results")
    }

    @Test
    fun composerSuggestionsAndChips() {
        val runtime = compose.activity.rivet
        runtime.settings.searchSuggestions = true
        val state = RivetState(connection = Connection.Online)
        page { HomeScreen(runtime, state, onListen = {}, onReviewApprovals = {}, suggester = FakeSuggester) }
        compose.onNodeWithTag(COMPOSER_TAG).performTextInput("trial running")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag(SUGGESTIONS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Did you mean trail running shoes?").assertIsDisplayed()
        shot("composer")
    }

    @Test
    fun composerChips() {
        val runtime = compose.activity.rivet
        val state = RivetState(connection = Connection.Online)
        val chips = listOf(ContextItem.of(SearchKind.Videos, SearchFixtures.videoResults.items[0]), ContextItem.of(SearchKind.Web, SearchFixtures.webResults.items[1]))
        page { HomeScreen(runtime, state.copy(chips = chips), onListen = {}, onReviewApprovals = {}, suggester = null) }
        compose.onNodeWithContentDescription("Remove How to pick trail running shoes in 10 minutes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        shot("composer-chips")
    }

    @Test
    fun useTheBrainWhenTheAgentCantStart() {
        val runtime = compose.activity.rivet
        runtime.settings.elevenLabsAgents = true
        val state = RivetState(connection = Connection.Online, agentDown = true, lastError = "Couldn't start the ElevenLabs agent: ElevenLabs agents are not set up on the brain")
        page { HomeScreen(runtime, state, onListen = {}, onReviewApprovals = {}, suggester = null) }
        shot("agent-down")
        compose.onNodeWithText(USE_BRAIN).performClick()
        assertTrue(!runtime.settings.elevenLabsAgents)
    }

    @Test
    fun settingsPlates() {
        val online = RivetState(connection = Connection.Online, brainModel = BrainModel.Perplexity, availableModels = setOf(BrainModel.Perplexity, BrainModel.Groq))
        page {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                BrainModelPlate(online) { true }
                Box(Modifier.padding(top = 12.dp)) { BrainModelPlate(online.copy(connection = Connection.Offline)) { true } }
                Box(Modifier.padding(top = 12.dp)) {
                    BrainModelPlate(online.copy(availableModels = setOf(BrainModel.Perplexity), modelError = "Groq isn't set up on Rivet's brain.")) { true }
                }
            }
        }
        compose.onNodeWithText("Connect to change.").assertIsDisplayed()
        shot("settings-model")
    }

    @Test
    fun choosingAModelAsksTheBrainOnlyWhenItCan() {
        val chosen = mutableListOf<BrainModel>()
        val one = RivetState(connection = Connection.Online, brainModel = BrainModel.Perplexity, availableModels = setOf(BrainModel.Perplexity))
        var state by androidx.compose.runtime.mutableStateOf(one)
        page { BrainModelPlate(state) { chosen += it; true } }
        compose.onNodeWithText("Not set up on the brain").assertIsDisplayed()
        compose.onNodeWithText(BrainModel.Groq.label).performClick()
        assertTrue(chosen.isEmpty())
        state = one.copy(availableModels = setOf(BrainModel.Perplexity, BrainModel.Groq))
        compose.onNodeWithText(BrainModel.Groq.label).performClick()
        assertEquals(listOf(BrainModel.Groq), chosen)
    }

    @Test
    fun settingsBehaviour() {
        page { SettingsScreen(compose.activity.rivet, RivetState(connection = Connection.Online, brainModel = BrainModel.Groq, availableModels = setOf(BrainModel.Perplexity, BrainModel.Groq))) }
        compose.onNodeWithText("Suggest searches as you type").performScrollTo()
        compose.onNodeWithText("What you type is sent to Brave Search for suggestions. Brave keeps search logs for up to 90 days.").assertIsDisplayed()
        shot("settings-behaviour")
    }
}

@Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
class SearchScreensNarrowTest : SearchScreensTest() {
    override val suffix = "-narrow"
}
