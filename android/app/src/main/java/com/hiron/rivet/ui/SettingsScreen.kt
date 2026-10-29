package com.hiron.rivet.ui

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
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.PhoneForwarded
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hiron.rivet.BuildConfig
import com.hiron.rivet.R
import com.hiron.rivet.core.BrainModel
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.Decision
import com.hiron.rivet.core.PermissionGroups
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.core.StandingRule
import com.hiron.rivet.core.isE164
import com.hiron.rivet.device.CallForwarding
import com.hiron.rivet.device.CarrierStyle
import com.hiron.rivet.device.ForwardMode
import com.hiron.rivet.device.RivetNotificationListener
import com.hiron.rivet.device.ScreenOperator
import com.hiron.rivet.service.RivetService
import java.time.Instant
import java.time.ZoneId

@Composable
fun SettingsScreen(runtime: RivetRuntime, state: RivetState) {
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
        Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section("Connection", 0) {
            ConnectionCard(state, s.gatewayUrl, s.sessionId, advanced, { advanced = it }, context)
        }
        AnimatedVisibility(advanced, enter = expandVertically(tween(Motion.BASE)) + fadeIn(), exit = shrinkVertically(tween(Motion.BASE)) + fadeOut()) {
            RivetCard {
                Eyebrow("CONNECTION DETAILS", tone = Tone.Neutral, dot = false)
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    RecessedField(url, { url = it }, label = "Server address", placeholder = "https://rivet-brain.fly.dev",
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
                            RivetService.start(context)
                        },
                        trailingArrow = false,
                    )
                }
            }
        }

        Section("Voice", 1, card = false) { Voice(runtime, refresh) }

        Section("How Rivet behaves", 2, card = false) { Behaviour(runtime, state) { refresh++ } }

        Section("Approval rules", 3) { ApprovalRules(runtime, state) }

        Section("Call answering", 4) { CallAnswering(runtime, refresh) }

        Section("What Rivet can use", 5, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            Permissions(context, refresh) { refresh++ }
        }
        InfoStrip(
            "On Android 13 and later, first allow restricted settings for Rivet: App info › ⋮ › Allow restricted " +
                "settings. Then turn on Notification access and the App operator.",
            Modifier.enter(6),
        )

        Section("About", 7) { About() }
    }
}

/** A section label with its card (or, with [card] false, a column of cards) under it. */
@Composable
private fun Section(
    name: String,
    index: Int,
    card: Boolean = true,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 14.dp).enter(index)) {
        SectionLabel(name, Modifier.padding(start = 4.dp, bottom = 10.dp))
        if (card) {
            RivetCard(padding = padding, content = content)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun ConnectionCard(
    state: RivetState,
    gatewayUrl: String,
    sessionId: String,
    advanced: Boolean,
    onAdvanced: (Boolean) -> Unit,
    context: Context,
) {
    val p = LocalPalette.current
    val (tone, tag) = when (state.connection) {
        Connection.Online -> Tone.Green to "ONLINE"
        Connection.Connecting -> Tone.Amber to "CONNECTING"
        Connection.Offline -> Tone.Red to "OFFLINE"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconWell(Icons.Outlined.Link, tone = tone, size = 44.dp, iconSize = 22.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            when (state.connection) {
                Connection.Online -> "Connected to Rivet"
                Connection.Connecting -> "Connecting to Rivet…"
                Connection.Offline -> state.lastError ?: "Not connected"
            },
            style = Type.title, color = p.ink, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        StatusTag(tag, tone)
    }
    Spacer(Modifier.height(14.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.field)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Readout("Server", gatewayUrl.removePrefix("https://").removePrefix("wss://").trimEnd('/').ifBlank { "not set" })
        Rule(color = p.line)
        Readout("Session", sessionId.ifBlank { "not set" })
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.connection == Connection.Offline) {
            Keycap("Connect", { RivetService.start(context) }, tone = KeyTone.Accent)
        } else {
            SecondaryKey("Disconnect", { RivetService.stop(context) }, Modifier.weight(1f), height = 44.dp, radius = 12.dp)
        }
        SysKey("Details", advanced, onAdvanced, Modifier.weight(1f), height = 44.dp, radius = 12.dp)
    }
}

@Composable
private fun Voice(runtime: RivetRuntime, refresh: Int) {
    val s = runtime.settings
    var voiceModel by remember(refresh) { mutableStateOf(s.voiceModel) }
    FeatureCard(
        Icons.Outlined.GraphicEq, "Talk key", "Shows the mic key, so you can talk to Rivet out loud.",
        tone = Tone.Accent,
        trailing = {
            RivetToggle(voiceModel, {
                voiceModel = it
                s.voiceModel = it
            })
        },
    )
}

@Composable
private fun Behaviour(runtime: RivetRuntime, state: RivetState, onChanged: () -> Unit) {
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
    // True once undone; false when it couldn't be, because the rules need Rivet connected.
    var undone by remember { mutableStateOf<Boolean?>(null) }
    var recommending by rememberSaveable { mutableStateOf(false) }
    FeatureCard(
        Icons.Outlined.Tune, "Use recommended settings", "See what Rivet suggests and choose what to change.",
        tone = Tone.Accent, trailing = { Keycap("Review", { recommending = true }) },
        nested = undo?.let { u ->
            {
                Column {
                    TextKey("Undo recommended settings", {
                        undone = runtime.undoRecommended()
                        version++
                        onChanged()
                    }, Modifier.offset { IntOffset(-8.dp.roundToPx(), 0) })
                    Text("Puts back: ${u.titles().joinToString(", ")}", style = Type.cardBody, color = p.ink2)
                }
            }
        },
    )
    when (undone) {
        true -> InfoStrip("Recommended settings undone.", icon = Icons.Outlined.CheckCircle, tone = Tone.Green)
        false -> InfoStrip("Connect to Rivet to undo. Only Rivet can remove the rules these settings made.", tone = Tone.Amber)
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
    RivetCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        Toggle("Read replies aloud", speak) { speak = it; s.speakReplies = it }
        Rule(color = p.line)
        Toggle("Read replies aloud in the assistant overlay", overlay) { overlay = it; s.speakInOverlay = it }
        Rule(color = p.line)
        Toggle("Connect automatically, also after a reboot", auto) { auto = it; s.autoConnect = it }
        Rule(color = p.line)
        Toggle("Use ElevenLabs agents", agents, detail = "Talk and type with Rivet's ElevenLabs agents. Off: Rivet's own brain.") {
            agents = it
            runtime.useAgents(it)
        }
        Rule(color = p.line)
        Toggle(
            "Suggest searches as you type", suggest,
            detail = "What you type is sent to Brave Search for suggestions. Brave keeps search logs for up to 90 days.",
        ) { suggest = it; s.searchSuggestions = it }
    }
    BrainModelPlate(state, runtime::setModel)
}

/** Which model Rivet's own brain runs; only the brain's `model` event changes what it shows. */
@Composable
internal fun BrainModelPlate(state: RivetState, onChoose: (BrainModel) -> Boolean) {
    val p = LocalPalette.current
    val online = state.connection == Connection.Online
    // The model asked for, until the brain says which one runs or why it couldn't switch.
    var pending by remember { mutableStateOf<BrainModel?>(null) }
    LaunchedEffect(state.brainModel, state.modelError) { pending = null }
    RivetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(Icons.Outlined.Psychology, tone = Tone.Cyan)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Rivet's own brain", style = Type.cardTitle, color = p.ink)
                Text("The model it thinks with when the ElevenLabs agents are off.", style = Type.cardBody, color = p.ink2)
            }
        }
        Spacer(Modifier.height(8.dp))
        BrainModel.entries.forEach { model ->
            val usable = online && model in state.availableModels
            val note = when {
                model == state.brainModel -> "In use"
                model == pending -> "Switching…"
                !online -> null
                model !in state.availableModels -> "Not set up on the brain"
                else -> null
            }
            // Only the control dims when it can't be chosen; the note explaining why stays legible.
            val dim = Modifier.alpha(if (usable || model == state.brainModel) 1f else 0.5f)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(model == state.brainModel, enabled = usable, role = Role.RadioButton) {
                        if (model != state.brainModel && onChoose(model)) pending = model
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(dim) { RadioDot(model == state.brainModel) }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(model.label, style = Type.bodyLg, color = p.ink, modifier = dim)
                    note?.let { Text(it, style = Type.cardBody, color = if (model == state.brainModel) p.ok else p.ink2) }
                }
            }
        }
        when {
            !online -> Text("Connect to change.", style = Type.cardBody, color = p.ink2)
            state.brainModel == null -> Text("Rivet's brain hasn't said which models it offers.", style = Type.cardBody, color = p.ink2)
        }
        state.modelError?.let { InfoStrip(it, Modifier.padding(top = 8.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Amber) }
        Text("Background tasks always use Rivet's own brain.", style = Type.cardBody, color = p.ink2, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The brain's standing rules, each removable; only the brain changes them, so removing needs a connection. */
@Composable
internal fun ApprovalRules(runtime: RivetRuntime, state: RivetState) {
    val p = LocalPalette.current
    val online = state.connection == Connection.Online
    val rules = state.rules.rules
    if (rules.isEmpty()) {
        Text("No rules yet. When Rivet asks, tick “Don't ask again” to make one.", style = Type.cardBody, color = p.ink2)
    }
    rules.forEachIndexed { i, rule ->
        if (i > 0) Rule(color = p.line)
        RuleRow(rule, state.names, removing = rule.key in state.removingRules, online = online) { runtime.revokeRule(rule) }
    }
    if (!online) InfoStrip("Connect to Rivet to change rules.", Modifier.padding(top = 12.dp))
    state.rulesError?.let { InfoStrip(it, Modifier.padding(top = 12.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
}

@Composable
private fun RuleRow(rule: StandingRule, names: Map<String, String>, removing: Boolean, online: Boolean, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val number = rule.target.scope["to"] ?: rule.target.scope["number"]
    val since = "since ${ruleDate(rule.createdAt)}"
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconWell(iconFor(rule.target.tool), tone = if (rule.decision == Decision.Allow) Tone.Amber else Tone.Red)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Plain.people(rule.label, names), style = Type.cardTitle, color = p.ink)
            Text(if (number != null && Plain.numbersIn(number).isNotEmpty()) "${Plain.phone(number)} · $since" else since, style = Type.cardBody, color = p.ink2)
            if (rule.decision == Decision.Allow) StatusTag("ALWAYS ALLOW", Tone.Amber) else StatusTag("ALWAYS DENY", Tone.Red)
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

/** Who made this, which build it is, and where to get help. */
@Composable
private fun About() {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).glow(p.accent.copy(alpha = 0.10f), 12.dp).clip(CircleShape).background(p.field).border(1.dp, p.edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.rivet_head), contentDescription = null, modifier = Modifier.size(48.dp).padding(4.dp))
        }
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    append("RIV")
                    withStyle(SpanStyle(color = p.accent)) { append("E") }
                    append("T")
                },
                style = Type.brand, color = p.ink,
            )
            Text("Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", style = Type.mono, color = p.ink2)
        }
    }
    Spacer(Modifier.height(14.dp))
    Rule(color = p.line)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Readout("Made by", "Ron AI · hi-ron.com")
        Readout("Support", "support@hi-ron.com")
    }
}

@Composable
private fun CallAnswering(runtime: RivetRuntime, refresh: Int) {
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
        error = if (granted) null else "Rivet needs the Phone permission to dial your carrier's forwarding code."
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconWell(Icons.AutoMirrored.Outlined.PhoneForwarded, tone = if (on) Tone.Green else Tone.Neutral, size = 44.dp, iconSize = 22.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            when {
                !on -> "Off."
                forwardedTo != number.trim() -> "On, but forwarding to $forwardedTo. Turn on again to use the new number."
                else -> "On: forwarding to $forwardedTo."
            },
            style = Type.title, color = if (on) p.ink else p.ink2, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        StatusTag(if (on) "ON" else "OFF", if (on) Tone.Green else Tone.Neutral)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Callers still dial your number. When this is on, your carrier forwards calls to Rivet's answering line, " +
            "Rivet takes a message, and you get it here. (Android doesn't let apps speak on ordinary calls.)",
        style = Type.cardBody, color = p.ink2,
    )
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RecessedField(
            number, { number = it }, label = "Rivet's answering number", placeholder = "+17605550100",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        if (number.isBlank()) {
            Keycap("Ask Rivet to set one up", { runtime.send("Set up call answering so you can answer my calls.") })
        }
        RecessedField(
            own, { own = it; s.ownNumber = it.trim() }, label = "Your own number, for Take over", placeholder = "+18015550100",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        if (own.isNotBlank() && !isE164(own.trim())) Text("Use the +1… form, like +18015550100.", style = Type.cardBody, color = p.amb)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ANSWER", style = Type.labelSm, color = p.ink2)
            Choices(
                listOf(ForwardMode.Unanswered to "Unanswered calls", ForwardMode.Always to "Every call"),
                mode, { mode = it },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Keycap(
                "Turn on",
                {
                    val target = number.trim()
                    if (code == null) {
                        error = "Enter Rivet's answering number in +1… form first."
                    } else {
                        dial(code) {
                            s.answeringNumber = target
                            s.answeringMode = mode
                            s.forwardedTo = target
                            forwardedTo = target
                        }
                    }
                },
                tone = KeyTone.Accent,
            )
            Keycap("Turn off", {
                dial(CallForwarding.disableCode(effective)) {
                    s.forwardedTo = ""
                    forwardedTo = ""
                }
            })
        }
        error?.let { InfoStrip(it, icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
        Text(
            "Turn on dials your carrier's forwarding code, and your carrier confirms on screen. Forwarded calls may count " +
                "against your plan; calls Rivet answers use minutes on Rivet's answering line.",
            style = Type.cardBody, color = p.ink2,
        )
        // The dial codes are for people who want to check what Rivet dials; most never need them.
        TextKey(if (codes) "Hide carrier codes" else "Show carrier codes", { codes = !codes }, Modifier.offset { IntOffset(-8.dp.roundToPx(), 0) })
        if (codes) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CARRIER", style = Type.labelSm, color = p.ink2)
                Choices(
                    listOf<Pair<CarrierStyle?, String>>(null to "Auto", CarrierStyle.Gsm to "Standard", CarrierStyle.Verizon to "Verizon"),
                    style, { style = it; s.carrierStyle = it },
                )
                if (style == null) Text("Detected: ${carrierLabel(detected)} codes", style = Type.mono, color = p.ink2)
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
    runtimePerms.forEach { (access, perms) ->
        val granted = remember(refresh) { perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
        PermissionRow(access, granted) { request.launch(perms.toTypedArray()) }
    }
    val listener = remember(refresh) { notificationAccess(context) || RivetNotificationListener.connected }
    PermissionRow(Access(Icons.Outlined.NotificationsActive, "Notification access", "Read and reply to messages in any app"), listener) {
        context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    val operator = remember(refresh) { ScreenOperator.connected }
    PermissionRow(Access(Icons.Outlined.TouchApp, "App operator", "Let Rivet tap and type in other apps (accessibility)"), operator) {
        context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    val roles = context.getSystemService(RoleManager::class.java)
    val screening = remember(refresh) { roles?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true }
    PermissionRow(Access(Icons.Outlined.PhoneInTalk, "Call screening", "See who's calling"), screening) {
        roles?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)?.let { role.launch(it) }
    }
    val assistant = remember(refresh) { roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }
    PermissionRow(Access(Icons.Outlined.RecordVoiceOver, "Default assistant", "Long-press Home or Power to talk to Rivet"), assistant, last = true) {
        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (missing.isNotEmpty()) Keycap("Allow all", { request.launch(missing.toTypedArray()) }, tone = KeyTone.Accent)
        Keycap("Open Rivet's app info", {
            context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        })
    }
}

private fun notificationAccess(context: Context): Boolean {
    val flat = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    val me = ComponentName(context, RivetNotificationListener::class.java)
    return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
}

@Composable
private fun PermissionRow(access: Access, granted: Boolean, last: Boolean = false, onGrant: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconWell(access.icon, tone = if (granted) Tone.Green else Tone.Neutral)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(access.title, style = Type.cardTitle, color = p.ink)
            Text(access.why, style = Type.cardBody, color = p.ink2)
        }
        if (granted) StatusTag("ON", Tone.Green) else Keycap("Allow", onGrant)
    }
    if (!last) Rule(color = p.line)
}

/** A choice of a few options as filter chips that wrap onto a second line rather than cut a label short. */
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> FilterChip(label, value == selected, { onSelect(value) }, Modifier.heightIn(min = 40.dp)) }
    }
}

/** A setting row: the whole row toggles. [detail] is a line under the label. */
@Composable
private fun Toggle(label: String, value: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value, role = Role.Switch, onValueChange = onChange).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = Type.bodyLg, color = p.ink)
            detail?.let { Text(it, style = Type.cardBody, color = p.ink2) }
        }
        RivetToggle(value, null)
    }
}
