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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Event
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hiron.emrys.BuildConfig
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.core.PermAction
import com.hiron.emrys.core.PermissionGroups
import com.hiron.emrys.device.CallForwarding
import com.hiron.emrys.device.CarrierStyle
import com.hiron.emrys.device.EmrysNotificationListener
import com.hiron.emrys.device.ForwardMode
import com.hiron.emrys.device.ScreenOperator

/** A section's serif title. */
private val SectionTitle = Type.step.copy(fontSize = 28.sp, lineHeight = 32.sp)

/** The inset of a row inside a settings card, and of the hairlines between rows. */
private val RowInset = 18.dp

/**
 * Settings, set like a table of contents: each section a quiet figure on a hairline and a serif title, over glass
 * cards whose rows carry Geist labels, grey details, slim switches and hairline dividers.
 */
@Composable
fun SettingsScreen(runtime: EmrysRuntime) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(horizontal = gutter())
            .padding(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Section(1, "Voice") { Voice(runtime, refresh) }

        Section(2, "Permissions") { AgentPermissions(runtime, refresh) }

        Section(3, "Call answering") { CallAnswering(runtime, refresh) }

        Section(4, "What Emrys can use") {
            Permissions(context, refresh) { refresh++ }
            InfoStrip(
                "On Android 13 and later, first allow restricted settings for Emrys: App info › ⋮ › Allow restricted " +
                    "settings. Then turn on Notification access and the App operator.",
            )
        }

        Section(5, "About") { About() }
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

/**
 * What the agents may do without asking: each kind of action is a `perm_*` dynamic variable every new session
 * starts with, "granted" when on and "prompt" when off.
 */
@Composable
private fun AgentPermissions(runtime: EmrysRuntime, refresh: Int) {
    val s = runtime.settings
    EmrysCard(padding = PaddingValues(0.dp)) {
        PermAction.entries.forEachIndexed { i, action ->
            if (i > 0) RowRule()
            var on by remember(refresh) { mutableStateOf(s.granted(action)) }
            Toggle(action.label, on, detail = if (on) "Always allowed" else "Asks you first") {
                on = it
                s.grant(action, it)
            }
        }
    }
    InfoStrip("On: Emrys does it without asking. Off: it asks you first. Changes apply from the next conversation.")
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
                "Callers still dial your number. When this is on, your carrier forwards calls to Emrys's answering line. " +
                    "(Android doesn't let apps speak on ordinary calls.)",
                style = Type.cardBody, color = p.ink2,
            )
        }
        RowRule()
        Column(Modifier.padding(horizontal = RowInset, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RecessedField(
                number, { number = it }, label = "Emrys's answering number", placeholder = "+17605550100",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
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
