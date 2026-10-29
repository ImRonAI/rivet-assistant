package com.hiron.rivet.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The robot's stage: a radial backdrop, a still turntable that shows until the robot page has loaded (and under
 * Robolectric), and an optional [hint] in a glass pill at the bottom. [framed] puts it on a card. The WebView goes
 * in [content] and draws over the backdrop once its page is up.
 */
@Composable
fun StageFrame(
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    hint: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.testTag(STAGE_TAG)
            .then(if (framed) Modifier.clip(shape).border(1.dp, p.edge, shape) else Modifier.clipToBounds())
            .background(p.bg)
            .then(if (framed) Modifier.topShade() else Modifier),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            backdrop(p.raised, p.bg)
            turntable(p.card, p.ink)
        }
        content()
        if (hint != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    .background(p.glass, CircleShape).border(1.dp, p.edge, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, tint = p.ink2, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(hint.uppercase(), style = Type.labelSm.copy(letterSpacing = 0.14.em), color = p.ink2, maxLines = 1)
            }
        }
    }
}

/** A dark line of shade inside the top edge of a framed stage, as if it were set into the screen. */
private fun Modifier.topShade() = drawWithContent {
    drawContent()
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent), endY = 20.dp.toPx()), size = Size(size.width, 20.dp.toPx()))
}

/** radial-gradient(60% 55% at 50% 42%, [from] → [to]). */
private fun DrawScope.backdrop(from: Color, to: Color) {
    val c = Offset(size.width * 0.5f, size.height * 0.42f)
    val rx = size.width * 0.6f
    val ry = size.height * 0.55f
    scale(1f, ry / rx, pivot = c) {
        drawCircle(Brush.radialGradient(listOf(from, to), center = c, radius = rx), radius = rx, center = c)
    }
}

/** The still turntable: a disc, two rings and 72 ticks, seen from a low pitch. */
private fun DrawScope.turntable(disc: Color, ink: Color) {
    val rx = min(size.width * 0.42f, size.height * 0.9f)
    val ry = rx * 0.26f
    val c = Offset(size.width * 0.5f, size.height - ry - size.height * 0.1f)
    drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), center = c + Offset(0f, ry * 0.3f), radius = rx * 1.1f), topLeft = Offset(c.x - rx * 1.1f, c.y - ry * 1.1f), size = Size(rx * 2.2f, ry * 2.6f))
    drawOval(disc, topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2, ry * 2))
    drawOval(ink.copy(alpha = 0.09f), topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2, ry * 2), style = Stroke(1.dp.toPx()))
    listOf(0.72f, 0.37f).forEach { k ->
        drawOval(ink.copy(alpha = 0.09f), topLeft = Offset(c.x - rx * k, c.y - ry * k), size = Size(rx * 2 * k, ry * 2 * k), style = Stroke(1.dp.toPx()))
    }
    val tick = ink.copy(alpha = 0.30f)
    for (i in 0 until 72) {
        val a = i * 2 * PI / 72
        val inner = if (i % 6 == 0) 0.84f else 0.91f
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(
            tick,
            Offset(c.x + dx * rx * inner, c.y + dy * ry * inner),
            Offset(c.x + dx * rx * 0.97f, c.y + dy * ry * 0.97f),
            strokeWidth = if (i % 6 == 0) 1.2.dp.toPx() else 0.8.dp.toPx(),
        )
    }
}

/** Pill-dot step indicator with "STEP 0n / 0N" on the right. */
@Composable
fun StepIndicator(step: Int, total: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 1..total) {
                val on = i == step
                val w by animateDpAsState(if (on) 20.dp else 6.dp, tween(Motion.BASE, easing = Motion.Emphasized), label = "step")
                Box(Modifier.width(w).height(6.dp).clip(RoundedCornerShape(50)).background(if (on) p.accent else p.dot))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("STEP %02d / %02d".format(step, total), style = Type.labelMd, color = p.ink2)
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
