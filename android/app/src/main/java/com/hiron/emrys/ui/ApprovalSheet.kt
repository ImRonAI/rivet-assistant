package com.hiron.emrys.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.hiron.emrys.core.ApprovalAnswer
import com.hiron.emrys.core.ApprovalRequest
import com.hiron.emrys.core.PermAction

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** The approval's headline: the sheet's serif moment. */
private val Headline = Type.step.copy(fontSize = 30.sp, lineHeight = 35.sp)

/**
 * The native approval for an agent's `request_approval`: what Emrys wants to do, an "Always allow" switch, and
 * Deny or Approve. Approving needs biometrics or the device credential.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(request: ApprovalRequest, onAnswer: (String, ApprovalAnswer) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var always by remember(request.id) { mutableStateOf(false) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val gutter = if (with(density) { LocalWindowInfo.current.containerSize.width.toDp() } >= 400.dp) 24.dp else 20.dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.raised,
        contentColor = p.ink,
        shape = SheetShape,
        dragHandle = {
            // The handle's row also draws the sheet's glass edge along the top.
            Box(Modifier.fillMaxWidth().height(22.dp).drawBehind { glassEdge(p.lineHi) }, contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.padding(bottom = 6.dp).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.lineHi))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = gutter).padding(bottom = 20.dp)) {
            Eyebrow("Needs your OK", Modifier.padding(top = 10.dp), tone = Tone.Amber, live = true)
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(request.action), contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Emrys wants to: ${request.action.label}", style = Type.title, color = p.ink2)
            }
            Spacer(Modifier.height(10.dp))
            Text(request.details.ifBlank { request.action.label }, style = Headline, color = p.ink)
            Spacer(Modifier.height(20.dp))
            Rule(color = p.line)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(always, role = Role.Switch) { always = it }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Always allow: ${request.action.label}", style = Type.title, color = p.ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                EmrysToggle(always, null)
            }
            Text(
                "Emrys won't ask again before this. You can change it in Settings › Permissions.",
                style = Type.cardBody, color = p.ink2, modifier = Modifier.padding(bottom = 4.dp),
            )
            error?.let { InfoStrip(it, Modifier.padding(top = 14.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryKey(
                    if (always) "Always allow" else "Approve",
                    {
                        val activity = context as? FragmentActivity ?: return@PrimaryKey
                        authenticate(activity, onOk = { onAnswer(request.id, ApprovalAnswer(true, always)) }, onError = { error = it })
                    },
                    trailingArrow = false, leading = Icons.Outlined.Fingerprint,
                )
                SecondaryKey("Deny", { onAnswer(request.id, ApprovalAnswer(false)) }, height = 56.dp)
            }
        }
    }
}

/** The icon for a kind of action, on the approval sheet and in Settings › Permissions. */
internal fun iconFor(action: PermAction): ImageVector = when (action) {
    PermAction.UseApps -> Icons.Outlined.TouchApp
    PermAction.SendMessages -> Icons.AutoMirrored.Outlined.Chat
    PermAction.ReadMessages -> Icons.Outlined.Visibility
    PermAction.Purchases -> Icons.Outlined.ShoppingCart
    PermAction.InstallApps -> Icons.Outlined.GetApp
    PermAction.CloudCoding -> Icons.Outlined.Code
    PermAction.Deploy -> Icons.Outlined.CloudUpload
    PermAction.CreateMusic -> Icons.Outlined.MusicNote
}

/** The sheet's glass edge: a hairline round its top corners, brightest along the top and fading down the sides. */
private fun DrawScope.glassEdge(color: Color) {
    val r = 28.dp.toPx()
    // Half the stroke falls outside the sheet and is clipped away, leaving a 1dp line inside it.
    drawOutline(
        SheetShape.createOutline(Size(size.width, r * 2), layoutDirection, this),
        Brush.verticalGradient(listOf(color, Color.Transparent), startY = 0f, endY = r * 1.4f),
        style = Stroke(2.dp.toPx()),
    )
}

private fun authenticate(activity: FragmentActivity, onOk: () -> Unit, onError: (String) -> Unit) {
    val authenticators = if (Build.VERSION.SDK_INT >= 30) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
    if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onError("Set a screen lock on this phone to approve Emrys's actions.")
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
            .setTitle("Approve Emrys's action")
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}
