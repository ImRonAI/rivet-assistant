package com.hiron.rivet.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.device.CallForwarding
import com.hiron.rivet.device.CarrierStyle
import com.hiron.rivet.device.ForwardMode
import com.hiron.rivet.device.RivetNotificationListener
import com.hiron.rivet.device.ScreenOperator
import com.hiron.rivet.service.RivetService

@Composable
fun SettingsScreen(runtime: RivetRuntime, state: RivetState) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val s = runtime.settings
    var url by remember { mutableStateOf(s.gatewayUrl) }
    var token by remember { mutableStateOf("") }
    var session by remember { mutableStateOf(s.sessionId) }
    var speak by remember { mutableStateOf(s.speakReplies) }
    var auto by remember { mutableStateOf(s.autoConnect) }
    var offer by remember { mutableStateOf(s.offerReplies) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val hasToken = remember(refresh, token) { s.deviceToken.isNotBlank() }
    // A ready-to-use build hides the connection details; they open only when something needs changing.
    var advanced by remember { mutableStateOf(!s.gateway().complete) }
    val (linkColor, linkMeta) = when (state.connection) {
        Connection.Online -> p.ok to "Online"
        Connection.Connecting -> p.amb to "Linking"
        Connection.Offline -> p.rec to "Offline"
    }

    Column(
        Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HudCard("Link", pip = linkColor, live = state.connection == Connection.Connecting, meta = linkMeta, metaColor = linkColor) {
            Text(
                when (state.connection) {
                    Connection.Online -> "Connected to Rivet"
                    Connection.Connecting -> "Connecting to Rivet…"
                    Connection.Offline -> state.lastError ?: "Not connected"
                },
                style = Type.headline, color = p.ink,
            )
            Readout("Brain", s.gatewayUrl.removePrefix("https://").removePrefix("wss://").trimEnd('/').ifBlank { "not set" })
            Readout("Session", s.sessionId.ifBlank { "not set" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.connection == Connection.Offline) {
                    Keycap("Connect", { RivetService.start(context) }, tone = KeyTone.Accent)
                } else {
                    Keycap("Disconnect", { RivetService.stop(context) })
                }
                SysKey("Details", advanced, { advanced = it })
            }
        }

        if (advanced) HudCard("Connection details") {
            RecessedField(url, { url = it }, label = "Brain URL", placeholder = "https://rivet-brain.fly.dev",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            RecessedField(token, { token = it }, label = if (hasToken) "Device token (saved, encrypted)" else "Device token",
                placeholder = if (hasToken) "Leave blank to keep it" else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            RecessedField(session, { session = it }, label = "Session id")
            Keycap(
                if (state.connection == Connection.Offline) "Save & connect" else "Save & reconnect",
                {
                    s.gatewayUrl = url
                    if (token.isNotBlank()) s.deviceToken = token
                    if (session.isNotBlank()) s.sessionId = session
                    token = ""
                    RivetService.start(context)
                },
                tone = KeyTone.Accent,
            )
        }

        HudCard("Behaviour") {
            Toggle("Read replies aloud", speak) { speak = it; s.speakReplies = it }
            Toggle("Connect automatically, also after a reboot", auto) { auto = it; s.autoConnect = it }
            Toggle("Offer replies to new messages (you approve each one)", offer) { offer = it; s.offerReplies = it }
        }

        CallAnswering(runtime, refresh)

        HudCard("Permissions") {
            Permissions(context, refresh) { refresh++ }
        }

        Text(
            "On Android 13+, sideloaded apps must first be allowed restricted settings: App info › ⋮ › Allow restricted " +
                "settings, then turn on Notification access and the App operator.",
            style = Type.body, color = p.ink2, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
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
    var error by remember { mutableStateOf<String?>(null) }
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
    HudCard("Call answering", pip = if (on) p.ok else p.ink2, meta = if (on) "On" else "Off", metaColor = if (on) p.ok else null) {
        Text(
            "Callers still dial your number. When this is on, your carrier forwards calls to Rivet's answering line on " +
                "Telnyx, Rivet takes a message, and you get it here. (Android doesn't let apps speak on ordinary calls.)",
            style = Type.body, color = p.ink2,
        )
        RecessedField(
            number, { number = it }, label = "Rivet's answering number", placeholder = "+17605550100",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        if (number.isBlank()) {
            Keycap("Ask Rivet to set up an answering number", { runtime.send("Set up call answering so you can answer my calls.") })
        }
        Text("ANSWER", style = Type.labelSm, color = p.ink2)
        Segmented(
            listOf(ForwardMode.Unanswered to "Unanswered calls", ForwardMode.Always to "Every call"),
            mode, { mode = it },
        )
        Text("CARRIER CODES", style = Type.labelSm, color = p.ink2)
        Segmented(
            listOf<Pair<CarrierStyle?, String>>(null to "Auto", CarrierStyle.Gsm to "Standard", CarrierStyle.Verizon to "Verizon"),
            style, { style = it; s.carrierStyle = it },
        )
        if (style == null) Text("Detected: ${carrierLabel(detected)} codes", style = Type.mono, color = p.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pip(if (on) p.ok else p.ink2, size = 7.dp)
            Text(
                when {
                    !on -> "Off."
                    forwardedTo != number.trim() -> "On, but forwarding to $forwardedTo. Turn on again to use the new number."
                    else -> "On: forwarding to $forwardedTo."
                },
                style = Type.bodyLg, color = if (on) p.ink else p.ink2,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        error?.let { Text(it, style = Type.body, color = p.rec) }
        Text(
            (code?.let { "Turn on dials $it" } ?: "Turn on dials your carrier's forwarding code") +
                "; turn off dials ${CallForwarding.disableCode(effective)}. Your carrier confirms on screen. Forwarded calls " +
                "may count against your plan; calls Rivet answers use Telnyx minutes.",
            style = Type.body, color = p.ink2,
        )
    }
}

private fun carrierLabel(style: CarrierStyle) = if (style == CarrierStyle.Verizon) "Verizon" else "standard"

@Composable
private fun Permissions(context: Context, refresh: Int, onChanged: () -> Unit) {
    val runtimePerms = buildList {
        add(Triple("Texts", "Send and read SMS", listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)))
        add(Triple("Contacts", "Find people by name", listOf(Manifest.permission.READ_CONTACTS)))
        add(Triple("Calendar", "Read and add events", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)))
        add(Triple("Phone", "Place calls and read recent calls", listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)))
        if (Build.VERSION.SDK_INT >= 33) add(Triple("Notifications", "Show approvals and reminders", listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }
    val missing = remember(refresh) {
        runtimePerms.flatMap { it.third }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }
    if (missing.isNotEmpty()) Keycap("Allow all", { request.launch(missing.toTypedArray()) }, tone = KeyTone.Accent)
    runtimePerms.forEach { (title, why, perms) ->
        val granted = remember(refresh) { perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
        PermissionRow(title, why, granted) { request.launch(perms.toTypedArray()) }
    }
    val listener = remember(refresh) { notificationAccess(context) || RivetNotificationListener.connected }
    PermissionRow("Notification access", "Read and reply to messages in any app", listener) {
        context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    val operator = remember(refresh) { ScreenOperator.connected }
    PermissionRow("App operator", "Let Rivet tap and type in other apps (accessibility)", operator) {
        context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    val roles = context.getSystemService(RoleManager::class.java)
    val screening = remember(refresh) { roles?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true }
    PermissionRow("Call screening", "See who's calling so Rivet can text back", screening) {
        roles?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)?.let { role.launch(it) }
    }
    val assistant = remember(refresh) { roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }
    PermissionRow("Default assistant", "Long-press Home or Power to talk to Rivet", assistant) {
        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
    Keycap("Open Rivet's app info", {
        context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    })
}

private fun notificationAccess(context: Context): Boolean {
    val flat = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    val me = ComponentName(context, RivetNotificationListener::class.java)
    return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = Type.labelLg, color = p.ink)
            Text(why, style = Type.body, color = p.ink2)
        }
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pip(p.ok, size = 6.dp)
                Text("ON", style = Type.labelMd, color = p.ok)
            }
        } else {
            Keycap("Allow", onGrant)
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.bodyLg, color = p.ink, modifier = Modifier.weight(1f).padding(end = 12.dp))
        RivetSwitch(value, onChange)
    }
}
