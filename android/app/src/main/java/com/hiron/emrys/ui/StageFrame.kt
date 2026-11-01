package com.hiron.emrys.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hiron.emrys.core.Activity
import kotlin.math.min

/**
 * The robot's stage: a noir window with a soft spotlight and a pool of light on the floor ringed by two faint
 * circles, shown until the robot page has loaded (and under Robolectric), and an optional [hint] in a glass pill at
 * the bottom. [framed] sets it in a [radius]-cornered window behind a 1dp hairline. The WebView goes in [content]
 * and draws over the backdrop once its page is up.
 */
@Composable
fun StageFrame(
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    hint: String? = null,
    radius: Dp = 28.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.testTag(STAGE_TAG)
            .then(if (framed) Modifier.clip(shape).border(1.dp, p.line, shape) else Modifier.clipToBounds())
            .background(p.bg),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            spotlight(p.ink)
            floorRings(p.ink)
        }
        content()
        if (hint != null) {
            // The pill's insets and the icon match Onboarding's check that the hint fits on one line.
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .background(p.glass, CircleShape).border(1.dp, p.line, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, tint = p.ink2, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(hint, style = Type.labelSm, color = p.ink2, maxLines = 1)
            }
        }
    }
}

/** A soft spotlight falling from above the stage: a tall, faint porcelain ellipse centred high. */
private fun DrawScope.spotlight(ink: Color) {
    val c = Offset(size.width * 0.5f, size.height * 0.30f)
    val rx = size.width * 0.55f
    val ry = size.height * 0.75f
    scale(1f, ry / rx, pivot = c) {
        drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.055f), ink.copy(alpha = 0.02f), Color.Transparent), center = c, radius = rx), radius = rx, center = c)
    }
}

/** The pool of light the robot stands in, seen from a low pitch: a soft porcelain wash and two faint concentric rings. */
private fun DrawScope.floorRings(ink: Color) {
    val rx = min(size.width * 0.36f, size.height * 0.8f)
    val ry = rx * 0.22f
    val c = Offset(size.width * 0.5f, size.height - ry - size.height * 0.12f)
    scale(1f, ry / rx, pivot = c) {
        drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.07f), Color.Transparent), center = c, radius = rx * 1.15f), radius = rx * 1.15f, center = c)
    }
    drawOval(ink.copy(alpha = 0.10f), topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2, ry * 2), style = Stroke(1.dp.toPx()))
    val k = 0.62f
    drawOval(ink.copy(alpha = 0.06f), topLeft = Offset(c.x - rx * k, c.y - ry * k), size = Size(rx * 2 * k, ry * 2 * k), style = Stroke(1.dp.toPx()))
}

/** The step dots, the current one a short porcelain capsule, with "Step 2 of 4" on the right. */
@Composable
fun StepIndicator(step: Int, total: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 1..total) {
                val on = i == step
                val w by animateDpAsState(if (on) 18.dp else 5.dp, tween(Motion.BASE, easing = Motion.Emphasized), label = "step")
                val c by animateColorAsState(if (on) p.ink else p.dot, tween(Motion.BASE), label = "step-colour")
                Box(Modifier.width(w).height(5.dp).clip(RoundedCornerShape(50)).background(c))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Step $step of $total", style = Type.labelLg.copy(fontFeatureSettings = "tnum"), color = p.ink2)
    }
}

/** The bottom fade that holds a screen's keys over its scrolling content. */
@Composable
fun FadeFooter(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to p.bg.copy(alpha = 0.95f), 1f to p.bg))
            .padding(top = 32.dp, start = 20.dp, end = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
