package com.hiron.rivet.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
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
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val listMax = with(density) { (window.height * 0.58f).toDp() }

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
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.dot)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("NEEDS YOUR OK", Modifier.weight(1f), tone = Tone.Amber, live = true)
                Text(if (batch.size == 1) "1 ACTION" else "${batch.size} ACTIONS", style = Type.eyebrow, color = p.ink2, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))
            Text(if (batch.size == 1) "Rivet wants to:" else "Rivet wants to do ${batch.size} things:", style = Type.headline, color = p.ink)
            Spacer(Modifier.height(16.dp))
            val list = rememberScrollState()
            Column(
                Modifier.heightIn(max = listMax).fadeBottom(p.raised, list.canScrollForward).verticalScroll(list),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                batch.forEachIndexed { i, a ->
                    ApprovalCard(
                        a,
                        Modifier.enter(i),
                        approved = choices[a.id] == true,
                        onToggle = if (batch.size > 1) ({ choices[a.id] = it }) else null,
                        remember30 = remember30[a.id] == true,
                        onRemember = { remember30[a.id] = it },
                    )
                }
            }
            error?.let { InfoStrip(it, Modifier.padding(top = 12.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
            Spacer(Modifier.height(20.dp))
            val unlock = batch.size == 1 && batch.single().high
            val approveText = when {
                batch.size > 1 -> "Send answers"
                unlock -> "Approve with unlock"
                else -> "Approve"
            }
            val denyText = if (batch.size > 1) "Deny all" else "Deny"
            val approve = @Composable { m: Modifier ->
                PrimaryKey(
                    approveText, { submit(if (batch.size > 1) null else true) }, m,
                    trailingArrow = false, leading = if (unlock) Icons.Outlined.Fingerprint else null,
                )
            }
            // Side by side and the same size, unless large text or the long unlock label would cut a key short; then the approve key goes on top.
            if (density.fontScale > 1.15f || unlock) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    approve(Modifier)
                    SecondaryKey(denyText, { submit(false) }, height = 56.dp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryKey(denyText, { submit(false) }, Modifier.weight(1f), height = 56.dp)
                    approve(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    a: ApprovalRequest,
    modifier: Modifier,
    approved: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    remember30: Boolean,
    onRemember: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val tone = Plain.toneFor(a.tier)
    var details by remember(a.id) { mutableStateOf(false) }
    val fields = remember(a.preview) { ApprovalPreview.fields(a.preview) }
    RivetCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(iconFor(a.tool), tone = tone, size = 44.dp, iconSize = 22.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Plain.numbers(a.summary), style = Type.title, color = p.ink)
                Text(Plain.action(a.tool), style = Type.cardBody, color = p.ink2)
            }
            if (onToggle != null) {
                Spacer(Modifier.width(8.dp))
                RivetToggle(approved, onToggle, Modifier.semantics { contentDescription = "Approve: ${a.summary}" })
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RiskMeter(a.tier, tone)
            Spacer(Modifier.width(10.dp))
            Text(Plain.risk(a.tier, a.tool), style = Type.cardBody, color = p.ink2, modifier = Modifier.weight(1f))
        }
        if (fields != null) {
            Preview(fields)
        } else if (a.preview.isNotBlank()) {
            val well = RoundedCornerShape(12.dp)
            Text(
                a.preview,
                style = Type.mono, color = p.ink,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(max = 220.dp).clip(well).background(p.field)
                    .border(1.dp, p.edge, well).verticalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        if (a.untrustedTurn) {
            InfoStrip(
                "This came up while Rivet was reading a message, an email or a web page. Check it carefully.",
                Modifier.padding(top = 12.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Amber,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextKey("Details", { details = !details }, Modifier.offset { IntOffset(-8.dp.roundToPx(), 0) }, leading = if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore)
        AnimatedVisibility(details, enter = expandVertically(tween(Motion.BASE)) + fadeIn(), exit = shrinkVertically(tween(Motion.BASE)) + fadeOut()) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(a.tool, style = Type.mono, color = p.ink2)
                a.operations.forEach { op ->
                    Text(
                        "${op.method ?: "?"} ${op.name}" + if (op.summary.isNotBlank()) " — ${op.summary}" else "",
                        style = Type.mono, color = p.ink2,
                    )
                }
            }
        }
        if (a.canRemember) {
            Rule(color = p.edgeSoft)
            Spacer(Modifier.height(4.dp))
            NestedRow("Don't ask again for 30 minutes", remember30, onRemember)
        }
    }
}

/** What the action will do: short fields as rows in one well, the message itself as a quote card. */
@Composable
private fun Preview(fields: List<Pair<String, String>>) {
    val p = LocalPalette.current
    val well = RoundedCornerShape(12.dp)
    val (quotes, rows) = fields.partition { (label, _) -> label == "Message" || label == "Subject" }
    if (rows.isNotEmpty()) {
        Column(
            Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(max = 220.dp).clip(well).background(p.field).border(1.dp, p.edge, well)
                .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) Rule(color = p.line)
                val stacked = value.length > 28 || '\n' in value
                if (stacked) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label.uppercase(), style = Type.labelSm, color = p.ink2)
                        Text(value, style = Type.bodyLg, color = p.ink)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label.uppercase(), style = Type.labelSm, color = p.ink2, maxLines = 1)
                        Spacer(Modifier.width(16.dp))
                        Text(value, style = Type.bodyLg, color = p.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    quotes.forEach { (label, value) ->
        Column(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(well).background(p.field).border(1.dp, p.edge, well).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Eyebrow(label.uppercase(), tone = Tone.Neutral, dot = false)
            Text(if (label == "Message") "\u201C$value\u201D" else value, style = Type.quote, color = p.ink, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
        }
    }
}

/** Fades the last stretch of a scrolling list into [color] while more of it is below. */
private fun Modifier.fadeBottom(color: Color, show: Boolean) = drawWithContent {
    drawContent()
    if (!show) return@drawWithContent
    val h = 36.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, color), startY = size.height - h, endY = size.height), Offset(0f, size.height - h), Size(size.width, h))
}

private fun iconFor(tool: String): ImageVector = when {
    tool.startsWith("sms_") || tool == "notification_reply" -> Icons.AutoMirrored.Outlined.Chat
    tool.startsWith("email_") -> Icons.Outlined.MailOutline
    tool == "phone_call" || tool == "calls_recent" -> Icons.Outlined.Call
    tool.startsWith("calendar_") -> Icons.Outlined.Event
    tool.startsWith("reminder") -> Icons.Outlined.Alarm
    tool.startsWith("contacts_") -> Icons.Outlined.Person
    tool.startsWith("screen_") -> Icons.Outlined.TouchApp
    tool.startsWith("app") -> Icons.Outlined.Apps
    tool.startsWith("notifications_") -> Icons.Outlined.Notifications
    tool.startsWith("telnyx_") || tool.startsWith("call_") -> Icons.Outlined.Dialpad
    tool == "device_status" -> Icons.Outlined.PhoneAndroid
    else -> Icons.Outlined.Bolt
}

/** Five segments lit up to the action's risk: read, send, write, delete, purchase. */
@Composable
private fun RiskMeter(tier: String, tone: Tone) {
    val p = LocalPalette.current
    val c = tone.color()
    val level = when (tier) {
        "read" -> 1
        "send" -> 2
        "write" -> 3
        "destructive" -> 4
        else -> 5
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            Box(
                Modifier.size(width = 14.dp, height = 4.dp).then(if (i == level - 1) Modifier.glow(c.copy(alpha = 0.5f), 4.dp, RoundedCornerShape(2.dp)) else Modifier)
                    .clip(RoundedCornerShape(2.dp)).background(if (i < level) c else p.track),
            )
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
            .setSubtitle("This spends money, deletes something, or is something Rivet hasn't done before.")
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}
