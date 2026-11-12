package com.hiron.ren.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import com.hiron.ren.R
import com.hiron.ren.core.Activity
import com.hiron.ren.core.RenState

/** The name the user sees and hears. */
private const val BRAND = "Ren"

/**
 * The masthead across the top of every screen: the ren. logotype with the screen's name under it, and on the right
 * one quiet status line (a dot and plain words) with, under it, how many actions wait on the user.
 * On the robot's own screen it drops its rule so the header and the stage read as one surface. [onClose] adds the
 * key that closes the screen back to the browser.
 */
@Composable
fun HudHeader(state: RenState, unit: String, rule: Boolean = true, onClose: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val status = status(state, p)
    // At large text sizes the screen's name gives the status line its room rather than being cut short.
    val named = LocalDensity.current.fontScale <= 1.15f
    Column(Modifier.fillMaxWidth().background(p.chrome).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                RenWordmark()
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
            if (onClose != null) {
                Spacer(Modifier.width(12.dp))
                IconKey(Icons.Outlined.Close, "Close", onClose, size = 40.dp)
            }
        }
        if (rule) Rule(color = p.line)
    }
}

/**
 * The logo: the ren. logotype as outlines, ink with a volt full stop. It is sized like text: [size] is the font size
 * whose x-height the letters match, as in ren desktop's `RenWordmark`. Read as "Ren".
 */
@Composable
internal fun RenWordmark(modifier: Modifier = Modifier, size: Float = 28f) {
    val p = LocalPalette.current
    val box = Modifier.size(width = (size * LOGOTYPE_WIDTH_EM).dp, height = (size * LOGOTYPE_HEIGHT_EM).dp)
    Box(modifier.clearAndSetSemantics { text = AnnotatedString(BRAND); heading() }) {
        Icon(painterResource(R.drawable.ren_logotype), contentDescription = null, tint = p.ink, modifier = box)
        Icon(painterResource(R.drawable.ren_logotype_dot), contentDescription = null, tint = p.accent, modifier = box)
    }
}

/** The logotype's advance box in ems (docs/design/ren-logotype.json: 1506.7 by 554 font units). */
private const val LOGOTYPE_WIDTH_EM = 1.5067f
private const val LOGOTYPE_HEIGHT_EM = 0.554f

/** A status pill: a dot and words that settle softly when they change. */
@Composable
fun Capsule(text: String, color: Color, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier.height(32.dp).clip(pill).background(p.tile).padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalDot(color)
        Spacer(Modifier.width(8.dp))
        Odometer(text, Type.labelLg, p.ink)
    }
}

private data class Status(val text: String, val color: Color, val live: Boolean = false)

private fun status(state: RenState, p: Palette): Status = when (state.activity) {
    Activity.Idle -> Status("Ready", p.ok)
    Activity.Listening -> Status("Listening", p.danger, live = true)
    Activity.Thinking -> Status("Thinking", p.accent, live = true)
    Activity.Searching -> Status("Searching", p.accent, live = true)
    Activity.Working -> Status("Working", p.accent, live = true)
    Activity.Texting -> Status("Texting", p.accent, live = true)
    Activity.Emailing -> Status("Emailing", p.accent, live = true)
    Activity.Waiting -> Status("Needs your OK", p.amb, live = true)
    Activity.Done -> Status("Done", p.ok)
    Activity.Failed -> Status("Didn't finish", p.danger)
}
