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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    Column(Modifier.fillMaxSize().background(p.bg).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("SETTINGS", style = Label, color = p.accent)
        Text("Connect Rivet", style = MaterialTheme.typography.headlineSmall, color = p.ink)
        Spacer(Modifier.size(12.dp))

        Section("Brain") {
            OutlinedTextField(url, { url = it }, label = { Text("Brain URL") }, placeholder = { Text("https://rivet-brain.fly.dev") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, label = { Text(if (hasToken) "Device token (saved, encrypted)" else "Device token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(session, { session = it }, label = { Text("Session id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        s.gatewayUrl = url
                        if (token.isNotBlank()) s.deviceToken = token
                        if (session.isNotBlank()) s.sessionId = session
                        token = ""
                        RivetService.start(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                ) { Text(if (state.connection == Connection.Offline) "Save & connect" else "Save & reconnect") }
                if (state.connection != Connection.Offline) OutlinedButton(onClick = { RivetService.stop(context) }) { Text("Disconnect") }
            }
            Text(
                when (state.connection) {
                    Connection.Online -> "Connected."
                    Connection.Connecting -> "Connecting…"
                    Connection.Offline -> state.lastError ?: "Not connected."
                },
                style = MaterialTheme.typography.bodySmall, color = if (state.connection == Connection.Online) p.ok else p.ink2,
            )
        }

        Section("Behaviour") {
            Toggle("Read replies aloud", speak) { speak = it; s.speakReplies = it }
            Toggle("Connect automatically (also after reboot)", auto) { auto = it; s.autoConnect = it }
            Toggle("Offer replies to new messages (you approve each one)", offer) { offer = it; s.offerReplies = it }
        }

        Section("Call answering") {
            CallAnswering(runtime, refresh)
        }

        Section("Permissions") {
            Permissions(context, refresh) { refresh++ }
        }

        Text(
            "On Android 13+, sideloaded apps must first be allowed restricted settings: App info › ⋮ › Allow restricted " +
                "settings, then turn on Notification access and the App operator.",
            style = MaterialTheme.typography.bodySmall, color = p.ink2, modifier = Modifier.padding(vertical = 8.dp),
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

    Text(
        "Callers still dial your number. When this is on, your carrier forwards calls to Rivet's answering line on " +
            "Telnyx, Rivet takes a message, and you get it here. (Android doesn't let apps speak on ordinary calls.)",
        style = MaterialTheme.typography.bodySmall, color = p.ink2,
    )
    OutlinedTextField(
        number, { number = it }, label = { Text("Rivet's answering number") }, placeholder = { Text("+17605550100") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    if (number.isBlank()) {
        TextButton(onClick = { runtime.send("Set up call answering so you can answer my calls.") }) {
            Text("Ask Rivet to set up an answering number")
        }
    }
    Text("ANSWER", style = Label, color = p.ink2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == ForwardMode.Unanswered, { mode = ForwardMode.Unanswered }, label = { Text("Calls I don't pick up") })
        FilterChip(mode == ForwardMode.Always, { mode = ForwardMode.Always }, label = { Text("All calls") })
    }
    Text("CARRIER CODES", style = Label, color = p.ink2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(style == null, { style = null; s.carrierStyle = null }, label = { Text("Auto (${carrierLabel(detected)})") })
        FilterChip(style == CarrierStyle.Gsm, { style = CarrierStyle.Gsm; s.carrierStyle = style }, label = { Text("Standard") })
        FilterChip(style == CarrierStyle.Verizon, { style = CarrierStyle.Verizon; s.carrierStyle = style }, label = { Text("Verizon") })
    }
    Text(
        when {
            forwardedTo.isBlank() -> "Off."
            forwardedTo != number.trim() -> "On, but forwarding to $forwardedTo. Turn on again to use the new number."
            else -> "On: forwarding to $forwardedTo."
        },
        style = MaterialTheme.typography.bodyMedium, color = if (forwardedTo.isBlank()) p.ink2 else p.ok,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
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
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
        ) { Text("Turn on") }
        OutlinedButton(onClick = {
            dial(CallForwarding.disableCode(effective)) {
                s.forwardedTo = ""
                forwardedTo = ""
            }
        }) { Text("Turn off") }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.rec) }
    Text(
        (code?.let { "Turn on dials $it" } ?: "Turn on dials your carrier's forwarding code") +
            "; turn off dials ${CallForwarding.disableCode(effective)}. Your carrier confirms on screen. Forwarded calls " +
            "may count against your plan; calls Rivet answers use Telnyx minutes.",
        style = MaterialTheme.typography.bodySmall, color = p.ink2,
    )
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
    TextButton(onClick = {
        context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }) { Text("Open Rivet's app info") }
}

private fun notificationAccess(context: Context): Boolean {
    val flat = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    val me = ComponentName(context, RivetNotificationListener::class.java)
    return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.ink)
            Text(why, style = MaterialTheme.typography.bodySmall, color = p.ink2)
        }
        if (granted) Text("ON", style = Label, color = p.ok)
        else OutlinedButton(onClick = onGrant) { Text("Allow") }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = p.ink, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(p.card).border(1.dp, p.line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title.uppercase(), style = Label, color = p.ink2)
        content()
    }
}
