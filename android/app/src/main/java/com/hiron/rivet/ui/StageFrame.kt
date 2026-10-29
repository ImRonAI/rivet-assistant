package com.hiron.rivet.ui

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
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The robot's stage: a radial backdrop, a still turntable that shows until the robot page has loaded (and under
 * Robolectric), an optional corner [caption] and bottom [hint]. [framed] puts it on a card. [mood] lights the
 * floor in that colour. The WebView goes in [content] and draws over the backdrop once its page is up.
 */
@Composable
fun StageFrame(
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    caption: String? = null,
    hint: String? = null,
    mood: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    val breath = if (mood != null && !rememberReduceMotion()) {
        rememberInfiniteTransition(label = "mood").animateFloat(0.7f, 1f, infiniteRepeatable(tween(1400, easing = Motion.Standard), RepeatMode.Reverse), label = "mood").value
    } else {
        1f
    }
    Box(
        modifier.testTag(STAGE_TAG)
            .then(if (framed) Modifier.clip(shape).border(1.dp, p.edge, shape) else Modifier.clipToBounds())
            .background(p.bg)
            .then(if (framed) Modifier.topShade() else Modifier),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            backdrop(p.raised, p.bg)
            if (mood != null) floorGlow(mood.copy(alpha = 0.18f * breath))
            turntable(p.card, p.ink)
        }
        content()
        if (caption != null) {
            Text(
                caption, style = Type.labelSm.copy(fontSize = 9.sp, letterSpacing = 0.14.em), color = p.ink2.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            )
        }
        if (hint != null) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).alpha(0.6f), verticalAlignment = Alignment.CenterVertically) {
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

/** The mood light on the floor: an ellipse 38% × 34% at 50% 70%. */
private fun DrawScope.floorGlow(color: Color) {
    val c = Offset(size.width * 0.5f, size.height * 0.7f)
    val rx = size.width * 0.38f
    val ry = size.height * 0.34f
    scale(1f, ry / rx, pivot = c) {
        drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = c, radius = rx), radius = rx, center = c)
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

/**
 * The sticky glass header of the voice setup: back (or a text action), the step counter, and on the right the
 * ElevenLabs badge and a voice mark.
 */
@Composable
fun StickyHeader(
    counter: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    startText: Pair<String, () -> Unit>? = null,
    badge: Boolean = true,
) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().background(p.glass).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                onBack != null -> Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = p.ink, modifier = Modifier.size(22.dp)) }
                startText != null -> TextKey(startText.first, startText.second)
            }
            Spacer(Modifier.width(6.dp))
            Text(counter, style = Type.eyebrow, color = p.ink2, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (badge) {
                val small = RoundedCornerShape(4.dp)
                Text(
                    "ELEVENLABS", style = Type.labelSm.copy(letterSpacing = 0.1.em), color = p.ink2,
                    modifier = Modifier.clip(small).background(p.edgeSoft).border(1.dp, p.edge, small).padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(p.raised).border(1.dp, p.edge, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        Rule(color = p.line)
    }
}

/** The bottom fade that holds a screen's keys over its scrolling content. */
@Composable
fun FadeFooter(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to p.bg.copy(alpha = 0.95f), 1f to p.bg))
            .padding(top = 24.dp, start = 20.dp, end = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
