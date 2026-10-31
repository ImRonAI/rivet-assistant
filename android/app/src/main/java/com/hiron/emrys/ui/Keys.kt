package com.hiron.emrys.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** Every key in Noir is a full pill. */
private val CtaShape = RoundedCornerShape(50)

/** A pressable that ticks under the finger and reports whether it is held down. */
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
 * Key and link text, rendered exactly as written (Noir sets interface text in sentence case). Screen readers and
 * tests get [text] as written.
 */
@Composable
fun CapsText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text, style = style, color = color, textAlign = textAlign, maxLines = maxLines, overflow = overflow,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
    )
}

/**
 * The full-width primary call to action: a porcelain pill with noir ink in Geist Medium. Pressed, it gathers a thin halo
 * hairline and a soft halo light; disabled, it is a faint glass pill with grey ink.
 */
@Composable
fun PrimaryKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingArrow: Boolean = true,
    leading: ImageVector? = null,
) {
    val p = LocalPalette.current
    val (press, pressed) = pressable(enabled, Role.Button, onClick)
    val ink = if (enabled) p.accentInk else p.ink2
    val down = enabled && pressed
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .graphicsLayer { if (down) { scaleX = 0.985f; scaleY = 0.985f } }
            .then(if (down) Modifier.glow(Glows.cta, CtaShape).ring(p.accentHi.copy(alpha = 0.75f), CtaShape, true, gap = 2.dp) else Modifier)
            .clip(CtaShape)
            .background(if (enabled) p.ink else p.edge)
            .then(if (enabled) Modifier else Modifier.border(1.dp, p.line, CtaShape))
            .then(press)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text, style = Type.button, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailingArrow) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The outline pill under a primary key: a porcelain hairline and Geist Medium label, brightening while pressed.
 * [height] 56 pairs it with a primary key in one row; a smaller [radius] gives the compact card key.
 */
@Composable
fun SecondaryKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    radius: Dp = 28.dp,
) {
    val p = LocalPalette.current
    val (press, pressed) = pressable(enabled, Role.Button, onClick)
    val shape = RoundedCornerShape(radius)
    Row(
        modifier.fillMaxWidth().height(height).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .background(if (pressed) p.cardHi else Color.Transparent).border(1.dp, if (pressed) p.ink.copy(alpha = 0.28f) else p.lineHi, shape)
            .then(press).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        CapsText(text, if (height >= 52.dp) Type.button else Type.key, p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A text link in Geist Medium, in porcelain, with an optional [leading] icon. */
@Composable
fun TextKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val p = LocalPalette.current
    val (press, pressed) = pressable(true, Role.Button, onClick)
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).then(press).alpha(if (pressed) 0.55f else 1f).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = p.ink2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        CapsText(text, Type.monoKey, p.ink, maxLines = 1)
    }
}

/** A pill filter chip: porcelain when selected, a hairline outline otherwise. */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val pill = RoundedCornerShape(50)
    val fill by animateColorAsState(if (selected) p.ink else Color.Transparent, tween(Motion.BASE, easing = Motion.Emphasized), label = "chip")
    Box(
        modifier.height(34.dp).clip(pill).background(fill).border(1.dp, if (selected) p.ink else p.lineHi, pill)
            .selectable(selected, role = Role.RadioButton) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.labelLg, color = if (selected) p.accentInk else p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val x by animateDpAsState(if (checked) width - thumb - inset else inset, tween(Motion.BASE, easing = Motion.Emphasized), label = "toggle")
    val track by animateColorAsState(if (checked) p.ink else p.track, tween(Motion.BASE), label = "track")
    val knob by animateColorAsState(if (checked) p.bg else p.ink2, tween(Motion.BASE), label = "thumb")
    val pill = RoundedCornerShape(50)
    val interactive = onCheckedChange != null
    Box(
        modifier
            .then(
                if (interactive) {
                    Modifier.minimumInteractiveComponentSize().toggleable(checked, enabled = enabled, role = Role.Switch) {
                        haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        onCheckedChange(it)
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width, height).alpha(if (enabled) 1f else 0.4f).clip(pill).background(track)
                .border(1.dp, if (checked) p.ink else p.lineHi, pill),
        ) {
            Box(Modifier.offset { IntOffset(x.roundToPx(), inset.roundToPx()) }.size(thumb).clip(CircleShape).background(knob))
        }
    }
}

/** Emrys's switch: a slim porcelain track with a noir thumb when on, a quiet graphite track when off. A null [onCheckedChange] shows the state only. */
@Composable
fun EmrysToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Toggle(checked, onCheckedChange, modifier, enabled, 36.dp, 20.dp, 14.dp)
}

/** A radio mark for radio cards: a faint ring, porcelain and filled when chosen. */
@Composable
fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val dot by animateDpAsState(if (selected) 8.dp else 0.dp, tween(Motion.BASE, easing = Motion.Emphasized), label = "radio")
    Box(modifier.size(20.dp).clip(CircleShape).border(1.dp, if (selected) p.ink else p.dot, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(dot).clip(CircleShape).background(p.ink))
    }
}

/** The round porcelain send key; pressed, it gathers a thin halo hairline. */
@Composable
fun SendKey(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Send") {
    val p = LocalPalette.current
    val (press, pressed) = pressable(true, Role.Button, onClick)
    Box(
        modifier.size(52.dp)
            .graphicsLayer { if (pressed) { scaleX = 0.95f; scaleY = 0.95f } }
            .then(if (pressed) Modifier.glow(Glows.cta, CircleShape).ring(p.accentHi.copy(alpha = 0.75f), CircleShape, true, gap = 2.dp) else Modifier)
            .clip(CircleShape).background(p.ink)
            .semantics { contentDescription = description }
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.ArrowUpward, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(22.dp))
    }
}
