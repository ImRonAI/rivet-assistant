package com.hiron.emrys.ui

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

/**
 * The glow presets: soft halo light, never a flood. Noir keeps cards and keys flat, so a glow marks only a pressed
 * primary, the live voice and a selected card. Emrys is dark only, so they are fixed colours.
 */
object Glows {
    private val accent = DarkPalette.accent
    private val hi = DarkPalette.accentHi
    val cta = GlowSpec(hi.copy(alpha = 0.22f), 16.dp, 0.dp)
    val talk = GlowSpec(accent.copy(alpha = 0.10f), 20.dp, 0.dp)
    val live = GlowSpec(accent.copy(alpha = 0.40f), 32.dp, 0.dp)
    val peak = GlowSpec(accent.copy(alpha = 0.40f), 8.dp, 0.dp)
    val selected = GlowSpec(accent.copy(alpha = 0.06f), 20.dp, 0.dp)
    val disc = GlowSpec(accent.copy(alpha = 0.06f), 12.dp, 6.dp)
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

/** A 1dp ring [gap] outside [shape], flush against its border by default: the focus halo of fields and the composer card. */
internal fun Modifier.ring(color: Color, shape: Shape, show: Boolean, gap: Dp = 0.dp): Modifier = drawBehind {
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

/**
 * The status dot: [size] across in [color]. [live] lets it breathe slowly inside a faint bloom of its own colour;
 * with motion reduced it holds still and lit.
 */
@Composable
internal fun SignalDot(color: Color, modifier: Modifier = Modifier, live: Boolean = false, size: Dp = 6.dp) {
    val a = pulse(live, low = 0.45f, periodMs = Motion.BREATHE)
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        if (live) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f * a), Color.Transparent), center, r * 2.6f), r * 2.6f)
        drawCircle(color.copy(alpha = 0.55f + 0.45f * a), r)
    }
}

/**
 * Upper-cased eyebrow text, the one uppercase style. Screen readers and tests get [text] as written, so an eyebrow
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

private const val HALO_STOP = "halo-stop"

/**
 * The quiet halo dot that ends a serif title or the wordmark, in place of punctuation. It is read as [spoken]
 * (a full stop by default), so the line still announces as a sentence.
 */
internal fun AnnotatedString.Builder.appendHaloStop(spoken: String = ".") = appendInlineContent(HALO_STOP, spoken)

/** The inline content that draws [appendHaloStop]: a small halo dot sitting just above the baseline, with a faint bloom. */
@Composable
internal fun haloStopContent(): Map<String, InlineTextContent> {
    val p = LocalPalette.current
    return remember(p) {
        mapOf(
            HALO_STOP to InlineTextContent(Placeholder(0.3.em, 0.42.em, PlaceholderVerticalAlign.AboveBaseline)) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.width * 0.21f
                    val c = Offset(size.width * 0.56f, size.height - r * 1.1f)
                    drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.35f), Color.Transparent), c, r * 3f), r * 3f, c)
                    drawCircle(p.accent, r, c)
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

/** An eyebrow: a small dot in the tone's colour and a widely tracked uppercase label. [live] lets the dot breathe. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Accent, dot: Boolean = true, live: Boolean = false) {
    val p = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            SignalDot(tone.color(), live = live)
            Spacer(Modifier.width(10.dp))
        }
        EyebrowText(text, p.ink2)
    }
}

/** The large serif title of a screen, ended by a quiet halo dot (added here; do not put punctuation at the end of [text]). */
@Composable
fun HeroTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.hero) {
    val p = LocalPalette.current
    Text(
        buildAnnotatedString {
            append(text)
            appendHaloStop()
        },
        style = style, color = p.ink, modifier = modifier, inlineContent = haloStopContent(),
    )
}

/** The onboarding headline: one serif line, centred. */
@Composable
fun StepHeadline(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(text, style = Type.step, color = p.ink, textAlign = TextAlign.Center)
    }
}

/** A section's eyebrow, with an optional quiet note on the right ("4 ways", "Optional"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.eyebrow, color = p.ink2, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Type.labelMd, color = p.ink2.copy(alpha = 0.8f), maxLines = 1)
    }
}

/**
 * The card every surface is built from: a soft card fill and a 1dp hairline at a 20dp radius. [selected] draws a halo
 * hairline and a faint halo light; [onClick] makes it pressable (it brightens while pressed).
 */
@Composable
fun EmrysCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    radius: Dp = 20.dp,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val down = pressed && onClick != null
    Column(
        modifier.fillMaxWidth()
            .graphicsLayer { if (down) { scaleX = 0.995f; scaleY = 0.995f } }
            .then(if (selected) Modifier.glow(Glows.selected, shape) else Modifier)
            .clip(shape)
            .background(if (down) p.cardHi else p.card)
            .border(1.dp, if (selected) p.accent.copy(alpha = 0.55f) else if (down) p.lineHi else p.line, shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, role = role, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/**
 * A round icon well. The icon sits in [Tone.Neutral] grey; a status tone (green, amber, red) tints it, while the
 * decorative tones stay porcelain. [selected] lifts the well.
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
        selected -> p.ink
        tone == Tone.Green || tone == Tone.Amber || tone == Tone.Red -> tone.color()
        tone == Tone.Neutral -> p.ink2
        else -> p.ink
    }
    Box(
        modifier.size(size).clip(CircleShape).background(if (selected) p.high else p.edgeSoft)
            .border(1.dp, if (selected) p.lineHi else p.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * Icon well, title (with an optional status tag beside it) and a line of body, with [trailing] on the right
 * (toggle, radio, arrow, play). [nested] renders under a hairline. Used by every onboarding and voice card.
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
    EmrysCard(modifier, selected = selected, onClick = onClick, role = role) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(icon, tone = tone, selected = selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Type.cardTitle, color = p.ink, modifier = Modifier.weight(1f, fill = false))
                    if (tag != null) {
                        Spacer(Modifier.width(10.dp))
                        StatusTag(tag.first, tag.second, outline = true)
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
            Rule(color = p.line)
            Box(Modifier.padding(top = 6.dp)) { nested() }
        }
    }
}

/** A sub-option row inside a card: a quiet label and a compact toggle; the whole row toggles. */
@Composable
fun NestedRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.cardBody, color = p.ink2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        CompactToggle(checked, null)
    }
}

/** A quiet note on a faint wash, led by a small icon in its tone. */
@Composable
fun InfoStrip(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info, tone: Tone = Tone.Neutral) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(p.edgeSoft).border(1.dp, p.line, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.cardBody, color = p.ink2)
    }
}

/** The trust line at the foot of a screen: a small icon and a quiet line of text, centred together as one group. */
@Composable
fun TrustFooter(lines: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.VerifiedUser, tone: Tone = Tone.Cyan) {
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

/** A requirement that is met: a sage check, title and body, and a quiet status tag on the right. */
@Composable
fun CheckCard(title: String, body: String, tag: String, tagTone: Tone, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    EmrysCard(modifier, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(p.ok.copy(alpha = 0.10f)).border(1.dp, p.ok.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = p.ok, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Type.cardTitle, color = p.ink)
                Text(body, style = Type.cardBody, color = p.ink2)
            }
            Spacer(Modifier.width(8.dp))
            StatusTag(tag, tagTone, outline = true)
        }
    }
}

/** A one-line status: a tone dot and a plain sentence, with an optional four-bar level glyph in the tone on the right. */
@Composable
fun StatusLine(text: String, tone: Tone, modifier: Modifier = Modifier, bars: Boolean = false) {
    val p = LocalPalette.current
    val c = tone.color()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SignalDot(c)
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.labelLg, color = p.ink, maxLines = 1, modifier = Modifier.weight(1f))
        if (bars) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                listOf(0.45f to 6.dp, 0.7f to 10.dp, 0.85f to 14.dp, 1f to 18.dp).forEach { (a, h) ->
                    Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(1.5.dp)).background(c.copy(alpha = a)))
                }
            }
        }
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
