package com.hiron.rivet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.Activity
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetState

/**
 * The telemetry strip across the top of every screen: the RIVET mark, the unit line, and live readouts for
 * what Rivet is doing, the link to the brain, and how many actions wait on your OK.
 */
@Composable
fun HudHeader(state: RivetState, unit: String) {
    val p = LocalPalette.current
    val (mode, modeColor) = mode(state, p)
    val (link, linkColor) = when (state.connection) {
        Connection.Online -> "ONLINE" to p.ok
        Connection.Connecting -> "LINKING" to p.amb
        Connection.Offline -> "OFFLINE" to p.rec
    }
    val queue = state.approvals.size
    Column(Modifier.fillMaxWidth().background(p.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append("RIV")
                        withStyle(SpanStyle(color = p.accent)) { append("E") }
                        append("T")
                    },
                    style = Type.brand, color = p.ink,
                )
                Spacer(Modifier.size(4.dp))
                Text(unit.uppercase(), style = Type.labelSm, color = p.ink2, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Telemetry("Mode", mode, modeColor)
                Telemetry("Link", link, linkColor)
                Telemetry("Queue", "%02d".format(queue), if (queue > 0) p.amb else p.ink2)
            }
        }
        Rule()
    }
}

@Composable
private fun Telemetry(label: String, value: String, color: Color) {
    val p = LocalPalette.current
    Column(Modifier.widthIn(min = 52.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = Type.labelSm, color = p.ink2)
        Text(value, style = Type.tele, color = color, maxLines = 1)
    }
}

private fun mode(state: RivetState, p: Palette): Pair<String, Color> {
    if (state.connection != Connection.Online) return "STANDBY" to p.ink2
    return when (state.activity) {
        Activity.Idle -> "READY" to p.ink
        Activity.Listening -> "LISTEN" to p.rec
        Activity.Thinking -> "THINK" to p.cy
        Activity.Searching -> "SEARCH" to p.cy
        Activity.Working -> "WORK" to p.cy
        Activity.Texting -> "TEXT" to p.cy
        Activity.Emailing -> "EMAIL" to p.cy
        Activity.Calling -> "CALL" to p.cy
        Activity.Waiting -> "HOLD" to p.amb
        Activity.Done -> "DONE" to p.ok
        Activity.Failed -> "STUCK" to p.rec
    }
}

data class PendantTab<T>(val value: T, val label: String, val icon: ImageVector, val badge: Int = 0)

const val TAB_TAG_PREFIX = "tab-"

/** The bottom tab strip: mono caps, a safety-orange bar over the active tab, an amber count for approvals. */
@Composable
fun <T> PendantTabs(tabs: List<PendantTab<T>>, selected: T, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.bg).navigationBarsPadding()) {
        Rule()
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { t ->
                val on = t.value == selected
                Box(
                    Modifier.weight(1f).height(58.dp).selectable(on, role = Role.Tab) { onSelect(t.value) }
                        .testTag(TAB_TAG_PREFIX + t.label.lowercase()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Box(Modifier.align(Alignment.TopCenter).size(width = 44.dp, height = 2.dp).background(p.accent))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(t.icon, contentDescription = null, tint = if (on) p.ink else p.ink2, modifier = Modifier.size(20.dp))
                            if (t.badge > 0) {
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    "${t.badge}", style = Type.labelSm, color = p.accentInk,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(p.amb).padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Text(t.label.uppercase(), style = Type.labelMd, color = if (on) p.ink else p.ink2)
                    }
                }
            }
        }
    }
}
