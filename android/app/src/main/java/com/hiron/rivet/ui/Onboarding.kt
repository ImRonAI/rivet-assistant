package com.hiron.rivet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hiron.rivet.core.RivetRuntime

/** One system on the power-on checklist: what Rivet uses, why, and the Android permissions behind it. */
data class Capability(val name: String, val why: String, val permissions: List<String>)

val CAPABILITIES = buildList {
    add(Capability("Texts", "Reads and sends texts, always with your OK.", listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)))
    add(Capability("Calendar", "Checks your day and adds events.", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)))
    add(Capability("Contacts", "Finds people by name.", listOf(Manifest.permission.READ_CONTACTS)))
    add(Capability("Calls", "Sees recent calls and calls people back.", listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)))
    if (Build.VERSION.SDK_INT >= 33) {
        add(Capability("Notifications", "Brings approvals and reminders to you.", listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
}

/**
 * Rivet's first run: the robot on its stage with a greeting, the power-on checklist of what Rivet will use
 * and why, and one key that asks for all of it at once.
 */
@Composable
fun Onboarding(runtime: RivetRuntime, onFinish: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onFinish() }
    val granted = remember {
        CAPABILITIES.map { c -> c.permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Brush.radialGradient(listOf(p.raised, p.bg))).testTag(STAGE_TAG)) {
            RobotView(runtime, p.dark, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(p.bg, Color.Transparent))))
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, p.bg))),
            )
            Text(
                "R-7 UTILITY UNIT · FIRST POWER-ON",
                style = Type.labelSm, color = p.ink2,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 20.dp, top = 16.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildAnnotatedString {
                    append("HI, I'M RIV")
                    withStyle(SpanStyle(color = p.accent)) { append("E") }
                    append("T")
                    withStyle(SpanStyle(color = p.accent)) { append(".") }
                },
                style = Type.greeting, color = p.ink,
            )
            Text(
                "I run your texts, calendar, calls and messages, and I ask before anything goes out.",
                style = Type.bodyLg, color = p.ink2,
            )
        }
        Column(
            Modifier.fillMaxWidth().background(p.raised).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HudCard("Power-on checklist", index = "00", meta = "${granted.count { it }}/${CAPABILITIES.size} on") {
                CAPABILITIES.forEachIndexed { i, c -> ChecklistRow(i + 1, c, granted[i]) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Keycap("Not now", onFinish, Modifier.weight(1f))
                Keycap("Get Started", { ask.launch(CAPABILITIES.flatMap { it.permissions }.toTypedArray()) }, Modifier.weight(2f), tone = KeyTone.Accent)
            }
        }
    }
}

@Composable
private fun ChecklistRow(n: Int, c: Capability, on: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(n), style = Type.labelSm, color = p.ink2)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = Type.labelLg, color = p.ink)
            Text(c.why, style = Type.body, color = p.ink2)
        }
        Spacer(Modifier.size(8.dp))
        Pip(if (on) p.ok else p.ink2.copy(alpha = 0.5f), size = 6.dp)
        Text(if (on) "ON" else "OFF", style = Type.labelSm, color = if (on) p.ok else p.ink2)
    }
}
