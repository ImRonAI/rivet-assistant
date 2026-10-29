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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * The concentric halo talk key of the dock and the overlay, 64dp. [TalkState.Listening] swaps the mic for a stop
 * mark on a live orange core with a ping ring.
 */
@Composable
fun HaloTalkKey(state: TalkState, onClick: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val listening = state == TalkState.Listening
    val glowAlpha = breathing()
    val ping = if (listening) ping() else null
    Box(
        modifier.size(64.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(64.dp).background(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.10f), Color.Transparent))))
        if (ping != null) {
            val ring = p.accent.copy(alpha = 0.5f)
            val fill = p.accent.copy(alpha = 0.15f)
            Box(
                Modifier.size(52.dp).drawBehind {
                    val r = size.minDimension / 2 * (1f + ping)
                    val fade = 0.9f * (1f - ping)
                    drawCircle(fill.copy(alpha = fill.alpha * fade), r)
                    drawCircle(ring.copy(alpha = ring.alpha * fade), r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                },
            )
        }
        Box(Modifier.size(60.dp).alpha(glowAlpha).border(1.dp, p.accent.copy(alpha = 0.25f), CircleShape))
        val coreFill = if (listening) Brush.linearGradient(listOf(p.accent, p.accentDeep)) else SolidColor(if (pressed) p.accentHi else p.accent)
        Box(
            Modifier.size(52.dp)
                .graphicsLayer { if (pressed) { scaleX = 0.96f; scaleY = 0.96f } }
                .glow(if (listening) Glows.live else Glows.cta.copy(radius = 12.dp), CircleShape)
                .clip(CircleShape).background(coreFill),
            contentAlignment = Alignment.Center,
        ) {
            if (listening) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(p.white), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(p.accent))
                }
            } else {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** A rounded-bar level meter. [levels] run 0 to 1; louder bars are brighter and the loudest glows. */
@Composable
fun LevelMeter(levels: List<Float>, modifier: Modifier = Modifier, height: Dp = 56.dp) {
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
            if (i < levels.lastIndex) Spacer(Modifier.width(8.dp))
        }
    }
}

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

/** Elapsed time with a pulsing red-orange dot. */
@Composable
fun TimerPill(elapsedSeconds: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier.clip(pill).background(Color.White.copy(alpha = 0.04f)).border(1.dp, p.edge, pill).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val a = pulse(low = 0.4f, periodMs = 1200)
        Box(Modifier.size(8.dp).alpha(a).border(1.dp, p.accent.copy(alpha = 0.5f), CircleShape).padding(2.dp).clip(CircleShape).background(p.accent))
        Spacer(Modifier.width(6.dp))
        Text(clock(elapsedSeconds), style = Type.labelMd.copy(fontFeatureSettings = "tnum"), color = p.accent, maxLines = 1)
    }
}
