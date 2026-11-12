package com.hiron.ren.ui

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

/**
 * The voice key of the dock and the overlay: a volt disc with a mic. [TalkState.Listening] turns it danger red with a
 * stop mark inside a thin ring, so a live microphone is never in doubt.
 */
@Composable
fun TalkKey(state: TalkState, onClick: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val listening = state == TalkState.Listening
    val fill = if (listening) p.danger else if (pressed) p.accentHi else p.accent
    Box(
        modifier.size(64.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(52.dp).ring(p.danger.copy(alpha = 0.4f), CircleShape, listening, gap = 3.dp, width = 2.dp).clip(CircleShape).background(fill),
            contentAlignment = Alignment.Center,
        ) {
            if (listening) {
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            } else {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** A rounded-bar level meter in volt. [levels] run 0 to 1; louder bars are stronger. */
@Composable
fun LevelMeter(levels: List<Float>, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    val p = LocalPalette.current
    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        levels.forEachIndexed { i, raw ->
            val v = raw.coerceIn(0f, 1f)
            val h by animateDpAsState(4.dp + (height - 4.dp) * v, tween(80), label = "level")
            val a = if (v < 0.45f) 0.35f else if (v < 0.75f) 0.6f else 1f
            Box(Modifier.width(4.dp).height(h).clip(RoundedCornerShape(50)).background(p.accent.copy(alpha = a)))
            if (i < levels.lastIndex) Spacer(Modifier.width(9.dp))
        }
    }
}

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
