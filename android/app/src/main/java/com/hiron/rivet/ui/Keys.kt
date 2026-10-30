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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.unit.sp

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
 * Mono caps for keys, links and labels. Screen readers and tests get [text] as written, so a key that shows
 * "NOT NOW" is still announced as "Not now".
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
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text.uppercase(locale), style = style, color = color, textAlign = textAlign, maxLines = maxLines, overflow = overflow,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
    )
}

/** The full-width glowing primary key: dark ink on the orange; a disabled key is a graphite plate. */
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
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .graphicsLayer { if (pressed) { scaleX = 0.985f; scaleY = 0.985f } }
            .then(if (enabled) Modifier.glow(Glows.cta, CtaShape) else Modifier)
            .clip(CtaShape)
            .background(
                if (!enabled) SolidColor(p.tile)
                else Brush.verticalGradient(if (pressed) listOf(p.accentHi, p.accentHi) else listOf(p.accentHi, p.accent)),
            )
            .then(if (enabled) Modifier.topLine(Color.White.copy(alpha = 0.14f)) else Modifier.border(1.dp, p.edge, CtaShape))
            .then(press)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Type.button, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (trailingArrow) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
    }
}

/** A hairline of light across the flat of the top edge, starting where the corners end. */
private fun Modifier.topLine(color: Color) = drawWithContent {
    drawContent()
    val inset = size.height / 2
    drawLine(color, Offset(inset, 0.75.dp.toPx()), Offset(size.width - inset, 0.75.dp.toPx()), strokeWidth = 1.dp.toPx())
}

/** The outlined secondary key under a primary key. [height] 56 pairs it with a primary key in one row; [radius] 12 is the compact card key. */
@Composable
fun SecondaryKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    radius: Dp = 16.dp,
) {
    val p = LocalPalette.current
    val (press, pressed) = pressable(enabled, Role.Button, onClick)
    val shape = RoundedCornerShape(radius)
    Row(
        modifier.fillMaxWidth().height(height).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .background(if (pressed) p.cardHi else p.raised).border(1.dp, if (pressed) p.lineHi else p.edge, shape)
            .then(press).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        CapsText(text, Type.monoKey, p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A mono caps text link. */
@Composable
fun TextKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val p = LocalPalette.current
    val (press, pressed) = pressable(true, Role.Button, onClick)
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).then(press).alpha(if (pressed) 0.6f else 1f).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = p.ink2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        CapsText(text, Type.monoKey, p.ink2, maxLines = 1)
    }
}

/** A pill filter chip: white when selected. */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val pill = RoundedCornerShape(50)
    val fill by animateColorAsState(if (selected) p.white else p.field, tween(Motion.QUICK), label = "chip")
    Box(
        modifier.height(32.dp).clip(pill).background(fill).border(1.dp, if (selected) p.white else p.edge, pill)
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
                        onCheckedChange(it)
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
