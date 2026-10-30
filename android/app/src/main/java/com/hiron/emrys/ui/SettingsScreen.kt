package com.hiron.emrys.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hiron.emrys.BuildConfig
import com.hiron.emrys.core.BrainModel
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.Decision
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.PermissionGroups
import com.hiron.emrys.core.StandingRule
import com.hiron.emrys.core.isE164
import com.hiron.emrys.device.CallForwarding
import com.hiron.emrys.device.CarrierStyle
import com.hiron.emrys.device.EmrysNotificationListener
import com.hiron.emrys.device.ForwardMode
import com.hiron.emrys.device.ScreenOperator
import com.hiron.emrys.service.EmrysService
import java.time.Instant
import java.time.ZoneId

/** A section's serif title. */
private val SectionTitle = Type.step.copy(fontSize = 28.sp, lineHeight = 32.sp)

/** The inset of a row inside a settings card, and of the hairlines between rows. */
private val RowInset = 18.dp

/**
 * Settings, set like a table of contents: each section a quiet figure on a hairline and a serif title, over glass
 * cards whose rows carry Geist labels, grey details, slim switches and hairline dividers.
 */
@Composable
fun SettingsScreen(runtime: EmrysRuntime, state: EmrysState) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val s = runtime.settings
    var url by remember { mutableStateOf(s.gatewayUrl) }
    var token by remember { mutableStateOf("") }
    var session by remember { mutableStateOf(s.sessionId) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val hasToken = remember(refresh, token) { s.deviceToken.isNotBlank() }
    // A ready-to-use build hides the connection details; they open only when something needs changing.
    var advanced by remember { mutableStateOf(!s.gateway().complete) }

    Column(
        Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(horizontal = gutter())
            .padding(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Section(1, "Connection") {
            ConnectionCard(state, s.gatewayUrl, s.sessionId, advanced, { advanced = it }, context)
            AnimatedVisibility(advanced, enter = expandVertically(tween(Motion.BASE)) + fadeIn(), exit = shrinkVertically(tween(Motion.BASE)) + fadeOut()) {
                EmrysCard(padding = PaddingValues(horizontal = RowInset, vertical = 20.dp)) {
                    Eyebrow("Connection details", tone = Tone.Neutral, dot = false)
                    Spacer(Modifier.height(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RecessedField(url, { url = it }, label = "Server address", placeholder = "https://emrys-brain.fly.dev",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        RecessedField(token, { token = it }, label = if (hasToken) "Device token (saved, encrypted)" else "Device token",
                            placeholder = if (hasToken) "Leave blank to keep it" else null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        RecessedField(session, { session = it }, label = "Session id")
                        PrimaryKey(
                            if (state.connection == Connection.Offline) "Save & connect" else "Save & reconnect",
                            {
                                s.gatewayUrl = url
                                if (token.isNotBlank()) s.deviceToken = token
                                if (session.isNotBlank()) s.sessionId = session
                                token = ""
                                EmrysService.start(context)
                            },
                            Modifier.padding(top = 4.dp),
                            trailingArrow = false,
                        )
                    }
                }
            }
        }

        Section(2, "Voice") { Voice(runtime, refresh) }

        Section(3, "How Emrys behaves") { Behaviour(runtime, state) { refresh++ } }

        Section(4, "Approval rules") {
            EmrysCard(padding = PaddingValues(horizontal = RowInset, vertical = 4.dp)) { ApprovalRules(runtime, state) }
        }

        Section(5, "Call answering") { CallAnswering(runtime, refresh) }

        Section(6, "What Emrys can use") {
            Permissions(context, refresh) { refresh++ }
            InfoStrip(
                "On Android 13 and later, first allow restricted settings for Emrys: App info › ⋮ › Allow restricted " +
                    "settings. Then turn on Notification access and the App operator.",
            )
        }

        Section(7, "About") { About() }
    }
}

/** Screen gutters: 20dp, and 16dp on phones under 360dp wide. */
@Composable
private fun gutter(): Dp = if (with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < 360.dp) 16.dp else 20.dp

/** A section: its number in small figures on a hairline, a serif title, then its cards. */
@Composable
private fun Section(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().enter(number - 1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                number.toString().padStart(2, '0'), style = Type.eyebrow.copy(fontFeatureSettings = "tnum"), color = p.ink2,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.width(14.dp))
            Rule(Modifier.weight(1f), color = p.line)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = SectionTitle, color = p.ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** The hairline between two rows of a settings card. */
@Composable
private fun RowRule() = Rule(Modifier.padding(horizontal = RowInset), color = LocalPalette.current.line)

@Composable
private fun ConnectionCard(
    state: EmrysState,
    gatewayUrl: String,
    sessionId: String,
    advanced: Boolean,
    onAdvanced: (Boolean) -> Unit,
    context: Context,
) {
    val p = LocalPalette.current
    val (tone, word) = when (state.connection) {
        Connection.Online -> Tone.Green to "Online"
        Connection.Connecting -> Tone.Amber to "Connecting"
        Connection.Offline -> Tone.Red to "Offline"
    }
    EmrysCard(padding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = RowInset, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (state.connection) {
                    Connection.Online -> "Connected to Emrys"
                    Connection.Connecting -> "Connecting to Emrys…"
                    Connection.Offline -> state.lastError ?: "Not connected"
                },
                style = Type.title, color = p.ink, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            StatusTag(word, tone)
        }
        RowRule()
        Readout(
            "Server", gatewayUrl.removePrefix("https://").removePrefix("wss://").trimEnd('/').ifBlank { "not set" },
            Modifier.padding(horizontal = RowInset, vertical = 14.dp), mono = true,
        )
        RowRule()
        Readout("Session", sessionId.ifBlank { "not set" }, Modifier.padding(horizontal = RowInset, vertical = 14.dp), mono = true)
        RowRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.connection == Connection.Offline) {
                // With the details open, their "Save & connect" is the one porcelain key.
                Keycap("Connect", { EmrysService.start(context) }, Modifier.weight(1f), tone = if (advanced) KeyTone.Plain else KeyTone.Accent)
            } else {
                SecondaryKey("Disconnect", { EmrysService.stop(context) }, Modifier.weight(1f))
            }
            Latch("Details", advanced, onAdvanced, Modifier.weight(1f))
        }
    }
}

/**
 * An outline pill that opens and closes the panel under it: a chevron turns over and the pill lifts to the active
 * segment's fill while it is open.
 */
@Composable
private fun Latch(text: String, on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val turn by animateFloatAsState(if (on) 180f else 0f, tween(Motion.BASE, easing = Motion.Emphasized), label = "latch")
    val pill = RoundedCornerShape(50)
    Row(
        modifier.height(48.dp).clip(pill).background(if (on) p.high else if (pressed) p.cardHi else Color.Transparent)
            .border(1.dp, if (pressed) p.ink.copy(alpha = 0.28f) else p.lineHi, pill)
            .toggleable(on, interactionSource = source, indication = null, role = Role.Switch) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onToggle(it)
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, style = Type.key, color = p.ink, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp).rotate(turn))
    }
}

@Composable
private fun Voice(runtime: EmrysRuntime, refresh: Int) {
    val s = runtime.settings
    var voiceModel by remember(refresh) { mutableStateOf(s.voiceModel) }
    EmrysCard(padding = PaddingValues(0.dp)) {
        Toggle("Talk key", voiceModel, detail = "Shows the mic key, so you can talk to Emrys out loud.") {
            voiceModel = it
            s.voiceModel = it
        }
    }
}

@Composable
private fun Behaviour(runtime: EmrysRuntime, state: EmrysState, onChanged: () -> Unit) {
    val p = LocalPalette.current
    val s = runtime.settings
    // Keyed on [version], so the toggles show what recommended settings (or their undo) just changed.
    var version by remember { mutableIntStateOf(0) }
    var speak by remember(version) { mutableStateOf(s.speakReplies) }
    var overlay by remember(version) { mutableStateOf(s.speakInOverlay) }
    var auto by remember(version) { mutableStateOf(s.autoConnect) }
    var agents by remember { mutableStateOf(s.elevenLabsAgents) }
    var suggest by remember { mutableStateOf(s.searchSuggestions) }
    var undo by remember(version) { mutableStateOf(s.recommendedUndo) }
    // True once undone; false when it couldn't be, because the rules need Emrys connected.
    var undone by remember { mutableStateOf<Boolean?>(null) }
    var recommending by rememberSaveable { mutableStateOf(false) }
    EmrysCard(padding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Use recommended settings", style = Type.bodyLg, color = p.ink)
                Text("See what Emrys suggests and choose what to change.", style = Type.cardBody, color = p.ink2)
            }
            Keycap("Review", { recommending = true })
        }
        undo?.let { u ->
            RowRule()
            Column(Modifier.padding(start = RowInset, end = RowInset, top = 6.dp, bottom = 16.dp)) {
                TextKey("Undo recommended settings", {
                    undone = runtime.undoRecommended()
                    version++
                    onChanged()
                }, Modifier.offset { IntOffset(-10.dp.roundToPx(), 0) })
                Text("Puts back: ${u.titles().joinToString(", ")}", style = Type.cardBody, color = p.ink2)
            }
        }
    }
    when (undone) {
        true -> InfoStrip("Recommended settings undone.", icon = Icons.Outlined.CheckCircle, tone = Tone.Green)
        false -> InfoStrip("Connect to Emrys to undo. Only Emrys can remove the rules these settings made.", tone = Tone.Amber)
        null -> Unit
    }
    if (recommending) {
        RecommendedFlow(runtime) { applied ->
            recommending = false
            if (applied) undone = null
            version++
            onChanged()
        }
    }
    EmrysCard(padding = PaddingValues(0.dp)) {
        Toggle("Read replies aloud", speak) { speak = it; s.speakReplies = it }
        RowRule()
        Toggle("Read replies aloud in the assistant overlay", overlay) { overlay = it; s.speakInOverlay = it }
        RowRule()
        Toggle("Connect automatically", auto, detail = "Also after a reboot.") { auto = it; s.autoConnect = it }
        RowRule()
        Toggle("Use ElevenLabs agents", agents, detail = "Talk and type with Emrys's ElevenLabs agents. Off: Emrys's own brain.") {
            agents = it
            runtime.useAgents(it)
        }
        RowRule()
        Toggle(
            "Suggest searches as you type", suggest,
            detail = "What you type is sent to Brave Search for suggestions. Brave keeps search logs for up to 90 days.",
        ) { suggest = it; s.searchSuggestions = it }
    }
    BrainModelPlate(state, runtime::setModel)
}

/** Which model Emrys's own brain runs; only the brain's `model` event changes what it shows. */
@Composable
internal fun BrainModelPlate(state: EmrysState, onChoose: (BrainModel) -> Boolean) {
    val p = LocalPalette.current
    val online = state.connection == Connection.Online
    // The model asked for, until the brain says which one runs or why it couldn't switch.
    var pending by remember { mutableStateOf<BrainModel?>(null) }
    LaunchedEffect(state.brainModel, state.modelError) { pending = null }
    EmrysCard(padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(start = RowInset, end = RowInset, top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Emrys's own brain", style = Type.bodyLg, color = p.ink)
            Text("The model it thinks with when the ElevenLabs agents are off.", style = Type.cardBody, color = p.ink2)
        }
        BrainModel.entries.forEach { model ->
            val inUse = model == state.brainModel
            val usable = online && model in state.availableModels
            val note = when {
                inUse -> "In use"
                model == pending -> "Switching…"
                !online -> null
                model !in state.availableModels -> "Not set up on the brain"
                else -> null
            }
            // Only the control dims when it can't be chosen; the note explaining why stays legible.
            val dim = Modifier.alpha(if (usable || inUse) 1f else 0.5f)
            RowRule()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp)
                    .selectable(inUse, enabled = usable, role = Role.RadioButton) {
                        if (!inUse && onChoose(model)) pending = model
                    }
                    .padding(horizontal = RowInset, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(dim) { RadioDot(inUse) }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(model.label, style = Type.bodyLg, color = p.ink, modifier = dim)
                    note?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (inUse) {
                                SignalDot(p.ok)
                                Spacer(Modifier.width(7.dp))
                            }
                            Text(it, style = Type.cardBody, color = if (inUse) p.ink else p.ink2)
                        }
                    }
                }
            }
        }
        RowRule()
        Column(Modifier.padding(horizontal = RowInset, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                !online -> Text("Connect to change.", style = Type.cardBody, color = p.ink2)
                state.brainModel == null -> Text("Emrys's brain hasn't said which models it offers.", style = Type.cardBody, color = p.ink2)
            }
            state.modelError?.let { InfoStrip(it, icon = Icons.Outlined.WarningAmber, tone = Tone.Amber) }
            Text("Background tasks always use Emrys's own brain.", style = Type.cardBody, color = p.ink2)
        }
    }
}

/** The brain's standing rules, each removable; only the brain changes them, so removing needs a connection. */
@Composable
internal fun ApprovalRules(runtime: EmrysRuntime, state: EmrysState) {
    val p = LocalPalette.current
    val online = state.connection == Connection.Online
    val rules = state.rules.rules
    if (rules.isEmpty()) {
        Text(
            "No rules yet. When Emrys asks, tick “Don't ask again” to make one.", style = Type.cardBody, color = p.ink2,
            modifier = Modifier.padding(vertical = 14.dp),
        )
    }
    rules.forEachIndexed { i, rule ->
        if (i > 0) Rule(color = p.line)
        RuleRow(rule, state.names, removing = rule.key in state.removingRules, online = online) { runtime.revokeRule(rule) }
    }
    if (!online) InfoStrip("Connect to Emrys to change rules.", Modifier.padding(vertical = 12.dp))
    state.rulesError?.let { InfoStrip(it, Modifier.padding(bottom = 12.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
}

@Composable
private fun RuleRow(rule: StandingRule, names: Map<String, String>, removing: Boolean, online: Boolean, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val number = rule.target.scope["to"] ?: rule.target.scope["number"]
    val since = "since ${ruleDate(rule.createdAt)}"
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(iconFor(rule.target.tool), contentDescription = null, tint = p.ink2, modifier = Modifier.align(Alignment.Top).padding(top = 1.dp).size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Plain.people(rule.label, names), style = Type.bodyLg, color = p.ink)
            if (rule.decision == Decision.Allow) StatusTag("Always allow", Tone.Amber) else StatusTag("Always deny", Tone.Red)
            Text(if (number != null && Plain.numbersIn(number).isNotEmpty()) "${Plain.phone(number)} · $since" else since, style = Type.cardBody, color = p.ink2)
        }
        if (removing) {
            Text("Removing…", style = Type.cardBody, color = p.ink2)
        } else {
            Keycap("Remove", onRemove, enabled = online)
        }
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Oct 29": when a rule was made, in the phone's time zone. */
internal fun ruleDate(createdAt: Double): String {
    val day = Instant.ofEpochMilli((createdAt * 1000).toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${MONTHS[day.monthValue - 1]} ${day.dayOfMonth}"
}

/** The colophon: the Emrys wordmark, which build it is, who made it and where to get help. */
@Composable
private fun About() {
    val p = LocalPalette.current
    EmrysCard(padding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmrysWordmark(size = 40f)
            Spacer(Modifier.height(12.dp))
            Text("Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", style = Type.mono, color = p.ink2)
        }
        RowRule()
        Readout("Made by", "Ron AI · hi-ron.com", Modifier.padding(horizontal = RowInset, vertical = 14.dp))
        RowRule()
        Readout("Support", "support@hi-ron.com", Modifier.padding(horizontal = RowInset, vertical = 14.dp))
    }
}

@Composable
private fun CallAnswering(runtime: EmrysRuntime, refresh: Int) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val s = runtime.settings
    var number by remember(refresh) { mutableStateOf(s.answeringNumber) }
    var mode by remember { mutableStateOf(s.answeringMode) }
    var style by remember { mutableStateOf(s.carrierStyle) }
    var forwardedTo by remember(refresh) { mutableStateOf(s.forwardedTo) }
    var own by remember(refresh) { mutableStateOf(s.ownNumber) }
    var error by remember { mutableStateOf<String?>(null) }
    var codes by rememberSaveable { mutableStateOf(false) }
    val detected = remember { CallForwarding.detect(context) }
    val effective = style ?: detected
    val code = CallForwarding.enableCode(number, mode, effective)
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        error = if (granted) null else "Emrys needs the Phone permission to dial your carrier's forwarding code."
    }

    fun dial(code: String, onDone: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            phonePermission.launch(Manifest.permission.CALL_PHONE)
            return
        }
        error = CallForwarding.dial(context, code)
        if (error == null) onDone()
    }

    val on = forwardedTo.isNotBlank()
    // The status dot sits on the middle of the sentence's first line, at any text size.
    val dotTop = with(LocalDensity.current) { (Type.title.lineHeight / 2).toDp() } - 3.dp
    EmrysCard(padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(start = RowInset, end = RowInset, top = 18.dp, bottom = 18.dp)) {
            Row {
                SignalDot(if (on) p.ok else p.ink2, Modifier.padding(top = dotTop))
                Spacer(Modifier.width(12.dp))
                Text(
                    when {
                        !on -> "Off."
                        forwardedTo != number.trim() -> "On, but forwarding to $forwardedTo. Turn on again to use the new number."
                        else -> "On: forwarding to $forwardedTo."
                    },
                    style = Type.title, color = if (on) p.ink else p.ink2,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Callers still dial your number. When this is on, your carrier forwards calls to Emrys's answering line, " +
                    "Emrys takes a message, and you get it here. (Android doesn't let apps speak on ordinary calls.)",
                style = Type.cardBody, color = p.ink2,
            )
        }
        RowRule()
        Column(Modifier.padding(horizontal = RowInset, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RecessedField(
                number, { number = it }, label = "Emrys's answering number", placeholder = "+17605550100",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            if (number.isBlank()) {
                Keycap("Ask Emrys to set one up", { runtime.send("Set up call answering so you can answer my calls.") })
            }
            RecessedField(
                own, { own = it; s.ownNumber = it.trim() }, label = "Your own number, for Take over", placeholder = "+18015550100",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            if (own.isNotBlank() && !isE164(own.trim())) Text("Use the +1… form, like +18015550100.", style = Type.cardBody, color = p.amb)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Answer", style = Type.labelLg, color = p.ink2)
                Choices(
                    listOf(ForwardMode.Unanswered to "Unanswered calls", ForwardMode.Always to "Every call"),
                    mode, { mode = it },
                )
            }
        }
        RowRule()
        Column(Modifier.padding(start = RowInset, end = RowInset, top = 18.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Keycap(
                    "Turn on",
                    {
                        val target = number.trim()
                        if (code == null) {
                            error = "Enter Emrys's answering number in +1… form first."
                        } else {
                            dial(code) {
                                s.answeringNumber = target
                                s.answeringMode = mode
                                s.forwardedTo = target
                                forwardedTo = target
                            }
                        }
                    },
                    Modifier.weight(1f),
                    tone = KeyTone.Accent,
                )
                Keycap(
                    "Turn off",
                    {
                        dial(CallForwarding.disableCode(effective)) {
                            s.forwardedTo = ""
                            forwardedTo = ""
                        }
                    },
                    Modifier.weight(1f),
                )
            }
            error?.let { InfoStrip(it, icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
            Text(
                "Turn on dials your carrier's forwarding code, and your carrier confirms on screen. Forwarded calls may count " +
                    "against your plan; calls Emrys answers use minutes on Emrys's answering line.",
                style = Type.cardBody, color = p.ink2,
            )
            // The dial codes are for people who want to check what Emrys dials; most never need them.
            Column {
                TextKey(if (codes) "Hide carrier codes" else "Show carrier codes", { codes = !codes }, Modifier.offset { IntOffset(-10.dp.roundToPx(), 0) })
                if (codes) {
                    Column(Modifier.padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Carrier", style = Type.labelLg, color = p.ink2)
                        Choices(
                            listOf<Pair<CarrierStyle?, String>>(null to "Auto", CarrierStyle.Gsm to "Standard", CarrierStyle.Verizon to "Verizon"),
                            style, { style = it; s.carrierStyle = it },
                        )
                        if (style == null) Text("Detected: ${carrierLabel(detected)} codes", style = Type.cardBody, color = p.ink2)
                        Text(
                            buildAnnotatedString {
                                val mono = SpanStyle(fontFamily = Mono, color = p.ink)
                                if (code != null) {
                                    append("Turn on dials ")
                                    withStyle(mono) { append(code) }
                                } else {
                                    append("Turn on dials your carrier's forwarding code")
                                }
                                append("; turn off dials ")
                                withStyle(mono) { append(CallForwarding.disableCode(effective)) }
                                append(".")
                            },
                            style = Type.cardBody, color = p.ink2,
                        )
                    }
                }
            }
        }
    }
}

private fun carrierLabel(style: CarrierStyle) = if (style == CarrierStyle.Verizon) "Verizon" else "standard"

private data class Access(val icon: ImageVector, val title: String, val why: String)

@Composable
private fun Permissions(context: Context, refresh: Int, onChanged: () -> Unit) {
    val icons = mapOf(
        "Texts" to Icons.AutoMirrored.Outlined.Chat, "Contacts" to Icons.Outlined.Person, "Calendar" to Icons.Outlined.Event,
        "Phone" to Icons.Outlined.Call, "Microphone" to Icons.Outlined.Mic, "Notifications" to Icons.Outlined.Notifications,
    )
    val runtimePerms = PermissionGroups.ALL.map { g -> Access(icons[g.name] ?: Icons.Outlined.Notifications, g.name, g.why) to g.permissions }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }
    val missing = remember(refresh) {
        runtimePerms.flatMap { it.second }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }
    EmrysCard(padding = PaddingValues(0.dp)) {
        runtimePerms.forEach { (access, perms) ->
            val granted = remember(refresh) { perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
            PermissionRow(access, granted) { request.launch(perms.toTypedArray()) }
        }
        val listener = remember(refresh) { notificationAccess(context) || EmrysNotificationListener.connected }
        PermissionRow(Access(Icons.Outlined.NotificationsActive, "Notification access", "Read and reply to messages in any app"), listener) {
            context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val operator = remember(refresh) { ScreenOperator.connected }
        PermissionRow(Access(Icons.Outlined.TouchApp, "App operator", "Let Emrys tap and type in other apps (accessibility)"), operator) {
            context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val roles = context.getSystemService(RoleManager::class.java)
        val screening = remember(refresh) { roles?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true }
        PermissionRow(Access(Icons.Outlined.PhoneInTalk, "Call screening", "See who's calling"), screening) {
            roles?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)?.let { role.launch(it) }
        }
        val assistant = remember(refresh) { roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }
        PermissionRow(Access(Icons.Outlined.RecordVoiceOver, "Default assistant", "Long-press Home or Power to talk to Emrys"), assistant) {
            context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        FlowRow(
            Modifier.padding(horizontal = RowInset, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (missing.isNotEmpty()) Keycap("Allow all", { request.launch(missing.toTypedArray()) }, tone = KeyTone.Accent)
            Keycap("Open Emrys's app info", {
                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            })
        }
    }
}

private fun notificationAccess(context: Context): Boolean {
    val flat = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    val me = ComponentName(context, EmrysNotificationListener::class.java)
    return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
}

/** One thing Emrys can use: a quiet glyph, what it is and why, then "On" with a sage dot or an Allow key; a hairline under it. */
@Composable
private fun PermissionRow(access: Access, granted: Boolean, onGrant: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = RowInset, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(access.icon, contentDescription = null, tint = if (granted) p.ink else p.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(access.title, style = Type.bodyLg, color = p.ink)
            Text(access.why, style = Type.cardBody, color = p.ink2)
        }
        if (granted) StatusTag("On", Tone.Green) else Keycap("Allow", onGrant)
    }
    RowRule()
}

/** A choice of a few options as filter chips that wrap onto a second line rather than cut a label short. */
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> FilterChip(label, value == selected, { onSelect(value) }, Modifier.heightIn(min = 40.dp)) }
    }
}

/** A setting row: a Geist label, an optional grey [detail] under it and a slim switch; the whole row toggles. */
@Composable
private fun Toggle(label: String, value: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(value, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = RowInset, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = Type.bodyLg, color = p.ink)
            detail?.let { Text(it, style = Type.cardBody, color = p.ink2) }
        }
        EmrysToggle(value, null)
    }
}
