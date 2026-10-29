package com.hiron.rivet.ui

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hiron.rivet.R
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.device.ScreenOperator

/**
 * One group on the access step: what Rivet uses, why, and the Android permissions behind it. [screen] marks
 * Rivet's accessibility service, which Android turns on in its own settings rather than by a permission prompt.
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
        "Screen context", "Reads the screen when you ask, so Rivet can help in any app.",
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

/**
 * Rivet's first run, in four steps under one robot stage: meet Rivet, its voice and text models, the default
 * assistant, and what it may use. The stage is hoisted so the robot page is never rebuilt between steps.
 */
@Composable
fun Onboarding(runtime: RivetRuntime, onFinish: () -> Unit) {
    val p = LocalPalette.current
    var step by rememberSaveable { mutableIntStateOf(1) }
    BackHandler(enabled = step > 1) { step-- }
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val stage = if (with(density) { window.height.toDp() } < 720.dp) 200.dp else 248.dp
    val slide = with(density) { 24.dp.roundToPx() }

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            StepIndicator(step, STEPS)
        }
        StageFrame(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(stage), hint = if (hintFits()) STAGE_HINT else null) {
            RobotView(runtime, Modifier.fillMaxSize(), focus = RobotFocus.Card)
        }
        AnimatedContent(
            step,
            Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                val spec = tween<Float>(Motion.BASE, easing = Motion.Emphasized)
                val move = tween<IntOffset>(Motion.BASE, easing = Motion.Emphasized)
                (slideInHorizontally(move) { dir * slide } + fadeIn(spec)) togetherWith
                    (slideOutHorizontally(move) { -dir * slide } + fadeOut(spec))
            },
            label = "step",
        ) { shown ->
            when (shown) {
                1 -> MeetStep(runtime, next = { step = 2 }, onFinish = onFinish)
                2 -> ModelsStep(runtime, next = { step = 3 })
                3 -> AssistantStep(next = { step = 4 })
                else -> AccessStep(onFinish)
            }
        }
    }
}

/** True when the stage hint fits on one line inside the framed stage at this width and font size. */
@Composable
private fun hintFits(): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    return remember(measurer, density, width) {
        val text = measurer.measure(STAGE_HINT.uppercase(), Type.labelSm.copy(letterSpacing = 0.14.em)).size.width
        // Gutters, the pill's inset and padding, and the hint's touch icon.
        text <= with(density) { (width - 40.dp - 24.dp - 24.dp - 19.dp).toPx() }
    }
}

/** The scrolling body of a step over its footer of keys. */
@Composable
private fun StepScaffold(
    headline: String,
    sub: String,
    keys: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    // The last card clears the footer however tall it grows (a wrapped link at large text sizes).
    var footer by remember { mutableStateOf(168.dp) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = footer + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepHeadline(headline, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                sub, style = Type.body.copy(fontSize = 13.sp, lineHeight = 20.sp), color = p.ink2, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
        FadeFooter(
            Modifier.align(Alignment.BottomCenter).onSizeChanged { footer = with(density) { it.height.toDp() } },
            content = keys,
        )
    }
}

@Composable
private fun MeetStep(runtime: RivetRuntime, next: () -> Unit, onFinish: () -> Unit) {
    var recommending by rememberSaveable { mutableStateOf(false) }
    if (recommending) {
        RecommendedFlow(runtime) { applied ->
            recommending = false
            if (applied) onFinish()
        }
    }
    StepScaffold(
        "MEET RIVET",
        "An AI assistant you can see, with an honest heart.",
        keys = {
            PrimaryKey("Use recommended settings", { recommending = true })
            FooterLink("Set up step by step", next)
            FooterLink("Not now", onFinish)
        },
    ) {
        FeatureCard(
            Icons.Outlined.Shield, "You stay in control", "Rivet only uses what you turn on, and you can turn any of it off.",
            Modifier.enter(0), tone = Tone.Cyan,
        )
        FeatureCard(
            Icons.Outlined.GppGood, "Asks before it acts", "Always confirms before sending messages, booking, or taking actions.",
            Modifier.enter(1), tone = Tone.Accent,
        )
        FeatureCard(
            ImageVector.vectorResource(R.drawable.ic_rivet), "A face you can see",
            "Calm expressions and real reactions on your Android phone while Rivet works.",
            Modifier.enter(2), tone = Tone.Cyan,
        )
    }
}

@Composable
private fun ModelsStep(runtime: RivetRuntime, next: () -> Unit) {
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
        "TALK OR TYPE",
        "Rivet's ElevenLabs agents answer you, or Rivet's own brain when they're off.",
        keys = {
            PrimaryKey("Save & Continue", next)
            FooterLink("Choose Rivet's voice", openVoice, leading = Icons.Outlined.GraphicEq, tone = Tone.Cyan)
        },
    ) {
        FeatureCard(
            Icons.Outlined.RecordVoiceOver, "ElevenLabs agents", "Talk and type with Rivet's ElevenLabs agents. Off: Rivet's own brain answers.",
            Modifier.enter(0).semantics { toggleableState = ToggleableState(agents) },
            tone = Tone.Accent, onClick = { setAgents(!agents) }, role = Role.Switch,
            trailing = { RivetToggle(agents, null) },
        )
        FeatureCard(
            Icons.Outlined.Psychology, "Rivet's own brain", "Answers when the ElevenLabs agents are off, runs background tasks, and asks before it acts.",
            Modifier.enter(1), tone = Tone.Cyan,
        )
        FeatureCard(
            Icons.Outlined.GraphicEq, "Talk key", "Shows the mic key, so you can talk to Rivet out loud.",
            Modifier.enter(2).semantics { toggleableState = ToggleableState(voice) },
            tone = Tone.Accent, tag = "WHEN YOU TALK" to Tone.Accent,
            onClick = { setVoice(!voice) }, role = Role.Switch,
            trailing = { RivetToggle(voice, null) },
            nested = {
                NestedRow("Read replies aloud when I type", speak, {
                    speak = it
                    settings.speakReplies = it
                })
            },
        )
    }
}

private enum class Summon { Power, Corner }

@Composable
private fun AssistantStep(next: () -> Unit) {
    val context = LocalContext.current
    var summon by rememberSaveable { mutableStateOf(Summon.Power) }
    val held = remember { context.holdsAssistantRole() }
    StepScaffold(
        "DEFAULT ASSISTANT",
        "Set Rivet as your phone's assistant so it's always one press away.",
        keys = {
            if (held) {
                PrimaryKey("Continue", next)
            } else {
                PrimaryKey("Open Assistant Settings", {
                    context.openFirst(
                        AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                        AndroidSettings.ACTION_VOICE_INPUT_SETTINGS,
                        AndroidSettings.ACTION_SETTINGS,
                    )
                    next()
                })
                FooterLink("Skip for now", next)
            }
        },
    ) {
        SummonCard(
            Summon.Power, summon, { summon = it }, 0, Icons.Outlined.PowerSettingsNew, Tone.Accent,
            "Long-press power button", "Hold the power button to call Rivet from anywhere.", tag = "PRIMARY" to Tone.Accent,
        )
        SummonCard(
            Summon.Corner, summon, { summon = it }, 1, Icons.Outlined.Swipe, Tone.Cyan,
            "Corner swipe", "Swipe up from a bottom corner, on phones that use gesture navigation.",
        )
        val strip = Modifier.enter(2)
        when {
            held -> InfoStrip("Rivet is already your assistant.", strip, icon = Icons.Outlined.CheckCircle, tone = Tone.Green)
            summon == Summon.Power -> InfoStrip(
                "Tapping below will open Android Assistant settings to select Rivet. Then hold the power button to call it.", strip,
            )
            else -> InfoStrip(
                "Tapping below will open Android Assistant settings to select Rivet. Then swipe up from a bottom corner to call it.", strip,
            )
        }
    }
}

@Composable
private fun SummonCard(
    value: Summon,
    chosen: Summon,
    onChoose: (Summon) -> Unit,
    index: Int,
    icon: ImageVector,
    tone: Tone,
    title: String,
    body: String,
    tag: Pair<String, Tone>? = null,
) {
    val on = value == chosen
    FeatureCard(
        icon, title, body, Modifier.enter(index).semantics { selected = on },
        tone = tone, tag = tag, selected = on, onClick = { onChoose(value) }, role = Role.RadioButton,
        trailing = { RadioDot(on) },
    )
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
        "SYSTEM ACCESS",
        "Strictly opt-in. Revocable anytime in Android Settings.",
        keys = {
            PrimaryKey("Complete Setup", {
                val wanted = ACCESS_GROUPS.filterIndexed { i, _ -> chosen[i] && !granted[i] }.flatMap { it.permissions }
                if (wanted.isEmpty()) done() else ask.launch(wanted.toTypedArray())
            })
            FooterLink("Customize later in Settings", onFinish)
        },
    ) {
        ACCESS_GROUPS.forEachIndexed { i, g ->
            val on = granted[i] || chosen[i]
            val flip = { chosen = chosen.toMutableList().also { it[i] = !it[i] } }
            FeatureCard(
                g.icon, g.name, g.why,
                Modifier.enter(i).semantics(mergeDescendants = true) { toggleableState = ToggleableState(on) },
                tone = g.tone,
                tag = if (granted[i]) "ON" to Tone.Green else null,
                onClick = if (granted[i]) null else flip,
                role = Role.Switch,
                trailing = { RivetToggle(on, null, enabled = !granted[i]) },
            )
        }
        TrustFooter("REVOCABLE ANY TIME IN ANDROID SETTINGS\nNOTHING GOES OUT WITHOUT YOUR OK", Modifier.enter(ACCESS_GROUPS.size))
    }
}

/** [TextKey]'s look, but the caps label wraps instead of clipping on narrow screens with large text. */
@Composable
private fun FooterLink(text: String, onClick: () -> Unit, leading: ImageVector? = null, tone: Tone = Tone.Neutral) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .alpha(if (pressed) 0.6f else 1f).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = tone.color(), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        CapsText(text, Type.monoKey, p.ink2, textAlign = TextAlign.Center)
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
