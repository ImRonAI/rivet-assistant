package com.hiron.rivet.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How a keycap reads: a plain key, the one primary action (safety orange), or a destructive one. */
enum class KeyTone { Plain, Accent, Danger }

private val KeyShape = RoundedCornerShape(12.dp)

/**
 * A teach-pendant keycap: 1px border, a lit top edge, a 1px lip underneath, and a 1px press that drops the
 * lip. [tag] is the small mono plate on the right of the label.
 */
@Composable
fun Keycap(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: KeyTone = KeyTone.Plain,
    tag: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val p = LocalPalette.current
    val c = keyColors(p, tone)
    KeyBody(onClick, modifier, c, enabled, role = Role.Button, glow = tone == KeyTone.Accent) {
        if (icon != null) Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
        Text(text, style = Type.key, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tag != null) {
            Text(
                tag, style = Type.labelSm, color = if (tone == KeyTone.Plain) p.ink2 else c.ink.copy(alpha = 0.7f),
                modifier = Modifier.border(1.dp, if (tone == KeyTone.Plain) p.edge else c.ink.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * A square keycap holding one icon: the composer's talk and send keys. [ring] lights an LED ring around it
 * (crimson while the microphone is live).
 */
@Composable
fun IconKey(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: KeyTone = KeyTone.Plain,
    size: Dp = 48.dp,
    ring: Color? = null,
) {
    val p = LocalPalette.current
    val c = keyColors(p, tone)
    val glow = if (ring != null) breathe(0.35f, 1f, 700) else 0f
    val shape = if (tone == KeyTone.Accent) RoundedCornerShape(50) else RoundedCornerShape(14.dp)
    Box(
        modifier.size(size).drawBehind {
            if (ring != null) {
                val grow = 5.dp.toPx()
                val outline = shape.createOutline(Size(this.size.width + grow * 2, this.size.height + grow * 2), layoutDirection, this)
                translate(-grow, -grow) { drawOutline(outline, ring.copy(alpha = 0.35f * glow), style = Stroke(2.dp.toPx())) }
            }
        },
    ) {
        KeyBody(
            onClick, Modifier.fillMaxSize().semantics { contentDescription = description }, c, enabled = true, role = Role.Button, square = true,
            shape = shape, glow = tone == KeyTone.Accent,
        ) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        }
    }
}

/** A latching system key in the filter-chip look: a white pill while it is on. */
@Composable
fun SysKey(text: String, on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pill = RoundedCornerShape(50)
    Box(
        modifier.defaultMinSize(minHeight = 48.dp)
            .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onToggle(it)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(36.dp).clip(pill).background(if (on) p.white else if (pressed) p.cardHi else p.field)
                .border(1.dp, if (on) p.white else p.edge, pill).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = Type.monoKey.copy(fontSize = 12.sp), color = if (on) p.bg else p.ink, maxLines = 1)
        }
    }
}

private data class KeyColors(val fill: Color, val pressed: Color, val line: Color, val ink: Color, val light: Color)

private fun keyColors(p: Palette, tone: KeyTone) = when (tone) {
    KeyTone.Plain -> KeyColors(p.card, p.cardHi, p.edge, p.ink, Color.White.copy(alpha = 0.06f))
    KeyTone.Accent -> KeyColors(p.accent, p.accentHi, p.accent, p.accentInk, Color.White.copy(alpha = 0.28f))
    KeyTone.Danger -> KeyColors(p.card, p.cardHi, p.rec.copy(alpha = 0.6f), p.rec, Color.White.copy(alpha = 0.06f))
}

@Composable
private fun KeyBody(
    onClick: () -> Unit,
    modifier: Modifier,
    c: KeyColors,
    enabled: Boolean,
    role: Role,
    square: Boolean = false,
    shape: Shape = KeyShape,
    glow: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f }
            .then(if (glow && enabled) Modifier.glow(Glows.cta.color, 12.dp, shape) else Modifier)
            .lip(p.keyShadow, !pressed, shape)
            .clip(shape).background(if (pressed) c.pressed else c.fill).topLight(c.light)
            .border(1.dp, c.line, shape)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = role) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .padding(horizontal = if (square) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        content = content,
    )
}

/** The 1px shadow line under a keycap; it disappears while the key is pressed down. */
private fun Modifier.lip(color: Color, show: Boolean, shape: Shape) = drawBehind {
    if (show) translate(0f, 1.dp.toPx()) { drawOutline(shape.createOutline(size, layoutDirection, this), color) }
}

/** A hairline of light along the top edge, as on a machined key. */
private fun Modifier.topLight(color: Color) = drawWithContent {
    drawContent()
    val inset = 6.dp.toPx()
    drawLine(color, Offset(inset, 0.75.dp.toPx()), Offset(size.width - inset, 0.75.dp.toPx()), strokeWidth = 1.dp.toPx())
}

/** An infinitely breathing value between [from] and [to]; tests and reduced-motion hosts hold it at [to]. */
@Composable
private fun breathe(from: Float, to: Float, halfPeriodMs: Int): Float {
    val t = rememberInfiniteTransition(label = "breathe")
    return t.animateFloat(to, from, infiniteRepeatable(tween(halfPeriodMs), RepeatMode.Reverse), label = "breathe").value
}

/** A status LED. [live] pulses it (the robot HUD's `live` pip) and blooms it. */
@Composable
fun Pip(color: Color, modifier: Modifier = Modifier, live: Boolean = false, size: Dp = 8.dp) {
    val alpha = if (live) breathe(0.35f, 1f, 600) else 1f
    Canvas(modifier.size(size * 2)) {
        val r = size.toPx() / 2
        if (live) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * alpha), Color.Transparent), center, r * 2.2f), r * 2.2f)
        drawCircle(color.copy(alpha = alpha), r)
        drawCircle(Color.White.copy(alpha = 0.35f * alpha), r * 0.35f, center + Offset(-r * 0.3f, -r * 0.3f))
    }
}

/** A tag plate in the [StatusTag] style for any colour: tinted fill, mono caps. [hot] fills it solid. */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier, hot: Boolean = false) {
    TagPlate(text, color, modifier, hot = hot)
}

/** Four rivet heads in the corners of a plate: Rivet's signature, and how a machined panel is held on. */
fun Modifier.rivets(color: Color, inset: Dp = 7.dp, radius: Dp = 1.6.dp) = drawBehind {
    val i = inset.toPx()
    val r = radius.toPx()
    listOf(Offset(i, i), Offset(size.width - i, i), Offset(i, size.height - i), Offset(size.width - i, size.height - i)).forEach {
        drawCircle(color, r, it)
        drawCircle(Color.White.copy(alpha = 0.10f), r * 0.45f, it + Offset(-r * 0.3f, -r * 0.3f))
    }
}

/**
 * A HUD card: card fill and hairline edge, an uppercase mono header with an optional index, LED and right-hand
 * meta, a divider, then the body.
 */
@Composable
fun HudCard(
    title: String,
    modifier: Modifier = Modifier,
    index: String? = null,
    pip: Color? = null,
    live: Boolean = false,
    meta: String? = null,
    metaColor: Color? = null,
    outline: Color? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, outline ?: p.edge, shape)) {
        HudHeaderRow(title, index = index, pip = pip, live = live, meta = meta, metaColor = metaColor)
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** The header strip of a HUD card, also used on its own above full-bleed lists. */
@Composable
fun HudHeaderRow(
    title: String,
    modifier: Modifier = Modifier,
    index: String? = null,
    pip: Color? = null,
    live: Boolean = false,
    meta: String? = null,
    metaColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(start = if (pip != null) 8.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pip != null) Pip(pip, live = live)
            if (pip != null) Spacer(Modifier.size(4.dp))
            if (index != null) Text(index, style = Type.labelSm, color = p.ink2, modifier = Modifier.padding(end = 10.dp))
            Text(title.uppercase(), style = Type.labelSm, color = p.ink, modifier = Modifier.weight(1f), maxLines = 1)
            if (meta != null) Odometer(meta.uppercase(), Type.labelSm, metaColor ?: p.ink2)
            trailing?.invoke()
        }
        Rule(color = p.line)
    }
}

@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = LocalPalette.current.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * Text that rolls like a split-flap readout when it changes: the old value slides up and out, the new one up
 * and in.
 */
@Composable
fun Odometer(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(260)) { it } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(220)) { -it } + fadeOut(tween(180)))
        },
        modifier = modifier,
        label = "odometer",
    ) { value -> Text(value, style = style, color = color, maxLines = 1) }
}

/** A choice of a few options: [FilterChip]s in a pill track; the chosen one is white. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val track = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().clip(track).background(p.field).border(1.dp, p.edge, track).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) -> Chip(label, value == selected, { onSelect(value) }, Modifier.weight(1f).height(40.dp), outlined = false) }
    }
}

/** A recessed well: a dark line along the inside top edge, as if the surface were milled down. */
private fun Modifier.innerShade() = drawWithContent {
    drawContent()
    drawLine(Color.Black.copy(alpha = 0.45f), Offset(10.dp.toPx(), 1.dp.toPx()), Offset(size.width - 10.dp.toPx(), 1.dp.toPx()), strokeWidth = 1.5.dp.toPx())
}

/**
 * A recessed input: field fill and a hairline at rest; focused, an orange border with a faint outer ring.
 * [label] sits above in mono caps.
 */
@Composable
fun RecessedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 5,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    fieldModifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label.uppercase(), style = Type.labelSm, color = if (focused) p.accent else p.ink2)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = Type.bodyLg.copy(color = p.ink),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = source,
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).ring(p.accent.copy(alpha = 0.3f), shape, focused)
                        .clip(shape).background(p.field).innerShade()
                        .border(1.dp, if (focused) p.accent.copy(alpha = 0.6f) else p.edge, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = Type.bodyLg, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

/** A switch in Rivet's colours: the [RivetToggle]. */
@Composable
fun RivetSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) = RivetToggle(checked, onCheckedChange, modifier)

/** A label/value row in mono, like the header telemetry. */
@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = Type.labelSm, color = p.ink2, modifier = Modifier.padding(end = 12.dp))
        Spacer(Modifier.weight(1f))
        Text(value, style = Type.mono, color = valueColor ?: p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A counter plate: a big display numeral over a mono caption, for the activity summary. */
@Composable
fun StatTile(value: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(p.card).border(1.dp, p.edge, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(6.dp))
            Text(label.uppercase(), style = Type.eyebrow, color = p.ink2, maxLines = 1)
        }
        Odometer("%02d".format(value), Type.stat, p.ink)
    }
}
