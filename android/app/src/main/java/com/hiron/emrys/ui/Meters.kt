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
 * The voice key of the dock and the overlay, 64dp: a porcelain disc with a noir mic, inside a thin aurora ring.
 * [TalkState.Listening] swaps the mic for a noir stop mark,
 * and the ring breathes in the aurora with a soft ring travelling outward.
 */
@Composable
fun HaloTalkKey(state: TalkState, onClick: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val listening = state == TalkState.Listening
    val box = 64.dp
    val breath = if (listening) breathing() else 1f
    val ping = if (listening) ping() else null
    val core = 52.dp
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
            val base = 56.dp
            Box(
                Modifier.size(base).drawBehind {
                    val r = size.minDimension / 2 * (1f + ping * 0.9f)
                    val fade = 1f - ping
                    drawCircle(aurora(p, 0.55f * fade), r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                },
            )
        }
        Box(Modifier.size(62.dp).drawBehind { auroraRing(p, 62.dp, if (listening) 1.5.dp else 1.dp, if (listening) breath else 0.55f) })
        Box(
            Modifier.size(core)
                .graphicsLayer { if (pressed) { scaleX = 0.96f; scaleY = 0.96f } }
                .glow(if (listening) Glows.live.copy(color = Glows.live.color.copy(alpha = Glows.live.color.alpha * breath)) else Glows.talk, CircleShape)
                .clip(CircleShape).background(if (pressed) p.accentHi else p.ink),
            contentAlignment = Alignment.Center,
        ) {
            if (listening) {
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(p.accentInk))
            } else {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * A rounded-bar level meter in the halo. [levels] run 0 to 1; louder bars are brighter and the loudest carries a
 * soft light.
 */
@Composable
fun LevelMeter(levels: List<Float>, modifier: Modifier = Modifier, height: Dp = 56.dp) {
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
            if (i < levels.lastIndex) Spacer(Modifier.width(9.dp))
        }
    }
}

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
