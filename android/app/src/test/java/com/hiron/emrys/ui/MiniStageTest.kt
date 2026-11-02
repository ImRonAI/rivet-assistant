package com.hiron.emrys.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.LiveCall
import com.hiron.emrys.core.LiveCallStatus
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.emrys
import com.hiron.emrys.testing.shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The short robot band shown off Home: its plain status, its tap, and how it fits short windows and large text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MiniStageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val online = EmrysState(connection = Connection.Online)

    private fun band(state: EmrysState = online, visible: MutableState<Boolean> = mutableStateOf(true), onOpen: () -> Unit = {}) {
        val runtime = compose.activity.emrys
        compose.setContent {
            EmrysTheme {
                Column(Modifier.fillMaxSize().background(LocalPalette.current.bg)) {
                    MiniStage(runtime, state, visible = visible.value, onOpen = onOpen)
                }
            }
        }
    }

    /** The band's height as drawn, clipped by its enter animation. */
    private fun shownHeight(): Float = compose.onNodeWithTag(MINI_STAGE_TAG).fetchSemanticsNode().boundsInRoot.height

    /** Shows the band on a paused clock and returns its drawn height in the first frame it appears. */
    private fun firstShownHeight(visible: MutableState<Boolean>): Float {
        compose.mainClock.autoAdvance = false
        band(visible = visible)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
        compose.runOnUiThread { visible.value = true }
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            if (compose.onAllNodesWithTag(MINI_STAGE_TAG).fetchSemanticsNodes().isNotEmpty()) return shownHeight()
        }
        error("the band never appeared")
    }

    private fun px(dp: Dp): Float = with(compose.density) { dp.toPx() }

    @Test
    fun statusCopy() {
        val call = LiveCall("c1", "Dana", "+18015550123", true, 0L, LiveCallStatus.Answering)
        val expected = mapOf(
            Activity.Idle to "Emrys is ready.",
            Activity.Listening to "Emrys is listening.",
            Activity.Thinking to "Emrys is thinking.",
            Activity.Searching to "Emrys is searching the web.",
            Activity.Working to "Emrys is working on it.",
            Activity.Texting to "Emrys is writing a text.",
            Activity.Emailing to "Emrys is writing an email.",
            Activity.Waiting to "Emrys needs your OK.",
            Activity.Done to "Emrys is done.",
            Activity.Failed to "Emrys didn't finish.",
        )
        assertEquals(Activity.entries.toSet(), expected.keys)
        expected.forEach { (activity, text) -> assertEquals(text, stageStatus(online.copy(activity = activity))) }
        assertEquals("Emrys is offline.", stageStatus(EmrysState(connection = Connection.Offline, activity = Activity.Working)))
        assertEquals("Emrys is connecting.", stageStatus(EmrysState(connection = Connection.Connecting, activity = Activity.Working)))
        assertEquals("Emrys is answering a call.", stageStatus(online.copy(activity = Activity.Working, liveCall = call)))
        assertEquals("Emrys is answering a call.", stageStatus(online.copy(liveCall = call.copy(status = LiveCallStatus.Transferring))))
        assertEquals("Emrys is working on it.", stageStatus(online.copy(activity = Activity.Working, liveCall = call.copy(status = LiveCallStatus.Ended))))
    }

    @Test
    fun tapOpensEmrys() {
        var opened = 0
        band(onOpen = { opened++ })
        compose.onNodeWithTag(MINI_STAGE_TAG).assertExists()
        val cover = compose.onNodeWithContentDescription("Emrys is ready.")
        assertEquals("Open Emrys", cover.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        cover.performClick()
        assertEquals(1, opened)
    }

    @Test
    fun hiddenWhenNotVisible() {
        band(visible = mutableStateOf(false))
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h600dp-xxhdpi")
    fun shortWindowsGetTheCompactBand() {
        band()
        compose.onNodeWithTag(MINI_STAGE_TAG).assertHeightIsEqualTo(72.dp + 16.dp)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-xxhdpi")
    fun veryShortWindowsHideTheBand() {
        band()
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun largeTextNarrowPhone() {
        band()
        compose.onNodeWithTag(MINI_STAGE_TAG).assertHeightIsEqualTo(96.dp + 16.dp)
        shot(compose, compose.activity, "mini-stage-360-font200")
    }

    @Test
    fun reducedMotionShowsTheBandAtOnce() {
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertEquals(px(96.dp + 16.dp), firstShownHeight(mutableStateOf(false)), 1f)
    }

    @Test
    fun withMotionTheBandGrowsIn() {
        val growing = firstShownHeight(mutableStateOf(false))
        assertTrue("the band is still growing: $growing px", growing < px(96.dp + 16.dp) - 1f)
        compose.mainClock.advanceTimeBy(Motion.BASE.toLong() + 100)
        assertEquals(px(96.dp + 16.dp), shownHeight(), 1f)
    }
}
