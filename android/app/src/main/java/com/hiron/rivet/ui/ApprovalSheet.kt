package com.hiron.rivet.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.hiron.rivet.core.ApprovalRequest
import com.hiron.rivet.core.Protocol

/**
 * Review sheet for one lane's pending approvals. The brain denies any approval in the lane that is not
 * answered, so the sheet always answers the whole lane at once. High-risk approvals need biometrics or the
 * device credential before they are sent as approved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(approvals: List<ApprovalRequest>, onAnswer: (String, Map<String, Protocol.Answer>) -> Unit, onDismiss: () -> Unit) {
    val lane = approvals.firstOrNull()?.lane ?: return
    val batch = approvals.filter { it.lane == lane }
    val p = LocalPalette.current
    val context = LocalContext.current
    val choices = remember(batch.map { it.id }) { mutableStateMapOf<String, Boolean>().apply { batch.forEach { put(it.id, batch.size == 1) } } }
    val remember30 = remember(batch.map { it.id }) { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun submit(approveAll: Boolean?) {
        val answers = batch.associate { a ->
            val ok = approveAll ?: (choices[a.id] == true)
            a.id to Protocol.Answer(ok, if (ok && remember30[a.id] == true && a.canRemember) 30 else 0)
        }
        val needsAuth = batch.any { it.high && answers[it.id]?.approved == true }
        if (!needsAuth) {
            onAnswer(lane, answers)
            return
        }
        val activity = context as? FragmentActivity ?: return
        authenticate(activity, onOk = { onAnswer(lane, answers) }, onError = { error = it })
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = p.bgHi) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
            Text("NEEDS YOUR OK", style = Label, color = p.accent)
            Text(
                if (batch.size == 1) "Rivet wants to:" else "Rivet wants to do ${batch.size} things:",
                style = MaterialTheme.typography.titleLarge, color = p.ink,
            )
            if (lane != "default") Text("From background task $lane", style = MaterialTheme.typography.bodySmall, color = p.ink2)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                batch.forEach { a ->
                    ApprovalCard(
                        a,
                        approved = choices[a.id] == true,
                        onToggle = if (batch.size > 1) ({ choices[a.id] = it }) else null,
                        remember30 = remember30[a.id] == true,
                        onRemember = { remember30[a.id] = it },
                    )
                }
            }
            error?.let { Text(it, color = p.rec, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.size(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { submit(false) }, modifier = Modifier.weight(1f)) { Text(if (batch.size > 1) "Deny all" else "Deny") }
                Button(
                    onClick = { submit(if (batch.size > 1) null else true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                ) {
                    Text(
                        when {
                            batch.size > 1 -> "Send answers"
                            batch.single().high -> "Approve with unlock"
                            else -> "Approve"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    a: ApprovalRequest,
    approved: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    remember30: Boolean,
    onRemember: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val tierColor = when {
        a.high -> p.rec
        a.tier == "send" -> p.amb
        else -> p.cy
    }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.line, shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                a.tier.uppercase(),
                style = Label.copy(fontSize = 10.sp), color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(tierColor).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(a.tool, style = Label, color = p.ink2, modifier = Modifier.weight(1f))
            if (onToggle != null) Switch(checked = approved, onCheckedChange = onToggle)
        }
        Spacer(Modifier.size(6.dp))
        Text(a.summary, style = MaterialTheme.typography.titleMedium, color = p.ink)
        a.operations.forEach { op ->
            Text(
                "${op.method ?: "?"}  ${op.name}" + if (op.summary.isNotBlank()) " — ${op.summary}" else "",
                style = MaterialTheme.typography.bodySmall, color = p.ink2,
            )
        }
        val fields = remember(a.preview) { ApprovalPreview.fields(a.preview) }
        if (fields != null) {
            // Everything the action will do, wrapped and readable: never cut off sideways.
            Spacer(Modifier.size(8.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(p.bg)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                fields.forEach { (label, value) ->
                    Text(label.uppercase(), style = Label.copy(fontSize = 10.sp), color = p.ink2)
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = p.ink)
                }
            }
        } else if (a.preview.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(
                a.preview,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = p.ink,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(p.bg)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
            )
        }
        if (a.untrustedTurn) {
            Spacer(Modifier.size(8.dp))
            Text(
                "Rivet read outside content (messages, web, a screen) this turn. Check this is what you asked for.",
                style = MaterialTheme.typography.bodySmall, color = p.amb,
            )
        }
        if (a.canRemember) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Don't ask again for 30 minutes", style = MaterialTheme.typography.bodySmall, color = p.ink2, modifier = Modifier.weight(1f))
                Switch(checked = remember30, onCheckedChange = onRemember)
            }
        }
    }
}

private fun authenticate(activity: FragmentActivity, onOk: () -> Unit, onError: (String) -> Unit) {
    val authenticators = if (Build.VERSION.SDK_INT >= 30) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
    if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onError("Set a screen lock on this phone to approve high-risk actions.")
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Approve Rivet's action")
            .setSubtitle("High-risk: purchase, delete or unclassified")
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}
