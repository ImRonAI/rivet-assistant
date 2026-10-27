package com.hiron.rivet.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** How a keycap reads: a plain key, the one primary action (safety orange), or a destructive one. */
enum class KeyTone { Plain, Accent, Danger }

private val KeyShape = RoundedCornerShape(8.dp)

/**
 * A teach-pendant keycap: 1px border, a 1px lip underneath, and a 1px press that drops the lip. [tag] is the
 * small mono plate on the right of the label.
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
    val (fill, line, ink) = keyColors(p, tone)
    KeyBody(onClick, modifier, fill, line, enabled, role = Role.Button) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(text, style = Type.key, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tag != null) {
            Text(
                tag, style = Type.labelSm, color = if (tone == KeyTone.Plain) p.ink2 else ink.copy(alpha = 0.7f),
                modifier = Modifier.border(1.dp, if (tone == KeyTone.Plain) p.keyLine else ink.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** A square keycap holding one icon: the composer's talk and send keys. */
@Composable
fun IconKey(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: KeyTone = KeyTone.Plain, size: Dp = 48.dp) {
    val p = LocalPalette.current
    val (fill, line, ink) = keyColors(p, tone)
    KeyBody(onClick, modifier.size(size).semantics { contentDescription = description }, fill, line, enabled = true, role = Role.Button, square = true) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
    }
}

/**
 * A system key (mono label, status ring): a latching switch on the dock. The ring fills safety orange when
 * it is on.
 */
@Composable
fun SysKey(text: String, on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier.defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f }
            .lip(p.keyShadow, !pressed)
            .clip(KeyShape).background(p.key).border(1.dp, if (on) p.ink2 else p.keyLine, KeyShape)
            .toggleable(on, interactionSource = source, indication = null, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(9.dp).clip(CircleShape).background(if (on) p.accent else Color.Transparent)
                .border(1.5.dp, if (on) p.accent else p.ink2.copy(alpha = 0.6f), CircleShape),
        )
        Text(text, style = Type.mono.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.02.em), color = p.ink)
    }
}

private data class KeyColors(val fill: Color, val line: Color, val ink: Color)

private fun keyColors(p: Palette, tone: KeyTone) = when (tone) {
    KeyTone.Plain -> KeyColors(p.key, p.keyLine, p.ink)
    KeyTone.Accent -> KeyColors(p.accent, p.accent, p.accentInk)
    KeyTone.Danger -> KeyColors(p.key, p.rec.copy(alpha = 0.6f), p.rec)
}

@Composable
private fun KeyBody(
    onClick: () -> Unit,
    modifier: Modifier,
    fill: Color,
    line: Color,
    enabled: Boolean,
    role: Role,
    square: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f }
            .lip(p.keyShadow, !pressed)
            .clip(KeyShape).background(fill).border(1.dp, line, KeyShape)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = role, onClick = onClick)
            .padding(horizontal = if (square) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        content = content,
    )
}

/** The 1px shadow line under a keycap; it disappears while the key is pressed down. */
private fun Modifier.lip(color: Color, show: Boolean) = drawBehind {
    if (show) {
        val r = 8.dp.toPx()
        drawRoundRect(color, topLeft = Offset(0f, 1.dp.toPx()), size = Size(size.width, size.height), cornerRadius = CornerRadius(r, r))
    }
}

/** A status LED. [live] pulses it (the robot HUD's `live` pip) and adds a little bloom. */
@Composable
fun Pip(color: Color, modifier: Modifier = Modifier, live: Boolean = false, size: Dp = 8.dp) {
    val alpha = if (live) {
        val t = rememberInfiniteTransition(label = "pip")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pip").value
    } else {
        1f
    }
    Box(modifier.size(size * 2), contentAlignment = Alignment.Center) {
        if (live) Box(Modifier.size(size * 2).alpha(alpha * 0.25f).clip(CircleShape).background(color))
        Box(Modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
    }
}

/** A tag plate in the reasoning-trace style: tinted fill, mono caps. [hot] fills it solid. */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier, hot: Boolean = false) {
    val p = LocalPalette.current
    Text(
        text.uppercase(),
        style = Type.labelSm.copy(letterSpacing = 0.08.em),
        color = if (hot) p.accentInk else color,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(if (hot) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/**
 * A HUD card: hairline outline, an uppercase mono header with an optional LED and right-hand meta, a divider,
 * then the body.
 */
@Composable
fun HudCard(
    title: String,
    modifier: Modifier = Modifier,
    pip: Color? = null,
    live: Boolean = false,
    meta: String? = null,
    metaColor: Color? = null,
    outline: Color? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(p.hud).border(1.dp, outline ?: p.hudLine, shape)) {
        HudHeaderRow(title, pip = pip, live = live, meta = meta, metaColor = metaColor)
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** The header strip of a HUD card, also used on its own above full-bleed lists. */
@Composable
fun HudHeaderRow(title: String, modifier: Modifier = Modifier, pip: Color? = null, live: Boolean = false, meta: String? = null, metaColor: Color? = null) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = if (pip != null) 6.dp else 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pip != null) Pip(pip, live = live)
            if (pip != null) Spacer(Modifier.size(4.dp))
            Text(title.uppercase(), style = Type.labelSm, color = p.ink, modifier = Modifier.weight(1f), maxLines = 1)
            if (meta != null) Text(meta.uppercase(), style = Type.labelSm, color = metaColor ?: p.ink2, maxLines = 1)
        }
        Rule(color = p.hudLine)
    }
}

@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = LocalPalette.current.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** A segmented control: pill well with a 4dp inset; the active segment is a solid card. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val well = RoundedCornerShape(50)
    Row(modifier.fillMaxWidth().height(48.dp).clip(well).background(p.tile).border(1.dp, p.keyLine, well).padding(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).fillMaxWidth().height(40.dp).clip(well)
                    .background(if (on) p.high else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, p.keyLine, well) else Modifier)
                    .selectable(on, role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.key, color = if (on) p.ink else p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

/**
 * A recessed input: tile fill, hairline at rest, a 2px safety-orange border when focused, no glow. [label]
 * sits above in mono caps.
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
    val shape = RoundedCornerShape(8.dp)
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
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(p.tile)
                        .border(if (focused) 2.dp else 1.dp, if (focused) p.accent else p.keyLine, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = Type.bodyLg, color = p.ink2, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/** A switch in Rivet's colours: orange track with a graphite thumb when on, a recessed well when off. */
@Composable
fun RivetSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = p.accentInk, checkedTrackColor = p.accent, checkedBorderColor = p.accent,
            uncheckedThumbColor = p.ink2, uncheckedTrackColor = p.tile, uncheckedBorderColor = p.keyLine,
        ),
    )
}

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
