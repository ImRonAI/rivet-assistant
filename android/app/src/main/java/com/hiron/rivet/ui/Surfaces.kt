package com.hiron.rivet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/** The status colours a component can take. */
enum class Tone { Accent, Cyan, Green, Amber, Red, Neutral }

@Composable
fun Tone.color(): Color {
    val p = LocalPalette.current
    return when (this) {
        Tone.Accent -> p.accent
        Tone.Cyan -> p.cy
        Tone.Green -> p.ok
        Tone.Amber -> p.amb
        Tone.Red -> p.rec
        Tone.Neutral -> p.ink2
    }
}

data class GlowSpec(val color: Color, val radius: Dp, val offsetY: Dp)

/** The glow presets. Rivet is dark only, so they are fixed colours. */
object Glows {
    private val accent = DarkPalette.accent
    val cta = GlowSpec(accent.copy(alpha = 0.35f), 24.dp, 0.dp)
    val ctaDrop = GlowSpec(accent.copy(alpha = 0.30f), 15.dp, 10.dp)
    val talk = GlowSpec(accent.copy(alpha = 0.45f), 36.dp, 12.dp)
    val live = GlowSpec(accent.copy(alpha = 0.55f), 36.dp, 0.dp)
    val peak = GlowSpec(accent.copy(alpha = 0.60f), 12.dp, 0.dp)
    val selected = GlowSpec(accent.copy(alpha = 0.08f), 24.dp, 0.dp)
    val disc = GlowSpec(accent.copy(alpha = 0.10f), 15.dp, 10.dp)
}

/** A soft light around [shape] drawn behind the content: ten stacked, fading outlines (works everywhere, Robolectric included). */
fun Modifier.glow(color: Color, radius: Dp, shape: Shape = CircleShape, offsetY: Dp = 0.dp): Modifier = drawBehind {
    val r = radius.toPx()
    val dy = offsetY.toPx()
    for (i in 1..10) {
        val grow = r * i / 10f
        val a = color.alpha * 0.22f * (1 - i / 10f).pow(1.6f)
        if (a <= 0f) continue
        val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
        translate(-grow, -grow + dy) { drawOutline(outline, color.copy(alpha = a)) }
    }
}

fun Modifier.glow(spec: GlowSpec, shape: Shape): Modifier = glow(spec.color, spec.radius, shape, spec.offsetY)

/** A 1dp ring [gap] outside [shape]: the focus halo of fields and the composer card. */
internal fun Modifier.ring(color: Color, shape: Shape, show: Boolean, gap: Dp = 1.dp): Modifier = drawBehind {
    if (!show) return@drawBehind
    val g = gap.toPx() + 0.5.dp.toPx()
    val outline = shape.createOutline(Size(size.width + g * 2, size.height + g * 2), layoutDirection, this)
    translate(-g, -g) { drawOutline(outline, color, style = Stroke(1.dp.toPx())) }
}

/** An opacity that pulses between 1 and [low]; held at 1 when [on] is false or motion is reduced. */
@Composable
internal fun pulse(on: Boolean = true, low: Float = 0.5f, periodMs: Int = Motion.PULSE): Float {
    if (!on || rememberReduceMotion()) return 1f
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(1f, low, infiniteRepeatable(tween(periodMs / 2, easing = Motion.Standard), RepeatMode.Reverse), label = "pulse").value
}

/** The tag plate behind [StatusTag] and [Tag]. */
@Composable
internal fun TagPlate(text: String, color: Color, modifier: Modifier = Modifier, outline: Boolean = false, hot: Boolean = false) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(4.dp)
    val fill = when {
        hot -> color
        outline -> Color.Transparent
        else -> color.copy(alpha = 0.10f)
    }
    Text(
        text.uppercase(),
        style = Type.tag,
        color = if (hot) p.accentInk else color,
        maxLines = 1,
        modifier = modifier.clip(shape).background(fill)
            .border(1.dp, color.copy(alpha = if (outline) 0.35f else 0.20f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 9sp mono uppercase tag: tone at 10% fill, 20% border. [outline] drops the fill for a quieter tag. */
@Composable
fun StatusTag(text: String, tone: Tone, modifier: Modifier = Modifier, outline: Boolean = false) {
    TagPlate(text, tone.color(), modifier, outline = outline)
}

/** A small tone dot and a spaced mono label in the tone's colour. [live] pulses the dot. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Accent, dot: Boolean = true, live: Boolean = false) {
    val c = tone.color()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            val a = pulse(live, low = 0.35f, periodMs = 1200)
            Box(Modifier.size(6.dp).alpha(a).glow(c.copy(alpha = if (live) 0.8f else 0.4f), 5.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.eyebrow, color = c, maxLines = 1)
    }
}

/** Big Shoulders hero line ending in an accent full stop (the period is appended; do not include it in [text]). */
@Composable
fun HeroTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.hero) {
    val p = LocalPalette.current
    Text(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = p.accent)) { append(".") }
        },
        style = style, color = p.ink, modifier = modifier,
    )
}

/** Onboarding headline row: a pulsing accent dot and the step headline, centred. */
@Composable
fun StepHeadline(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(8.dp).alpha(pulse()).glow(Glows.peak, CircleShape).clip(CircleShape).background(p.accent))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.step, color = p.ink, textAlign = TextAlign.Center)
    }
}

/** A muted mono section label with an optional right-aligned note ("4 WAYS", "OPTIONAL"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.eyebrow, color = p.ink2, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Type.eyebrow.copy(fontSize = 10.sp), color = p.ink2.copy(alpha = 0.6f), maxLines = 1)
    }
}

/**
 * The card every v3 surface is built from: card fill and a hairline edge; [selected] gives it an accent border
 * and a faint glow. [onClick] makes it pressable.
 */
@Composable
fun RivetCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    radius: Dp = 16.dp,
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
            .graphicsLayer { if (down) { scaleX = 0.99f; scaleY = 0.99f } }
            .then(if (selected) Modifier.glow(Glows.selected, shape) else Modifier)
            .clip(shape)
            .background(if (down) p.cardHi else p.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else if (down) p.lineHi else p.edge, shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, role = role, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A square icon well. [selected] lights it in the accent. */
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
    val shape = RoundedCornerShape(size / 4)
    Box(
        modifier.size(size).clip(shape).background(if (selected) p.accentSoft else p.tile)
            .border(1.dp, if (selected) p.accent.copy(alpha = 0.3f) else p.edgeSoft, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) p.accent else tone.color(), modifier = Modifier.size(iconSize))
    }
}

/**
 * Icon well, title (with an optional tag beside it) and one line of body, with [trailing] on the right (toggle,
 * radio, arrow, play). [nested] renders under a divider. Used by every onboarding and voice card.
 */
@Composable
fun FeatureCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Cyan,
    tag: Pair<String, Tone>? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    trailing: (@Composable () -> Unit)? = null,
    nested: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    RivetCard(modifier, selected = selected, onClick = onClick, role = role) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(icon, tone = tone, selected = selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Type.cardTitle, color = p.ink, modifier = Modifier.weight(1f, fill = false))
                    if (tag != null) {
                        Spacer(Modifier.width(8.dp))
                        StatusTag(tag.first, tag.second)
                    }
                }
                Text(body, style = Type.cardBody, color = p.ink2)
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
        if (nested != null) {
            Spacer(Modifier.height(14.dp))
            Rule(color = p.edgeSoft)
            Box(Modifier.padding(top = 8.dp)) { nested() }
        }
    }
}

/** A sub-option row inside a card: muted label and a compact toggle; the whole row toggles. */
@Composable
fun NestedRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.cardBody, color = p.ink2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        CompactToggle(checked, null)
    }
}

/** A quiet note strip with an icon. */
@Composable
fun InfoStrip(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info, tone: Tone = Tone.Neutral) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(p.raised).border(1.dp, p.edgeSoft, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.cardBody, color = p.ink2.copy(alpha = 0.8f))
    }
}

/** The trust line at the foot of a screen: an icon and centred mono lines. */
@Composable
fun TrustFooter(lines: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.VerifiedUser, tone: Tone = Tone.Cyan) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            lines, style = Type.labelMd.copy(lineHeight = 17.sp), color = p.ink2, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(34.dp))
    }
}

/** A requirement that is met: green check, title and body, and a tag on the right. */
@Composable
fun CheckCard(title: String, body: String, tag: String, tagTone: Tone, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    RivetCard(modifier, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(p.bg).border(1.dp, p.edge, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = p.ok, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Type.cardTitle, color = p.ink)
                Text(body, style = Type.cardBody, color = p.ink2)
            }
            Spacer(Modifier.width(8.dp))
            StatusTag(tag, tagTone, outline = true)
        }
    }
}

/** A one-line status: tone dot and mono caps, with an optional four-bar level glyph on the right. */
@Composable
fun StatusLine(text: String, tone: Tone, modifier: Modifier = Modifier, bars: Boolean = false) {
    val p = LocalPalette.current
    val c = tone.color()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = Type.labelMd, color = c, maxLines = 1, modifier = Modifier.weight(1f))
        if (bars) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                listOf(p.accent to 14.dp, p.ok to 18.dp, p.cy to 12.dp, p.accent to 6.dp).forEach { (color, h) ->
                    Box(Modifier.width(4.dp).height(h).clip(RoundedCornerShape(1.dp)).background(color))
                }
            }
        }
    }
}

/** Entrance: fade in and rise 12dp, delayed by [index] steps; runs once for the composable's lifetime. */
fun Modifier.enter(index: Int): Modifier = composed {
    val reduce = rememberReduceMotion()
    val t = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(320, delayMillis = index * Motion.STAGGER, easing = Motion.Emphasized))
    }
    graphicsLayer {
        alpha = t.value
        translationY = (1f - t.value) * 12.dp.toPx()
    }
}
