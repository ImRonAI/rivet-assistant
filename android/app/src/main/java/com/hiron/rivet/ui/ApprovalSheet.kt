package com.hiron.rivet.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.raised,
        contentColor = p.ink,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.hudLine)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pip(p.amb, live = true)
                Spacer(Modifier.size(4.dp))
                Text("NEEDS YOUR OK", style = Type.labelSm, color = p.amb, modifier = Modifier.weight(1f))
                Text(
                    (if (lane == "default") "" else "TASK $lane · ") + if (batch.size == 1) "1 ACTION" else "${batch.size} ACTIONS",
                    style = Type.labelSm, color = p.ink2, maxLines = 1,
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(if (batch.size == 1) "Rivet wants to:" else "Rivet wants to do ${batch.size} things:", style = Type.headline, color = p.ink)
            Spacer(Modifier.size(12.dp))
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
            error?.let { Text(it, color = p.rec, style = Type.body, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.size(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Keycap(if (batch.size > 1) "Deny all" else "Deny", { submit(false) }, modifier = Modifier.weight(1f))
                Keycap(
                    when {
                        batch.size > 1 -> "Send answers"
                        batch.single().high -> "Approve with unlock"
                        else -> "Approve"
                    },
                    { submit(if (batch.size > 1) null else true) },
                    modifier = Modifier.weight(1f),
                    tone = KeyTone.Accent,
                )
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
    Column(
        Modifier.fillMaxWidth().clip(shape).background(p.hud).border(1.dp, p.hudLine, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(a.tier, tierColor, hot = a.high)
            Spacer(Modifier.size(8.dp))
            Text(a.tool, style = Type.labelMd, color = p.ink2, modifier = Modifier.weight(1f), maxLines = 1)
            if (onToggle != null) RivetSwitch(approved, onToggle)
        }
        Text(a.summary, style = Type.title, color = p.ink)
        a.operations.forEach { op ->
            Text(
                "${op.method ?: "?"}  ${op.name}" + if (op.summary.isNotBlank()) " — ${op.summary}" else "",
                style = Type.mono, color = p.ink2,
            )
        }
        val fields = remember(a.preview) { ApprovalPreview.fields(a.preview) }
        val well = RoundedCornerShape(8.dp)
        if (fields != null) {
            // Everything the action will do, wrapped and readable: never cut off sideways.
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(well).background(p.tile).border(1.dp, p.keyLine, well)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                fields.forEachIndexed { i, (label, value) ->
                    if (i > 0) Spacer(Modifier.size(4.dp))
                    Text(label.uppercase(), style = Type.labelSm, color = p.ink2)
                    Text(value, style = Type.bodyLg, color = p.ink)
                }
            }
        } else if (a.preview.isNotBlank()) {
            Text(
                a.preview,
                style = Type.mono, color = p.ink,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(well).background(p.tile).border(1.dp, p.keyLine, well)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
            )
        }
        if (a.untrustedTurn) {
            Row(
                Modifier.fillMaxWidth().clip(well).background(p.amb.copy(alpha = 0.10f)).padding(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Tag("Check", p.amb)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Rivet read outside content (messages, web, a screen) this turn. Check this is what you asked for.",
                    style = Type.body, color = p.ink,
                )
            }
        }
        if (a.canRemember) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Don't ask again for 30 minutes", style = Type.body, color = p.ink2, modifier = Modifier.weight(1f))
                RivetSwitch(remember30, onRemember)
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
