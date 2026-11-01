package com.hiron.emrys.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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

/** How a key reads: a plain key, the one primary action (porcelain), or a destructive one (coral). */
enum class KeyTone { Plain, Accent }

private val KeyShape = RoundedCornerShape(50)

/**
 * A soft pill key: a quiet fill and a 1dp hairline, brightening while pressed. [KeyTone.Accent] is the porcelain
 * primary; [KeyTone.Danger] carries coral ink on a coral hairline. [tag] is a small quiet note after the label.
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
    val c = keyColors(LocalPalette.current, tone)
    KeyBody(onClick, modifier, c, enabled, role = Role.Button) {
        if (icon != null) Icon(icon, contentDescription = null, tint = c.icon, modifier = Modifier.size(18.dp))
        Text(text, style = Type.key, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tag != null) Text(tag, style = Type.labelMd, color = c.ink.copy(alpha = 0.6f), maxLines = 1)
    }
}

/**
 * A round key holding one icon: the composer's talk and send keys, the viewer's actions. [ring] lights a thin
 * breathing ring around it (coral while the microphone is live).
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
    val c = keyColors(LocalPalette.current, tone)
    val glow = if (ring != null) breathe(0.4f, 1f, Motion.BREATHE / 2) else 0f
    Box(
        modifier.size(size).drawBehind {
            if (ring != null) {
                val grow = 4.dp.toPx()
                val outline = CircleShape.createOutline(Size(this.size.width + grow * 2, this.size.height + grow * 2), layoutDirection, this)
                translate(-grow, -grow) { drawOutline(outline, ring.copy(alpha = 0.6f * glow), style = Stroke(1.dp.toPx())) }
            }
        },
    ) {
        KeyBody(
            onClick, Modifier.fillMaxSize().semantics { contentDescription = description }, c, enabled = true, role = Role.Button, square = true,
            shape = CircleShape,
        ) {
            Icon(icon, contentDescription = null, tint = c.icon, modifier = Modifier.size(21.dp))
        }
    }
}

/** A latching pill in the secondary-key look, porcelain while it is on; [height] and [radius] match it to the keys beside it. */
@Composable
fun SysKey(
    text: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    radius: Dp = 24.dp,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.height(height).clip(shape).background(if (on) p.ink else if (pressed) p.cardHi else p.key)
            .border(1.dp, if (on) p.ink else if (pressed) p.lineHi else p.line, shape)
            .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onToggle(it)
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        CapsText(text, Type.monoKey, if (on) p.accentInk else p.ink, maxLines = 1)
    }
}

private data class KeyColors(val fill: Color, val pressed: Color, val line: Color, val pressedLine: Color, val ink: Color, val icon: Color)

private fun keyColors(p: Palette, tone: KeyTone) = when (tone) {
    KeyTone.Plain -> KeyColors(p.key, p.cardHi, p.line, p.lineHi, p.ink, p.ink2)
    KeyTone.Accent -> KeyColors(p.ink, p.ink, p.ink, p.accentHi, p.accentInk, p.accentInk)
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
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val down = pressed && enabled
    Row(
        modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .graphicsLayer { if (down) { scaleX = 0.98f; scaleY = 0.98f } }
            .then(if (down) Modifier.ring(c.pressedLine.copy(alpha = 0.6f), shape, true, gap = 2.dp) else Modifier)
            .clip(shape).background(if (down) c.pressed else c.fill)
            .border(1.dp, if (down) c.pressedLine else c.line, shape)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = role) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .padding(horizontal = if (square) 0.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        content = content,
    )
}

/** An infinitely breathing value between [from] and [to]; reduced-motion hosts hold it at [to]. */
@Composable
private fun breathe(from: Float, to: Float, halfPeriodMs: Int): Float {
    if (rememberReduceMotion()) return to
    val t = rememberInfiniteTransition(label = "breathe")
    return t.animateFloat(to, from, infiniteRepeatable(tween(halfPeriodMs, easing = Motion.Standard), RepeatMode.Reverse), label = "breathe").value
}

@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = LocalPalette.current.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * Text that settles softly when it changes: the old value fades up and away while the new one fades in from just
 * below. With motion reduced it changes in place.
 */
@Composable
fun Odometer(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, overflow: TextOverflow = TextOverflow.Clip) {
    val reduce = rememberReduceMotion()
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            if (reduce) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (slideInVertically(tween(Motion.BASE, easing = Motion.Emphasized)) { it / 3 } + fadeIn(tween(Motion.BASE))) togetherWith
                    (slideOutVertically(tween(Motion.QUICK)) { -it / 3 } + fadeOut(tween(Motion.QUICK)))
            }
        },
        modifier = modifier,
        label = "odometer",
    ) { value -> Text(value, style = style, color = color, maxLines = 1, overflow = overflow) }
}

/**
 * An input: a rounded field fill and a hairline at rest; focused, a halo border, a faint halo ring and a halo
 * caret. [label] sits above in Geist, as written.
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
    val shape = RoundedCornerShape(14.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = Type.labelLg, color = if (focused) p.accent else p.ink2)
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
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).ring(p.accent.copy(alpha = 0.16f), shape, focused, gap = 2.dp)
                        .clip(shape).background(p.field)
                        .border(1.dp, if (focused) p.accent.copy(alpha = 0.6f) else p.line, shape)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = Type.bodyLg, color = p.ink2.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

/** A label/value row: a quiet label and its value. [mono] sets the value in Geist Mono, for codes, addresses and ids. */
@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null, mono: Boolean = false) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body, color = p.ink2, modifier = Modifier.padding(end = 16.dp))
        Spacer(Modifier.weight(1f))
        Text(value, style = if (mono) Type.mono else Type.body.copy(fontWeight = Type.title.fontWeight), color = valueColor ?: p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
