package com.hiron.ren.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/** The status colours a component can take. */
enum class Tone { Accent, Info, Green, Amber, Red, Neutral }

@Composable
fun Tone.color(): Color {
    val p = LocalPalette.current
    return when (this) {
        Tone.Accent -> p.accent
        Tone.Info -> p.info
        Tone.Green -> p.ok
        Tone.Amber -> p.amb
        Tone.Red -> p.danger
        Tone.Neutral -> p.ink2
    }
}

/** A ring [width] wide, [gap] outside [shape]: the focus ring of fields and the composer card. */
internal fun Modifier.ring(color: Color, shape: Shape, show: Boolean, gap: Dp = 0.dp, width: Dp = 1.dp): Modifier = drawBehind {
    if (!show) return@drawBehind
    val g = gap.toPx() + width.toPx() / 2
    val outline = shape.createOutline(Size(size.width + g * 2, size.height + g * 2), layoutDirection, this)
    translate(-g, -g) { drawOutline(outline, color, style = Stroke(width.toPx())) }
}

/**
 * Text with a band of light sweeping across it: how Ren shows it is working. With motion reduced the text holds
 * still in secondary ink.
 */
@Composable
fun ShimmerText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    if (rememberReduceMotion()) {
        Text(text, modifier, style = style, color = p.ink2, maxLines = 1)
        return
    }
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)), label = "shimmer")
    val brush = remember(sweep, p) {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader = LinearGradientShader(
                Offset(size.width * (sweep - 0.5f), 0f), Offset(size.width * (sweep + 0.5f), 0f), listOf(p.muted, p.ink, p.muted),
            )
        }
    }
    Text(text, modifier, style = style.copy(brush = brush), maxLines = 1)
}

/** One pass of the shimmer, as ren desktop's `shimmer` animation runs it. */
private const val SHIMMER_MS = 2000

/** The status dot: [size] across in [color]. [live] sets it in a soft ring of its own colour; nothing pulses. */
@Composable
internal fun SignalDot(color: Color, modifier: Modifier = Modifier, live: Boolean = false, size: Dp = 6.dp) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        if (live) drawCircle(color.copy(alpha = 0.22f), r * 2f)
        drawCircle(color, r)
    }
}

/**
 * Upper-cased tag-label text, the one uppercase style. Screen readers and tests get [text] as written, so a label
 * written "Voice configuration" shows "VOICE CONFIGURATION" and is announced as written.
 */
@Composable
private fun EyebrowText(text: String, color: Color, modifier: Modifier = Modifier, style: TextStyle = Type.eyebrow) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text.uppercase(locale), style = style, color = color, maxLines = 1,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
    )
}

private const val HALO_STOP = "volt-stop"

/**
 * The volt dot, the logotype's full stop, ending a one-word line or a hero heading in place of punctuation. It is read
 * as [spoken] (a full stop by default), so the line still announces as a sentence.
 */
internal fun AnnotatedString.Builder.appendVoltStop(spoken: String = ".") = appendInlineContent(HALO_STOP, spoken)

/** The inline content that draws [appendVoltStop]: a volt dot sitting on the baseline. */
@Composable
internal fun voltStopContent(): Map<String, InlineTextContent> {
    val p = LocalPalette.current
    return remember(p) {
        mapOf(
            HALO_STOP to InlineTextContent(Placeholder(0.26.em, 0.42.em, PlaceholderVerticalAlign.AboveBaseline)) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.width * 0.36f
                    drawCircle(p.accent, r, Offset(size.width * 0.56f, size.height - r))
                }
            },
        )
    }
}

/** The tag behind [StatusTag] and [Tag]: a status dot and a short label. [outline] quietens the label; [hot] sets it on a soft wash of [color]. */
@Composable
internal fun TagPlate(text: String, color: Color, modifier: Modifier = Modifier, outline: Boolean = false, hot: Boolean = false) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier.then(if (hot) Modifier.clip(pill).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalDot(color, size = 6.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.tag.copy(fontSize = 11.sp, lineHeight = 14.sp), color = if (outline) p.ink2 else p.ink, maxLines = 1)
    }
}

/** A status tag: a dot in the tone's colour and a short label. [outline] gives it a quieter, secondary label. */
@Composable
fun StatusTag(text: String, tone: Tone, modifier: Modifier = Modifier, outline: Boolean = false) {
    TagPlate(text, tone.color(), modifier, outline = outline)
}

/**
 * A tag label: mono capitals in muted ink. With [dot], [Tone.Accent] leads it with the volt `//` and any other tone
 * with a dot in its colour.
 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Accent, dot: Boolean = true) {
    val p = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (dot && tone == Tone.Accent) {
            Text("//", style = Type.eyebrow.copy(letterSpacing = 0.em), color = p.accent, modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.width(6.dp))
        } else if (dot) {
            SignalDot(tone.color())
            Spacer(Modifier.width(8.dp))
        }
        EyebrowText(text, p.muted)
    }
}

/**
 * The card every surface is built from: a flat fill behind a 1dp hairline at the card radius. [selected] draws a volt
 * border; [onClick] makes it pressable (its fill and border darken while pressed).
 */
@Composable
fun RenCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    radius: Dp = Radius.card,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val down = pressed && onClick != null
    Column(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(if (down) p.cardHi else p.card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) p.accent else if (down) p.lineHi else p.line, shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, role = role, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/**
 * A square icon well. The icon sits in [Tone.Neutral] grey; a status tone (green, amber, red) tints it, while the
 * other tones stay ink. [selected] fills the well with a volt wash.
 */
@Composable
fun IconWell(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    selected: Boolean = false,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    val p = LocalPalette.current
    val tint = when {
        selected -> p.accent
        tone == Tone.Green || tone == Tone.Amber || tone == Tone.Red -> tone.color()
        tone == Tone.Neutral -> p.ink2
        else -> p.ink
    }
    val shape = RoundedCornerShape(Radius.control)
    Box(
        modifier.size(size).clip(shape).background(if (selected) p.accentSoft else p.tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** A quiet note on a faint wash, led by a small icon in its tone. */
@Composable
fun InfoStrip(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info, tone: Tone = Tone.Neutral) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(Radius.control)
    Row(
        modifier.fillMaxWidth().clip(shape).background(p.tile).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.cardBody, color = p.ink2)
    }
}

/** The trust line at the foot of a screen: a small icon and a quiet line of text, centred together as one group. */
@Composable
fun TrustFooter(lines: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.VerifiedUser, tone: Tone = Tone.Neutral) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone.color().copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(lines, style = Type.cardBody, color = p.ink2, modifier = Modifier.widthIn(max = 290.dp))
    }
}

/** Entrance: fade in and rise 8dp, delayed by [index] steps; runs once for the composable's lifetime. */
fun Modifier.enter(index: Int): Modifier = composed {
    val reduce = rememberReduceMotion()
    val t = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(Motion.SLOW, delayMillis = index * Motion.STAGGER, easing = Motion.Emphasized))
    }
    graphicsLayer {
        alpha = t.value
        translationY = (1f - t.value) * 8.dp.toPx()
    }
}
