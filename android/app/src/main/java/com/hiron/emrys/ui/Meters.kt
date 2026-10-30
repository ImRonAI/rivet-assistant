package com.hiron.emrys.ui

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
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
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

enum class TalkState { Idle, Listening }

/** A ring that grows from 1 to 2 times its size while fading out, repeating; null when motion is reduced. */
@Composable
private fun ping(): Float? {
    if (rememberReduceMotion()) return null
    return rememberInfiniteTransition(label = "ping").animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.PING, easing = Motion.Emphasized)), label = "ping").value
}

/** A halo ring's alpha, breathing from .55 to 1 and back; held at 1 when motion is reduced. */
@Composable
private fun breathing(): Float {
    if (rememberReduceMotion()) return 1f
    return rememberInfiniteTransition(label = "halo").animateFloat(0.55f, 1f, infiniteRepeatable(tween(Motion.BREATHE / 2, easing = Motion.Standard), RepeatMode.Reverse), label = "halo").value
}

/** The aurora, the live voice's gradient, swept round a ring: accentHi → accent → accentDeep and back. */
private fun aurora(p: Palette, alpha: Float) = Brush.sweepGradient(
    listOf(p.accentHi, p.accent, p.accentDeep, p.cy.copy(alpha = 0.9f), p.accent, p.accentHi).map { it.copy(alpha = it.alpha * alpha) },
)

/** A ring of [diameter] stroked [width] wide in the aurora at [alpha], centred in this draw scope. */
private fun DrawScope.auroraRing(p: Palette, diameter: Dp, width: Dp, alpha: Float) {
    drawCircle(aurora(p, alpha), diameter.toPx() / 2 - width.toPx() / 2, style = Stroke(width.toPx()))
}

/**
 * The voice key: a porcelain disc with a noir mic, inside a thin aurora ring. [large] is the record screen's 256dp
 * key; the small one is the 64dp dock and overlay key. [TalkState.Listening] swaps the mic for a noir stop mark,
 * and the ring breathes in the aurora with a soft ring travelling outward.
 */
@Composable
fun HaloTalkKey(state: TalkState, onClick: () -> Unit, description: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val listening = state == TalkState.Listening
    val box = if (large) 256.dp else 64.dp
    val breath = if (listening) breathing() else 1f
    val ping = if (listening) ping() else null
    val core = if (large) 112.dp else 52.dp
    Box(
        modifier.size(box)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // The light: a faint halo at rest, the aurora's bloom while live.
        Box(
            Modifier.size(box).drawBehind {
                val a = if (listening) 0.16f * breath else 0.06f
                drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = a), Color.Transparent)), size.minDimension / 2)
            },
        )
        if (ping != null) {
            val base = if (large) 160.dp else 56.dp
            Box(
                Modifier.size(base).drawBehind {
                    // The large ring stops short of the label under the key.
                    val r = size.minDimension / 2 * (1f + ping * if (large) 0.25f else 0.9f)
                    val fade = 1f - ping
                    drawCircle(aurora(p, (if (large) 0.35f else 0.55f) * fade), r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                },
            )
        }
        if (large) {
            // Two hairline orbits frame the record key at rest; the inner one is the aurora, and stays while it listens.
            if (!listening) Box(Modifier.size(208.dp).border(1.dp, p.line, CircleShape))
            Box(Modifier.size(144.dp).drawBehind { auroraRing(p, 144.dp, if (listening) 2.dp else 1.dp, if (listening) breath else 0.5f) })
        } else {
            Box(Modifier.size(62.dp).drawBehind { auroraRing(p, 62.dp, if (listening) 1.5.dp else 1.dp, if (listening) breath else 0.55f) })
        }
        Box(
            Modifier.size(core)
                .graphicsLayer { if (pressed) { scaleX = 0.96f; scaleY = 0.96f } }
                .glow(if (listening) Glows.live.copy(color = Glows.live.color.copy(alpha = Glows.live.color.alpha * breath)) else Glows.talk, CircleShape)
                .clip(CircleShape).background(if (pressed) p.accentHi else p.ink),
            contentAlignment = Alignment.Center,
        ) {
            if (listening) {
                Box(Modifier.size(if (large) 28.dp else 16.dp).clip(RoundedCornerShape(if (large) 7.dp else 4.dp)).background(p.accentInk))
            } else {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(if (large) 40.dp else 24.dp))
            }
        }
    }
}

private val GroupGaps = setOf(3, 5, 7)

/**
 * A rounded-bar level meter in the halo. [levels] run 0 to 1; louder bars are brighter and the loudest carries a
 * soft light. [grouped] uses the 12-bar pattern with wider gaps after bars 4, 6 and 8.
 */
@Composable
fun LevelMeter(levels: List<Float>, modifier: Modifier = Modifier, height: Dp = 56.dp, grouped: Boolean = true) {
    val p = LocalPalette.current
    val peak = levels.indices.maxByOrNull { levels[it] }
    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        levels.forEachIndexed { i, raw ->
            val v = raw.coerceIn(0f, 1f)
            val h by animateDpAsState(4.dp + (height - 4.dp) * v, tween(80), label = "level")
            val a = if (v < 0.45f) 0.35f else if (v < 0.75f) 0.6f else 1f
            val shape = RoundedCornerShape(50)
            Box(
                Modifier.width(4.dp).height(h)
                    .then(if (i == peak && v > 0f) Modifier.glow(Glows.peak, shape) else Modifier)
                    .clip(shape).background(Brush.verticalGradient(listOf(p.accentHi.copy(alpha = a), p.accentDeep.copy(alpha = a)))),
            )
            if (i < levels.lastIndex) Spacer(Modifier.width(if (grouped && i in GroupGaps) 22.dp else 9.dp))
        }
    }
}

/** A two-tone mini waveform: bars before [progress] in the halo, the rest graphite. */
@Composable
fun MiniWave(bars: List<Float>, progress: Float, modifier: Modifier = Modifier, height: Dp = 24.dp) {
    val p = LocalPalette.current
    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        bars.forEachIndexed { i, v ->
            val played = bars.isNotEmpty() && (i + 0.5f) / bars.size <= progress
            Box(Modifier.width(3.dp).height(3.dp + (height - 3.dp) * v.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(if (played) p.accent else p.waveOff))
        }
    }
}

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

/** Elapsed time in Geist Mono beside a breathing coral dot, and the limit when there is one ("00:14 / 00:30 max"). */
@Composable
fun TimerPill(elapsedSeconds: Int, maxSeconds: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier.clip(pill).background(p.glass).border(1.dp, p.line, pill).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalDot(p.rec, live = true)
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = p.ink)) { append(clock(elapsedSeconds)) }
                if (maxSeconds > 0) append(" / ${clock(maxSeconds)} max")
            },
            style = Type.tele, color = p.ink2, maxLines = 1,
        )
    }
}

enum class PlayStyle { Ghost, Square }

/** A play/stop key: the large haloed [PlayStyle.Ghost] disc, or the small round [PlayStyle.Square] one for list rows. */
@Composable
fun PlayKey(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, style: PlayStyle = PlayStyle.Square) {
    val p = LocalPalette.current
    val icon = if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow
    val label = if (playing) "Stop" else "Play"
    when (style) {
        PlayStyle.Ghost -> Box(
            modifier.size(80.dp)
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = if (playing) 0.16f else 0.07f), Color.Transparent), center, 60.dp.toPx()), 60.dp.toPx())
                }
                .clip(CircleShape).background(p.key).border(1.dp, if (playing) p.accent.copy(alpha = 0.5f) else p.lineHi, CircleShape)
                .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(p.accent))
            } else {
                Icon(icon, contentDescription = null, tint = p.ink, modifier = Modifier.size(34.dp))
            }
        }
        PlayStyle.Square -> Box(Modifier.minimumInteractiveComponentSize().then(modifier), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(p.key).border(1.dp, if (playing) p.accent.copy(alpha = 0.5f) else p.line, CircleShape)
                    .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = if (playing) p.accent else p.ink, modifier = Modifier.size(18.dp)) }
        }
    }
}

/**
 * The large text card for describing a voice: a leading icon, the text area, and a footer with Clear and a
 * character counter. Input stops at [max] characters. Focused, it takes a halo border, ring and caret.
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
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().heightIn(min = 190.dp).ring(p.accent.copy(alpha = 0.16f), shape, focused, gap = 2.dp)
            .clip(shape).background(p.field).border(1.dp, if (focused) p.accent.copy(alpha = 0.6f) else p.line, shape)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
            Icon(leading, contentDescription = null, tint = if (focused) p.accent else p.ink2, modifier = Modifier.padding(top = 2.dp).size(20.dp))
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
                        if (value.isEmpty()) Text(placeholder, style = Type.bodyLg, color = p.ink2.copy(alpha = 0.7f))
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
            SignalDot(p.ok)
            Spacer(Modifier.width(8.dp))
            Text("${value.length} / $max", style = Type.tele, color = p.ink2)
        }
    }
}

/** A dashed area to tap and pick a file. [picked] shows a chosen file: a sage audio mark and its name. */
@Composable
fun DropZone(title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, picked: Boolean = false) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val dash = if (pressed) p.accent.copy(alpha = 0.7f) else p.lineHi
    Column(
        modifier.fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    dash, cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .clip(RoundedCornerShape(20.dp)).background(if (pressed) p.edgeSoft else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (picked) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(p.ok.copy(alpha = 0.10f)).border(1.dp, p.ok.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AudioFile, contentDescription = null, tint = p.ok, modifier = Modifier.size(22.dp)) }
        } else {
            Box(
                Modifier.size(48.dp).glow(Glows.disc, CircleShape).clip(CircleShape).background(p.edgeSoft).border(1.dp, p.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = p.ink2, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.height(14.dp))
        if (picked) {
            Text(title, style = Type.title, color = p.ink, textAlign = TextAlign.Center)
        } else {
            CapsText(title, Type.title, p.ink, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
        Text(sub, style = Type.cardBody, color = p.ink2, textAlign = TextAlign.Center)
    }
}
