package com.hiron.emrys.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.core.EmrysState

const val MINI_STAGE_TAG = "mini-stage"

/**
 * Emrys in a short glass band at the top of a screen that isn't Home, so it stays in view. Tapping it runs [onOpen]
 * (back to Home). It steps aside while the keyboard is up and on short windows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MiniStage(
    runtime: EmrysRuntime,
    state: EmrysState,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onOpen: () -> Unit,
) {
    val p = LocalPalette.current
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val height = bandHeight(window)
    val shown = visible && !WindowInsets.isImeVisible && height != null
    val reduce = rememberReduceMotion()
    var last by remember { mutableStateOf(FULL_BAND) }
    if (height != null) SideEffect { last = height }
    AnimatedVisibility(
        shown,
        modifier,
        enter = if (reduce) EnterTransition.None else expandVertically(tween(Motion.BASE, easing = Motion.Emphasized)) + fadeIn(tween(Motion.BASE)),
        exit = if (reduce) ExitTransition.None else shrinkVertically(tween(Motion.QUICK)) + fadeOut(tween(Motion.QUICK)),
    ) {
        // The band keeps its last height while it shrinks away on a window that just got too short.
        val h = height ?: last
        Box(
            Modifier.testTag(MINI_STAGE_TAG).fillMaxWidth().background(p.bg)
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp).height(h),
        ) {
            StageFrame(Modifier.fillMaxSize(), framed = true, hint = null, radius = 22.dp) {
                // A leaving band drops its page at once.
                if (shown) RobotView(runtime, Modifier.fillMaxSize(), focus = RobotFocus.Card)
            }
            // The cover sits over the WebView, so a drag or pinch in the band never orbits the robot.
            val status = stageStatus(state)
            Box(
                Modifier.matchParentSize().clickable(role = Role.Button, onClickLabel = OPEN_LABEL, onClick = onOpen)
                    .semantics { contentDescription = status },
            )
        }
    }
}

private const val OPEN_LABEL = "Open Emrys"
private val FULL_BAND = 96.dp
private val COMPACT_BAND = 72.dp

/** The band's height for a window [window] tall, or null when the window is too short to give it room. */
private fun bandHeight(window: Dp): Dp? = when {
    window >= 640.dp -> FULL_BAND
    window >= 520.dp -> COMPACT_BAND
    else -> null
}

/** What Emrys is doing, in one plain sentence for the band's screen reader label. */
internal fun stageStatus(state: EmrysState): String = when (state.activity) {
    Activity.Idle -> "Emrys is ready."
    Activity.Listening -> "Emrys is listening."
    Activity.Thinking -> "Emrys is thinking."
    Activity.Searching -> "Emrys is searching the web."
    Activity.Working -> "Emrys is working on it."
    Activity.Texting -> "Emrys is writing a text."
    Activity.Emailing -> "Emrys is writing an email."
    Activity.Waiting -> "Emrys needs your OK."
    Activity.Done -> "Emrys is done."
    Activity.Failed -> "Emrys didn't finish."
}
