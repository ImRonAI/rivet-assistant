package com.hiron.rivet.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

enum class CtaStyle { Sans, Display }

private val CtaShape = RoundedCornerShape(16.dp)

/** A pressable that dips slightly and ticks under the finger. */
@Composable
private fun pressable(enabled: Boolean, role: Role, onClick: () -> Unit): Pair<Modifier, Boolean> {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val m = Modifier.clickable(enabled = enabled, interactionSource = source, indication = null, role = role) {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onClick()
    }
    return m to pressed
}

/**
 * The full-width glowing primary key. [CtaStyle.Sans] is the onboarding and sheet key; [CtaStyle.Display] is the
 * voice-setup key in Big Shoulders.
 */
@Composable
fun PrimaryKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CtaStyle = CtaStyle.Sans,
    enabled: Boolean = true,
    trailingArrow: Boolean = true,
    leading: ImageVector? = null,
) {
    val p = LocalPalette.current
    val (press, pressed) = pressable(enabled, Role.Button, onClick)
    val ink = when {
        !enabled -> p.ink.copy(alpha = 0.5f)
        style == CtaStyle.Display -> p.white
        else -> p.accentInk
    }
    val glow = if (style == CtaStyle.Display) Glows.ctaDrop else Glows.cta
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .graphicsLayer { if (pressed) { scaleX = 0.985f; scaleY = 0.985f } }
            .then(if (enabled) Modifier.glow(glow, CtaShape) else Modifier)
            .clip(CtaShape)
            .background(
                if (!enabled) SolidColor(p.accent.copy(alpha = 0.35f))
                else Brush.verticalGradient(if (pressed) listOf(p.accentHi, p.accentHi) else listOf(p.accentHi, p.accent)),
            )
            .topLine(Color.White.copy(alpha = if (enabled) 0.22f else 0f))
            .then(press)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text, style = if (style == CtaStyle.Display) Type.cta else Type.button, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailingArrow) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
    }
}

/** A hairline of light across the top edge. */
private fun Modifier.topLine(color: Color) = drawWithContent {
    drawContent()
    if (color.alpha > 0f) {
        val inset = 14.dp.toPx()
        drawLine(color, Offset(inset, 0.75.dp.toPx()), Offset(size.width - inset, 0.75.dp.toPx()), strokeWidth = 1.dp.toPx())
    }
}

/** The outlined secondary key under a primary key. */
@Composable
fun SecondaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null, enabled: Boolean = true) {
    val p = LocalPalette.current
    val (press, pressed) = pressable(enabled, Role.Button, onClick)
    Row(
        modifier.fillMaxWidth().height(48.dp).alpha(if (enabled) 1f else 0.4f).clip(CtaShape)
            .background(if (pressed) p.cardHi else p.raised).border(1.dp, if (pressed) p.lineHi else p.edge, CtaShape)
            .then(press).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.monoKey, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A mono text link. */
@Composable
fun TextKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null, tone: Tone = Tone.Neutral) {
    val p = LocalPalette.current
    val (press, pressed) = pressable(true, Role.Button, onClick)
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).then(press).alpha(if (pressed) 0.6f else 1f).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = tone.color(), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.monoKey.copy(letterSpacing = 0.15.em), color = p.ink2, maxLines = 1)
    }
}

/** A pill filter chip: white when selected. */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) = Chip(text, selected, onClick, modifier, outlined = true)

/** A [FilterChip]; inside a [Segmented] track it drops its own outline. */
@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, outlined: Boolean) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val pill = RoundedCornerShape(50)
    val fill by animateColorAsState(if (selected) p.white else p.field, tween(Motion.QUICK), label = "chip")
    Box(
        modifier.height(32.dp).clip(pill).background(fill).border(1.dp, if (selected) p.white else if (outlined) p.edge else Color.Transparent, pill)
            .selectable(selected, role = Role.RadioButton) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.monoKey.copy(fontSize = 12.sp), color = if (selected) p.bg else p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A suggestion or quick-action pill. [selected] latches it white. */
@Composable
fun PillChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null, selected: Boolean = false) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    val (press, pressed) = pressable(true, Role.Button, onClick)
    val ink = if (selected) p.bg else p.ink
    Row(
        modifier.height(40.dp).clip(pill).background(if (selected) p.white else if (pressed) p.cardHi else p.field)
            .border(1.dp, if (selected) p.white else p.edge, pill).then(press).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = if (selected) p.bg else p.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.chip, color = ink, maxLines = 1)
    }
}

/** A horizontal rail that bleeds off the right edge. */
@Composable
fun ChipRail(modifier: Modifier = Modifier, start: Dp = 20.dp, content: LazyListScope.() -> Unit) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = start, end = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Toggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean, width: Dp, height: Dp, thumb: Dp) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val inset = (height - thumb) / 2
    val x by animateDpAsState(if (checked) width - thumb - inset else inset, tween(150, easing = Motion.Standard), label = "toggle")
    val track by animateColorAsState(if (checked) p.accent else p.track, tween(150), label = "track")
    val pill = RoundedCornerShape(50)
    val interactive = onCheckedChange != null
    Box(
        modifier
            .then(
                if (interactive) {
                    Modifier.minimumInteractiveComponentSize().toggleable(checked, enabled = enabled, role = Role.Switch) {
                        haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        onCheckedChange?.invoke(it)
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width, height).alpha(if (enabled) 1f else 0.5f).clip(pill).background(track)
                .border(1.dp, if (checked) p.accentHi.copy(alpha = 0.6f) else p.edge, pill),
        ) {
            Box(Modifier.offset { IntOffset(x.roundToPx(), inset.roundToPx()) }.size(thumb).clip(CircleShape).background(p.ink))
        }
    }
}

/** Rivet's toggle: orange track when on, graphite when off. A null [onCheckedChange] shows the state only. */
@Composable
fun RivetToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Toggle(checked, onCheckedChange, modifier, enabled, 36.dp, 20.dp, 16.dp)
}

/** The small toggle for nested rows. */
@Composable
fun CompactToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Toggle(checked, onCheckedChange, modifier, enabled, 32.dp, 16.dp, 12.dp)
}

/** A radio mark for radio cards. */
@Composable
fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val dot by animateDpAsState(if (selected) 10.dp else 0.dp, tween(Motion.QUICK), label = "radio")
    Box(modifier.size(20.dp).clip(CircleShape).border(1.dp, if (selected) p.accent else p.dot, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(dot).clip(CircleShape).background(p.accent))
    }
}

/** The round accent send key. */
@Composable
fun SendKey(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Send") {
    val p = LocalPalette.current
    val (press, pressed) = pressable(true, Role.Button, onClick)
    Box(
        modifier.size(52.dp)
            .graphicsLayer { if (pressed) { scaleX = 0.94f; scaleY = 0.94f } }
            .glow(Glows.cta.color, 12.dp)
            .clip(CircleShape).background(if (pressed) p.accentHi else p.accent)
            .semantics { contentDescription = description }
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.ArrowUpward, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(22.dp))
    }
}
