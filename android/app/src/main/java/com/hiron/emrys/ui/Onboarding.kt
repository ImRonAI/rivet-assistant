package com.hiron.emrys.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.ScreenSearchDesktop
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hiron.emrys.R
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.device.ScreenOperator

/**
 * One group on the access step: what Emrys uses, why, and the Android permissions behind it. [screen] marks
 * Emrys's accessibility service, which Android turns on in its own settings rather than by a permission prompt.
 */
data class AccessGroup(
    val name: String,
    val why: String,
    val icon: ImageVector,
    val tone: Tone,
    val permissions: List<String>,
    val screen: Boolean = false,
)

val ACCESS_GROUPS = listOf(
    AccessGroup(
        "Screen context", "Reads the screen when you ask, so Emrys can help in any app.",
        Icons.Outlined.ScreenSearchDesktop, Tone.Cyan, emptyList(), screen = true,
    ),
    AccessGroup(
        "Notifications & calendar", "Checks your day, adds events, and brings you reminders and approvals.",
        Icons.Outlined.NotificationsActive, Tone.Accent,
        buildList {
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        },
    ),
    AccessGroup(
        "Messages & actions", "Reads and sends texts, finds contacts and calls people back, always asking first.",
        Icons.Outlined.Forum, Tone.Cyan,
        listOf(
            Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
        ),
    ),
)

private const val STEPS = 4

/** The steps' serif headline: the greeting size, shrinking (never below [HEADLINE_MIN]) to stay on one line. */
private val Headline = Type.greeting
private val HEADLINE_MIN = 28.sp

/**
 * Emrys's first run, in four steps around one robot stage: meet Emrys, its voice and text models, the default
 * assistant, and what it may use. Each step's serif headline sits over the stage and its list and keys under it; the
 * stage is hoisted so the robot page is never rebuilt between steps.
 */
@Composable
fun Onboarding(runtime: EmrysRuntime, onFinish: () -> Unit) {
    val p = LocalPalette.current
    var step by rememberSaveable { mutableIntStateOf(1) }
    BackHandler(enabled = step > 1) { step-- }
    val density = LocalDensity.current
    val height = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    // The robot takes the room the headline, the list and the keys leave it: less on short phones and at large text sizes.
    val stage = when {
        height >= 1000.dp -> 280.dp
        height >= 860.dp && density.fontScale <= 1.15f -> 224.dp
        height >= 720.dp -> 188.dp
        else -> 164.dp
    }
    val slide = with(density) { 24.dp.roundToPx() }
    val reduce = rememberReduceMotion()

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            StepIndicator(step, STEPS)
        }
        AnimatedContent(step, Modifier.fillMaxWidth(), transitionSpec = { stepMotion(slide, reduce) }, label = "heading") { shown ->
            StepHeading(shown)
        }
        StageFrame(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(stage), hint = if (hintFits()) STAGE_HINT else null) {
            RobotView(runtime, Modifier.fillMaxSize(), focus = RobotFocus.Card)
        }
        AnimatedContent(step, Modifier.weight(1f).fillMaxWidth(), transitionSpec = { stepMotion(slide, reduce) }, label = "step") { shown ->
            when (shown) {
                1 -> MeetStep(runtime, next = { step = 2 }, onFinish = onFinish)
                2 -> ModelsStep(runtime, next = { step = 3 })
                3 -> AssistantStep(next = { step = 4 })
                else -> AccessStep(onFinish)
            }
        }
    }
}

/** A step arrives from the side it is travelling to while the last one fades; with motion reduced it changes in place. */
private fun AnimatedContentTransitionScope<Int>.stepMotion(slide: Int, reduce: Boolean): ContentTransform {
    if (reduce) return fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    val dir = if (targetState > initialState) 1 else -1
    val fade = tween<Float>(Motion.BASE, easing = Motion.Emphasized)
    val move = tween<IntOffset>(Motion.BASE, easing = Motion.Emphasized)
    return (slideInHorizontally(move) { dir * slide } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(move) { -dir * slide } + fadeOut(fade))
}

/** Each step's headline, ended by the halo stop ("Meet Emrys" with the name in italic), and the warm line under it. */
private fun stepCopy(step: Int): Pair<AnnotatedString, String> = when (step) {
    1 -> buildAnnotatedString {
        append("Meet ")
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Emrys") }
        appendHaloStop()
    } to "An AI assistant you can see, with an honest heart."
    2 -> headline("Talk or type") to "Emrys's ElevenLabs agents answer you, or Emrys's own brain when they're off."
    3 -> headline("Default assistant") to "Set Emrys as your phone's assistant so it's always one press away."
    else -> headline("System access") to "Strictly opt-in. Revocable anytime in Android Settings."
}

private fun headline(text: String) = buildAnnotatedString {
    append(text)
    appendHaloStop()
}

/**
 * The serif headline over the stage and its sub-line. The sub-line always holds two lines, so the stage stays put
 * from step to step.
 */
@Composable
private fun StepHeading(step: Int) {
    val p = LocalPalette.current
    val (title, sub) = stepCopy(step)
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            title,
            Modifier.fillMaxWidth().semantics { heading() },
            style = Headline.copy(color = p.ink, textAlign = TextAlign.Center),
            maxLines = 1,
            inlineContent = haloStopContent(),
            autoSize = TextAutoSize.StepBased(minFontSize = HEADLINE_MIN, maxFontSize = Headline.fontSize, stepSize = 1.sp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            sub, style = Type.bodyLg.copy(lineBreak = LineBreak.Heading), color = p.ink2, textAlign = TextAlign.Center, minLines = 2,
            modifier = Modifier.widthIn(max = 330.dp),
        )
    }
}

/** True when the stage hint fits on one line inside the framed stage at this width and font size. */
@Composable
private fun hintFits(): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    return remember(measurer, density, width) {
        val text = measurer.measure(STAGE_HINT, Type.labelSm).size.width
        // Gutters, the pill's inset and padding, and the hint's touch icon.
        text <= with(density) { (width - 40.dp - 24.dp - 24.dp - 19.dp).toPx() }
    }
}

/** The scrolling list of a step under the stage, over its footer of keys. */
@Composable
private fun StepScaffold(keys: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    // The last row clears the footer however tall it grows (a wrapped link at large text sizes).
    var footer by remember { mutableStateOf(168.dp) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = footer + 8.dp),
            content = content,
        )
        FadeFooter(
            Modifier.align(Alignment.BottomCenter).onSizeChanged { footer = with(density) { it.height.toDp() } },
            content = keys,
        )
    }
}

/** One entry of a step's list under a hairline; [last] closes the list with another. Entries rise in one by one. */
@Composable
private fun Ruled(index: Int, last: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().enter(index)) {
        Rule(color = p.line)
        content()
        if (last) Rule(color = p.line)
    }
}

/**
 * A row of a step's list: an icon well, a Geist title (with an optional status [note] beside it) over a line of
 * body, and [trailing] on the right. A non-null [checked] makes the row report a switch's state, and [onToggle]
 * (when set) lets the whole row flip it; the well lifts while it is on.
 */
@Composable
private fun SetupRow(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    note: Pair<String, Tone>? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth()
            .then(if (checked != null) Modifier.semantics(mergeDescendants = true) { toggleableState = ToggleableState(checked) } else Modifier)
            .then(
                if (onToggle != null) {
                    Modifier.clickable(interactionSource = source, indication = null, role = Role.Switch) {
                        haptics.performHapticFeedback(if (checked == true) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        onToggle()
                    }
                } else {
                    Modifier
                },
            )
            .background(if (pressed) p.edgeSoft else Color.Transparent)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconWell(icon, selected = checked == true, size = 40.dp, iconSize = 20.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.title, color = p.ink, modifier = Modifier.weight(1f, fill = false))
                if (note != null) {
                    Spacer(Modifier.width(10.dp))
                    StatusTag(note.first, note.second, outline = true)
                }
            }
            Text(body, style = Type.body, color = p.ink2)
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

@Composable
private fun MeetStep(runtime: EmrysRuntime, next: () -> Unit, onFinish: () -> Unit) {
    var recommending by rememberSaveable { mutableStateOf(false) }
    if (recommending) {
        RecommendedFlow(runtime) { applied ->
            recommending = false
            if (applied) onFinish()
        }
    }
    StepScaffold(
        keys = {
            StepKey("Use recommended settings") { recommending = true }
            SecondaryKey("Set up step by step", next, height = 52.dp)
            FooterLink("Not now", onFinish)
        },
    ) {
        Ruled(0) {
            SetupRow(Icons.Outlined.Shield, "You stay in control", "Emrys only uses what you turn on, and you can turn any of it off.")
        }
        Ruled(1) {
            SetupRow(Icons.Outlined.GppGood, "Asks before it acts", "Always confirms before sending messages, booking, or taking actions.")
        }
        Ruled(2, last = true) {
            SetupRow(
                ImageVector.vectorResource(R.drawable.ic_emrys), "A face you can see",
                "Calm expressions and real reactions on your Android phone while Emrys works.",
            )
        }
    }
}

@Composable
private fun ModelsStep(runtime: EmrysRuntime, next: () -> Unit) {
    val p = LocalPalette.current
    val settings = runtime.settings
    var agents by remember { mutableStateOf(settings.elevenLabsAgents) }
    var voice by remember { mutableStateOf(settings.voiceModel) }
    var speak by remember { mutableStateOf(settings.speakReplies) }
    val setAgents = { on: Boolean ->
        agents = on
        runtime.useAgents(on)
    }
    val setVoice = { on: Boolean ->
        voice = on
        settings.voiceModel = on
    }
    val openVoice = LocalOpenVoiceSetup.current
    StepScaffold(
        keys = {
            StepKey("Save & continue", next)
            FooterLink("Choose Emrys's voice", openVoice, leading = Icons.Outlined.GraphicEq, tone = Tone.Accent)
        },
    ) {
        Ruled(0) {
            SetupRow(
                Icons.Outlined.RecordVoiceOver, "ElevenLabs agents", "Talk and type with Emrys's ElevenLabs agents. Off: Emrys's own brain answers.",
                checked = agents, onToggle = { setAgents(!agents) }, trailing = { EmrysToggle(agents, null) },
            )
        }
        Ruled(1) {
            SetupRow(Icons.Outlined.Psychology, "Emrys's own brain", "Answers when the ElevenLabs agents are off, runs background tasks, and asks before it acts.")
        }
        Ruled(2, last = true) {
            SetupRow(
                Icons.Outlined.GraphicEq, "Talk key", "Shows the mic key, so you can talk to Emrys out loud.",
                note = "When you talk" to Tone.Accent,
                checked = voice, onToggle = { setVoice(!voice) }, trailing = { EmrysToggle(voice, null) },
            )
            Rule(Modifier.padding(start = 56.dp), color = p.edgeSoft)
            NestedRow(
                "Read replies aloud when I type", speak,
                {
                    speak = it
                    settings.speakReplies = it
                },
                Modifier.padding(start = 56.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

private enum class Summon { Power, Corner }

@Composable
private fun AssistantStep(next: () -> Unit) {
    val context = LocalContext.current
    var summon by rememberSaveable { mutableStateOf(Summon.Power) }
    val held = remember { context.holdsAssistantRole() }
    StepScaffold(
        keys = {
            if (held) {
                StepKey("Continue", next)
            } else {
                StepKey("Open assistant settings") {
                    context.openFirst(
                        AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                        AndroidSettings.ACTION_VOICE_INPUT_SETTINGS,
                        AndroidSettings.ACTION_SETTINGS,
                    )
                    next()
                }
                FooterLink("Skip for now", next)
            }
        },
    ) {
        val power = @Composable { m: Modifier, stretched: Boolean ->
            SummonCard(
                Summon.Power, summon, { summon = it }, 0, Icons.Outlined.PowerSettingsNew,
                "Long-press power button", "Hold the power button to call Emrys from anywhere.", m, stretched, tag = "Primary",
            )
        }
        val corner = @Composable { m: Modifier, stretched: Boolean ->
            SummonCard(
                Summon.Corner, summon, { summon = it }, 1, Icons.Outlined.Swipe,
                "Corner swipe", "Swipe up from a bottom corner, on phones that use gesture navigation.", m, stretched,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 340.dp) {
                EqualRow(12.dp) {
                    power(Modifier.fillMaxHeight(), true)
                    corner(Modifier.fillMaxHeight(), true)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    power(Modifier, false)
                    corner(Modifier, false)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val strip = Modifier.enter(2)
        when {
            held -> InfoStrip("Emrys is already your assistant.", strip, icon = Icons.Outlined.CheckCircle, tone = Tone.Green)
            summon == Summon.Power -> InfoStrip(
                "Tapping below will open Android Assistant settings to select Emrys. Then hold the power button to call it.", strip,
            )
            else -> InfoStrip(
                "Tapping below will open Android Assistant settings to select Emrys. Then swipe up from a bottom corner to call it.", strip,
            )
        }
    }
}

/** Lays its children side by side in equal widths, [gap] apart, all as tall as the tallest at that width. */
@Composable
private fun EqualRow(gap: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val space = gap.roundToPx()
        val width = ((constraints.maxWidth - space * (measurables.size - 1)) / measurables.size.coerceAtLeast(1)).coerceAtLeast(0)
        val height = measurables.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0
        val placeables = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, placeable -> placeable.placeRelative(i * (width + space), 0) }
        }
    }
}

/**
 * One way to call Emrys, as a choice tile: its icon and radio over the title and body; chosen, it wears the halo ring.
 * [stretched] tiles share their neighbour's height, so the tag sits at the foot.
 */
@Composable
private fun SummonCard(
    value: Summon,
    chosen: Summon,
    onChoose: (Summon) -> Unit,
    index: Int,
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier,
    stretched: Boolean,
    tag: String? = null,
) {
    val p = LocalPalette.current
    val on = value == chosen
    EmrysCard(
        modifier.enter(index).ring(p.accent.copy(alpha = 0.2f), RoundedCornerShape(20.dp), on, gap = 3.dp).semantics { selected = on },
        selected = on, onClick = { onChoose(value) }, role = Role.RadioButton,
        padding = PaddingValues(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(icon, selected = on, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.weight(1f))
            RadioDot(on)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = Type.title, color = p.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Type.cardBody, color = p.ink2)
        if (tag != null) {
            // Side by side, the tag sits at the foot of the taller tile.
            if (stretched) Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(14.dp))
            StatusTag(tag, Tone.Accent, outline = true)
        }
    }
}

@Composable
private fun AccessStep(onFinish: () -> Unit) {
    val context = LocalContext.current
    val granted = remember { ACCESS_GROUPS.map { context.has(it) } }
    var chosen by rememberSaveable { mutableStateOf(granted.map { !it }) }
    val screenWanted = ACCESS_GROUPS.indices.any { chosen[it] && !granted[it] && ACCESS_GROUPS[it].screen }
    val done = {
        if (screenWanted) context.openFirst(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
        onFinish()
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { done() }
    StepScaffold(
        keys = {
            StepKey("Complete setup") {
                val wanted = ACCESS_GROUPS.filterIndexed { i, _ -> chosen[i] && !granted[i] }.flatMap { it.permissions }
                if (wanted.isEmpty()) done() else ask.launch(wanted.toTypedArray())
            }
            FooterLink("Customize later in Settings", onFinish)
        },
    ) {
        ACCESS_GROUPS.forEachIndexed { i, g ->
            val on = granted[i] || chosen[i]
            val flip = { chosen = chosen.toMutableList().also { it[i] = !it[i] } }
            Ruled(i, last = i == ACCESS_GROUPS.lastIndex) {
                SetupRow(
                    g.icon, g.name, g.why,
                    checked = on,
                    onToggle = if (granted[i]) null else flip,
                    note = if (granted[i]) "On" to Tone.Green else null,
                    trailing = { EmrysToggle(on, null, enabled = !granted[i]) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        TrustFooter(
            "Revocable any time in Android Settings\nNothing goes out without your OK",
            Modifier.enter(ACCESS_GROUPS.size), tone = Tone.Neutral,
        )
    }
}

/** A step's porcelain key; it drops its arrow when the label would otherwise be cut short (narrow phones, large text). */
@Composable
private fun StepKey(text: String, onClick: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val label = remember(text, measurer, density) { measurer.measure(text, Type.button).size.width }
        // The pill's padding either side, then the arrow and its gap.
        val arrow = label + with(density) { (48.dp + 28.dp).toPx() } <= constraints.maxWidth
        PrimaryKey(text, onClick, trailingArrow = arrow)
    }
}

/** A text link in [TextKey]'s look whose label wraps instead of clipping on narrow screens with large text. */
@Composable
private fun FooterLink(text: String, onClick: () -> Unit, leading: ImageVector? = null, tone: Tone = Tone.Neutral) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = if (tone == Tone.Accent) p.accent else p.ink2
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .alpha(if (pressed) 0.55f else 1f).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        CapsText(text, Type.monoKey.copy(fontSize = 14.sp, lineHeight = 18.sp), ink, textAlign = TextAlign.Center)
    }
}

private fun Context.has(group: AccessGroup): Boolean =
    if (group.screen) {
        ScreenOperator.connected
    } else {
        group.permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

internal fun Context.holdsAssistantRole(): Boolean {
    val roles = getSystemService(RoleManager::class.java) ?: return false
    return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
}

/** Opens the first of [actions] this phone has a screen for. */
internal fun Context.openFirst(vararg actions: String) {
    for (action in actions) {
        try {
            startActivity(Intent(action))
            return
        } catch (_: ActivityNotFoundException) {
            continue
        }
    }
}
