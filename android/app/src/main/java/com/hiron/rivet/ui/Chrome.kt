package com.hiron.rivet.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.rivet.core.Activity
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetState

/**
 * The strip across the top of every screen: the RIVET mark and the screen's name, and on the right one status
 * capsule in plain words. On the robot's own screen it drops its rule so the header and the stage read as one
 * surface.
 */
@Composable
fun HudHeader(state: RivetState, unit: String, rule: Boolean = true) {
    val p = LocalPalette.current
    val status = status(state, p)
    // At large text sizes the screen's name gives the status capsule its room rather than being cut short.
    val named = LocalDensity.current.fontScale <= 1.15f
    Column(Modifier.fillMaxWidth().background(p.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append("RIV")
                        withStyle(SpanStyle(color = p.accent)) { append("E") }
                        append("T")
                    },
                    style = Type.brand.copy(fontSize = 26.sp), color = p.ink,
                )
                if (named) {
                    Spacer(Modifier.size(2.dp))
                    CapsText(unit, Type.labelSm, p.ink2, maxLines = 1)
                }
            }
            Capsule(status.text, status.color, status.live)
        }
        if (rule) Rule(color = p.line)
    }
}

/** A status pill: an LED and a rolling word. */
@Composable
fun Capsule(text: String, color: Color, live: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier.height(32.dp).clip(pill).background(p.card).border(1.dp, p.edge, pill).padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pip(color, live = live, size = 7.dp)
        Spacer(Modifier.width(2.dp))
        Odometer(text, Type.labelMd, color)
    }
}

private data class Status(val text: String, val color: Color, val live: Boolean = false)

private fun status(state: RivetState, p: Palette): Status = when (state.connection) {
    Connection.Offline -> Status("OFFLINE", p.rec)
    Connection.Connecting -> Status("CONNECTING", p.amb)
    Connection.Online -> when (state.activity) {
        Activity.Idle -> Status("READY", p.ok)
        Activity.Listening -> Status("LISTENING", p.rec, live = true)
        Activity.Thinking -> Status("THINKING", p.cy, live = true)
        Activity.Searching -> Status("SEARCHING", p.cy, live = true)
        Activity.Working -> Status("WORKING", p.cy, live = true)
        Activity.Texting -> Status("TEXTING", p.cy, live = true)
        Activity.Emailing -> Status("EMAILING", p.cy, live = true)
        Activity.Waiting -> Status("NEEDS YOU", p.amb, live = true)
        Activity.Done -> Status("DONE", p.ok)
        Activity.Failed -> Status("DIDN'T FINISH", p.rec)
    }
}

data class PendantTab<T>(val value: T, val label: String, val icon: ImageVector, val badge: Int = 0)

const val TAB_TAG_PREFIX = "tab-"

/**
 * The bottom tab bar: glass under line icons and mono caps; an orange pill and a soft bloom spring to the
 * active tab, and an amber count shows approvals.
 */
@Composable
fun <T> PendantTabs(tabs: List<PendantTab<T>>, selected: T, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().background(p.glass).navigationBarsPadding()) {
        Rule(color = p.line)
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
            val slot = maxWidth / tabs.size.coerceAtLeast(1)
            val at = tabs.indexOfFirst { it.value == selected }.coerceAtLeast(0)
            val x by animateDpAsState(slot * at, spring(dampingRatio = 0.75f, stiffness = 420f), label = "tab")
            Box(
                Modifier.offset(x = x).width(slot).fillMaxHeight().drawBehind {
                    val c = Offset(size.width / 2, size.height * 0.42f)
                    drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.10f), Color.Transparent), c, 28.dp.toPx()), 28.dp.toPx(), c)
                    val w = 20.dp.toPx()
                    drawRoundRect(p.accent, topLeft = Offset((size.width - w) / 2, 0f), size = Size(w, 3.dp.toPx()), cornerRadius = CornerRadius(1.5.dp.toPx()))
                },
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { t ->
                    val on = t.value == selected
                    Box(
                        Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab) {
                            if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(t.value)
                        }
                            .testTag(TAB_TAG_PREFIX + t.label.lowercase()),
                        contentAlignment = Alignment.Center,
                    ) {
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
                            Text(t.label.uppercase(), style = Type.labelSm, color = if (on) p.ink else p.ink2)
                        }
                    }
                }
            }
        }
    }
}
