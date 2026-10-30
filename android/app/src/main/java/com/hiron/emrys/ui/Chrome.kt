package com.hiron.emrys.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.emrys.R
import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.EmrysState

/** Opens the voice setup screens over the whole app (provided by MainActivity). */
val LocalOpenVoiceSetup = staticCompositionLocalOf<() -> Unit> { {} }

/** The name the user sees and hears. */
private const val BRAND = "Emrys"

/**
 * The masthead across the top of every screen: the Emrys sigil and serif wordmark with the screen's name under
 * them, and on the right one quiet status line (a dot and plain words) with, under it, how many actions wait on
 * the user.
 * On the robot's own screen it drops its rule so the header and the stage read as one surface.
 */
@Composable
fun HudHeader(state: EmrysState, unit: String, rule: Boolean = true) {
    val p = LocalPalette.current
    val status = status(state, p)
    // At large text sizes the screen's name gives the status line its room rather than being cut short.
    val named = LocalDensity.current.fontScale <= 1.15f
    Column(Modifier.fillMaxWidth().background(p.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                EmrysWordmark()
                if (named) Text(unit, style = Type.labelMd, color = p.ink2, maxLines = 1, modifier = Modifier.padding(start = 1.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignalDot(status.color, live = status.live)
                    Spacer(Modifier.width(8.dp))
                    Odometer(status.text, Type.labelLg, p.ink, Modifier.weight(1f, fill = false), overflow = TextOverflow.Ellipsis)
                }
                val waiting = state.approvals.size
                if (waiting > 0) Odometer("$waiting waiting", Type.labelMd, p.ink2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (rule) Rule(color = p.line)
    }
}

/** The brand lockup: the dot-matrix sigil at the serif's cap height, then "Emrys" in Instrument Serif. Read as "Emrys". */
@Composable
internal fun EmrysWordmark(modifier: Modifier = Modifier, size: Float = 28f) {
    val p = LocalPalette.current
    Row(modifier.clearAndSetSemantics { text = AnnotatedString(BRAND); heading() }, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.emrys_sigil), contentDescription = null, tint = p.accent, modifier = Modifier.size((size * 0.84f).dp))
        Spacer(Modifier.width((size * 0.28f).dp))
        Text(BRAND, style = Type.brand.copy(fontSize = size.sp, lineHeight = (size + 2).sp), color = p.ink, maxLines = 1)
    }
}

/** A status pill on glass: a dot and words that settle softly when they change. */
@Composable
fun Capsule(text: String, color: Color, live: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier.height(32.dp).clip(pill).background(p.glass).border(1.dp, p.line, pill).padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalDot(color, live = live)
        Spacer(Modifier.width(8.dp))
        Odometer(text, Type.labelLg, p.ink)
    }
}

private data class Status(val text: String, val color: Color, val live: Boolean = false)

private fun status(state: EmrysState, p: Palette): Status = when (state.connection) {
    Connection.Offline -> Status("Offline", p.rec)
    Connection.Connecting -> Status("Connecting", p.amb)
    Connection.Online -> when (state.activity) {
        Activity.Idle -> Status("Ready", p.ok)
        Activity.Listening -> Status("Listening", p.rec, live = true)
        Activity.Thinking -> Status("Thinking", p.cy, live = true)
        Activity.Searching -> Status("Searching", p.cy, live = true)
        Activity.Working -> Status("Working", p.cy, live = true)
        Activity.Texting -> Status("Texting", p.cy, live = true)
        Activity.Emailing -> Status("Emailing", p.cy, live = true)
        Activity.Calling -> Status("On a call", p.cy, live = true)
        Activity.Waiting -> Status("Needs your OK", p.amb, live = true)
        Activity.Done -> Status("Done", p.ok)
        Activity.Failed -> Status("Didn't finish", p.rec)
    }
}

/** A tab: [tag] names its test tag (after [TAB_TAG_PREFIX]) and stays the same whatever the label says. */
data class PendantTab<T>(val value: T, val label: String, val icon: ImageVector, val badge: Int = 0, val tag: String = label.lowercase())

const val TAB_TAG_PREFIX = "tab-"

/**
 * The bottom tab bar: glass under a hairline, outlined icons and Geist labels, porcelain for the active tab and
 * grey for the rest. A short porcelain bar settles over the active tab, and a honey count shows approvals.
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
            val x by animateDpAsState(slot * at, spring(dampingRatio = 0.9f, stiffness = 300f), label = "tab")
            Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(slot).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.offset(y = (-1).dp).size(width = 22.dp, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(p.ink))
            }
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { t ->
                    val on = t.value == selected
                    val ink by animateColorAsState(if (on) p.ink else p.ink2, tween(Motion.BASE), label = "tab-ink")
                    Box(
                        Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab) {
                            if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(t.value)
                        }
                            .testTag(TAB_TAG_PREFIX + t.tag),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box {
                                Icon(t.icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
                                if (t.badge > 0) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-5).dp).defaultMinSize(minWidth = 16.dp).height(16.dp)
                                            .clip(CircleShape).background(p.amb).border(1.5.dp, p.bg, CircleShape).padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("${t.badge}", style = Type.labelSm.copy(fontFeatureSettings = "tnum"), color = p.accentInk, maxLines = 1)
                                    }
                                }
                            }
                            Text(t.label, style = Type.labelMd.copy(fontSize = 12.sp, letterSpacing = 0.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
