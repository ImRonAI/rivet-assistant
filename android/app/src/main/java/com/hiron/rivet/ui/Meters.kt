package com.hiron.rivet.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Mic
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class TalkState { Idle, Listening }

/** A ring that grows from 1 to 2 times its size while fading out, repeating; null when motion is reduced. */
@Composable
private fun ping(): Float? {
    if (rememberReduceMotion()) return null
    return rememberInfiniteTransition(label = "ping").animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.PING, easing = Motion.Emphasized)), label = "ping").value
}

@Composable
private fun breathing(): Float {
    if (rememberReduceMotion()) return 1f
    return rememberInfiniteTransition(label = "halo").animateFloat(0.55f, 1f, infiniteRepeatable(tween(Motion.BREATHE / 2, easing = Motion.Standard), RepeatMode.Reverse), label = "halo").value
}

/**
 * The concentric halo talk key. [large] is the record screen's 256dp key; the small one is the 64dp dock and
 * overlay key. [TalkState.Listening] swaps the mic for a stop mark on a live orange core with a ping ring.
 */
@Composable
fun HaloTalkKey(state: TalkState, onClick: () -> Unit, description: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val listening = state == TalkState.Listening
    val box = if (large) 256.dp else 64.dp
    val glowAlpha = breathing()
    val ping = if (listening) ping() else null
    val pulse = pulse(listening)
    Box(
        modifier.size(box)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(box).background(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.10f), Color.Transparent))))
        if (ping != null) {
            val base = if (large) 160.dp else 52.dp
            val ring = p.accent.copy(alpha = if (large) 0.10f else 0.5f)
            val fill = p.accent.copy(alpha = if (large) 0.10f else 0.15f)
            Box(
                Modifier.size(base).drawBehind {
                    // The large ring stops short of the label under the key.
                    val r = size.minDimension / 2 * (1f + ping * if (large) 0.25f else 1f)
                    val fade = 0.9f * (1f - ping)
                    drawCircle(fill.copy(alpha = fill.alpha * fade), r)
                    drawCircle(ring.copy(alpha = ring.alpha * fade), r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                },
            )
        }
        if (large && !listening) {
            Box(Modifier.size(208.dp).alpha(glowAlpha).border(1.dp, p.accent.copy(alpha = 0.20f), CircleShape))
            Box(Modifier.size(160.dp).border(1.dp, p.edge, CircleShape))
        }
        if (large && listening) {
            Box(Modifier.size(128.dp).alpha(pulse).background(p.accent.copy(alpha = 0.15f), CircleShape).border(1.dp, p.accent.copy(alpha = 0.30f), CircleShape))
        }
        if (!large) Box(Modifier.size(60.dp).alpha(glowAlpha).border(1.dp, p.accent.copy(alpha = 0.25f), CircleShape))
        val core = when {
            large && listening -> 80.dp
            large -> 112.dp
            else -> 52.dp
        }
        val coreFill = when {
            listening -> Brush.linearGradient(listOf(p.accent, p.accentDeep))
            large -> Brush.radialGradient(listOf(Color(0xFF2A1712), Color(0xFF1A110E)))
            else -> SolidColor(if (pressed) p.accentHi else p.accent)
        }
        Box(
            Modifier.size(core)
                .graphicsLayer { if (pressed) { scaleX = 0.96f; scaleY = 0.96f } }
                .glow(if (listening) Glows.live else if (large) Glows.talk else Glows.cta.copy(radius = 12.dp), CircleShape)
                .clip(CircleShape).background(coreFill)
                .then(if (large && !listening) Modifier.border(1.dp, p.accent.copy(alpha = 0.35f), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (listening) {
                Box(Modifier.size(if (large) 24.dp else 18.dp).clip(RoundedCornerShape(if (large) 6.dp else 4.dp)).background(p.white), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (large) 10.dp else 8.dp).background(p.accent))
                }
            } else {
                Icon(
                    Icons.Outlined.Mic, contentDescription = null, tint = if (large) p.ink else p.accentInk,
                    modifier = Modifier.size(if (large) 44.dp else 24.dp),
                )
            }
        }
    }
}

private val GroupGaps = setOf(3, 5, 7)

/**
 * A rounded-bar level meter. [levels] run 0 to 1; louder bars are brighter and the loudest glows. [grouped] uses
 * the 12-bar pattern with wider gaps after bars 4, 6 and 8.
 */
@Composable
fun LevelMeter(levels: List<Float>, modifier: Modifier = Modifier, height: Dp = 56.dp, grouped: Boolean = true) {
    val p = LocalPalette.current
    val peak = levels.indices.maxByOrNull { levels[it] }
    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        levels.forEachIndexed { i, raw ->
            val v = raw.coerceIn(0f, 1f)
            val h by animateDpAsState(6.dp + (height - 6.dp) * v, tween(80), label = "level")
            val c = p.accent.copy(alpha = if (v < 0.45f) 0.4f else if (v < 0.75f) 0.6f else 1f)
            val shape = RoundedCornerShape(3.dp)
            Box(
                Modifier.width(6.dp).height(h)
                    .then(if (i == peak && v > 0f) Modifier.glow(Glows.peak, shape) else Modifier)
                    .clip(shape).background(c),
            )
            if (i < levels.lastIndex) Spacer(Modifier.width(if (grouped && i in GroupGaps) 24.dp else 8.dp))
        }
    }
}

/** A two-tone mini waveform: bars before [progress] are orange, the rest graphite. */
@Composable
fun MiniWave(bars: List<Float>, progress: Float, modifier: Modifier = Modifier, height: Dp = 24.dp) {
    val p = LocalPalette.current
    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        bars.forEachIndexed { i, v ->
            val played = bars.isNotEmpty() && (i + 0.5f) / bars.size <= progress
            Box(Modifier.width(4.dp).height(3.dp + (height - 3.dp) * v.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(if (played) p.accent else p.waveOff))
        }
    }
}

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

/** Elapsed time with a pulsing red-orange dot, and the limit when there is one ("00:14 / 00:30 MAX"). */
@Composable
fun TimerPill(elapsedSeconds: Int, maxSeconds: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier.clip(pill).background(Color.White.copy(alpha = 0.04f)).border(1.dp, p.edge, pill).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val a = pulse(low = 0.4f, periodMs = 1200)
        Box(Modifier.size(8.dp).alpha(a).border(1.dp, p.accent.copy(alpha = 0.5f), CircleShape).padding(2.dp).clip(CircleShape).background(p.accent))
        Spacer(Modifier.width(6.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = p.accent)) { append(clock(elapsedSeconds)) }
                if (maxSeconds > 0) append(" / ${clock(maxSeconds)} MAX")
            },
            style = Type.labelMd.copy(fontFeatureSettings = "tnum"), color = p.ink2, maxLines = 1,
        )
    }
}

enum class PlayStyle { Ghost, Square }

/** A play/stop key: the big haloed [PlayStyle.Ghost] key, or the small [PlayStyle.Square] one for list rows. */
@Composable
fun PlayKey(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, style: PlayStyle = PlayStyle.Square) {
    val p = LocalPalette.current
    val icon = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow
    val label = if (playing) "Stop" else "Play"
    when (style) {
        PlayStyle.Ghost -> Box(
            modifier.size(80.dp)
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.15f), Color.Transparent), center, 60.dp.toPx()), 60.dp.toPx())
                }
                .clip(CircleShape).background(Color(0xFF171C22)).border(1.dp, p.edge, CircleShape)
                .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(p.accent))
            } else {
                Icon(icon, contentDescription = null, tint = p.accent, modifier = Modifier.size(36.dp))
            }
        }
        PlayStyle.Square -> Box(Modifier.minimumInteractiveComponentSize().then(modifier), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.size(36.dp).clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, if (playing) p.accent.copy(alpha = 0.5f) else p.edge, shape)
                    .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = if (playing) p.accent else p.ink, modifier = Modifier.size(18.dp)) }
        }
    }
}

/**
 * The large text card for describing a voice: a leading icon, the text area, and a footer with Clear and a
 * character counter. Input stops at [max] characters.
 */
@Composable
fun ComposerCard(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    max: Int,
    modifier: Modifier = Modifier,
    leading: ImageVector = Icons.Outlined.Mic,
    fieldModifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().heightIn(min = 190.dp).ring(p.accent.copy(alpha = 0.3f), shape, focused)
            .clip(shape).background(p.field).border(1.dp, if (focused) p.accent.copy(alpha = 0.6f) else p.edge, shape)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
            Icon(leading, contentDescription = null, tint = p.accent, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.take(max)) },
                modifier = fieldModifier.weight(1f),
                textStyle = Type.bodyLg.copy(color = p.ink),
                cursorBrush = SolidColor(p.accent),
                interactionSource = source,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = Type.bodyLg, color = p.ink2.copy(alpha = 0.6f))
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Rule(color = p.line)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextKey("Clear", { onValueChange("") }, Modifier.padding(start = 0.dp), leading = Icons.Outlined.Close)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(6.dp).clip(CircleShape).background(p.ok))
            Spacer(Modifier.width(8.dp))
            Text("${value.length} / $max", style = Type.labelMd.copy(fontFeatureSettings = "tnum"), color = p.ink2)
        }
    }
}

/** A dashed area to tap and pick a file. [picked] shows a chosen file: an audio mark in green and its name. */
@Composable
fun DropZone(title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, picked: Boolean = false) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val dash = if (pressed) p.accent else p.graphite
    Column(
        modifier.fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    dash, cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clip(RoundedCornerShape(12.dp)).background(p.bg.copy(alpha = 0.6f))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (picked) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(p.ok.copy(alpha = 0.12f)).border(1.dp, p.ok.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AudioFile, contentDescription = null, tint = p.ok, modifier = Modifier.size(22.dp)) }
        } else {
            Box(
                Modifier.size(48.dp).glow(Glows.disc, CircleShape).clip(CircleShape).background(p.tile).border(1.dp, p.graphite, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = p.accent, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        if (picked) {
            Text(title, style = Type.title, color = p.ink, textAlign = TextAlign.Center)
        } else {
            CapsText(title, Type.monoKey, p.ink, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
        Text(sub, style = Type.cardBody.copy(fontSize = 12.sp), color = p.ink2, textAlign = TextAlign.Center)
    }
}
