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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
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
import com.hiron.rivet.core.AlertBlock
import com.hiron.rivet.core.ApprovalRequest
import com.hiron.rivet.core.Decision
import com.hiron.rivet.core.NoAllow
import com.hiron.rivet.core.Protocol
import com.hiron.rivet.core.RuleOffer
import com.hiron.rivet.core.Tier

/**
 * Review sheet for one lane's pending approvals. The brain denies any approval in the lane that is not
 * answered, so the sheet always answers the whole lane at once. High-risk approvals, and any "always allow",
 * need biometrics or the device credential before they are sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(
    approvals: List<ApprovalRequest>,
    onAnswer: (String, Map<String, Protocol.Answer>) -> Unit,
    onDismiss: () -> Unit,
    names: Map<String, String> = emptyMap(),
) {
    val lane = approvals.firstOrNull()?.lane ?: return
    val batch = approvals.filter { it.lane == lane }
    val p = LocalPalette.current
    val context = LocalContext.current
    val choices = remember(batch.map { it.id }) { mutableStateMapOf<String, Boolean>().apply { batch.forEach { put(it.id, batch.size == 1) } } }
    val standing = remember(batch.map { it.id }) { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val listMax = with(density) { (window.height * 0.58f).toDp() }

    fun submit(approveAll: Boolean?) {
        val answers = batch.associate { a ->
            val ok = approveAll ?: (choices[a.id] == true)
            a.id to answerFor(a, ok, standing[a.id] == true)
        }
        val newAllow = answers.values.any { it.standing == Decision.Allow }
        val needsAuth = newAllow || batch.any { it.high && answers[it.id]?.approved == true }
        if (!needsAuth) {
            onAnswer(lane, answers)
            return
        }
        val activity = context as? FragmentActivity ?: return
        authenticate(activity, rule = newAllow, onOk = { onAnswer(lane, answers) }, onError = { error = it })
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
                        names = names,
                        approved = choices[a.id] == true,
                        onToggle = if (batch.size > 1) ({ choices[a.id] = it }) else null,
                        standing = standing[a.id] == true,
                        onStanding = { standing[a.id] = it },
                    )
                }
            }
            error?.let { InfoStrip(it, Modifier.padding(top = 12.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
            Spacer(Modifier.height(20.dp))
            val single = batch.singleOrNull()
            val ticked = single != null && standing[single.id] == true && single.offer != null
            val alwaysAllow = ticked && single.offer.allow
            val unlock = single != null && (single.high || alwaysAllow)
            val approveText = when {
                single == null -> "Send answers"
                alwaysAllow -> "Always allow"
                single.high -> "Approve with unlock"
                else -> "Approve"
            }
            val denyText = when {
                single == null -> "Deny all"
                ticked -> "Always deny"
                else -> "Deny"
            }
            val approve = @Composable { m: Modifier ->
                PrimaryKey(
                    approveText, { submit(if (single == null) null else true) }, m,
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

/** The answer for one card: a ticked "don't ask again" goes with the choice the offer allows for it, else nothing. */
internal fun answerFor(request: ApprovalRequest, approved: Boolean, ticked: Boolean): Protocol.Answer {
    val offer = request.offer
    val standing = when {
        !ticked || offer == null -> null
        approved && offer.allow -> Decision.Allow
        !approved && offer.deny -> Decision.Deny
        else -> null
    }
    return Protocol.Answer(approved, standing)
}

/** The rule row's label and the line under it, or null when the approval offers no rule. */
internal fun ruleCopy(offer: RuleOffer?, names: Map<String, String>): Pair<String, String>? {
    if (offer == null || !offer.deny) return null
    val label = Plain.people(offer.label, names)
    if (offer.allow) {
        return "Don't ask again about $label" to
            "Approve to always allow it. Deny to always decline it. You can change this in Settings › Approval rules."
    }
    val helper = when (offer.noAllow) {
        NoAllow.Purchase -> "Rivet always asks before spending money, so this works only with Deny."
        NoAllow.Destructive -> "Rivet always asks before deleting anything, so this works only with Deny."
        NoAllow.Unknown -> "Rivet always asks before things it doesn't recognise, so this works only with Deny."
        NoAllow.Untrusted ->
            "Rivet read a message, an email or a web page while working on this, so it can't be always allowed right now. This works only with Deny."
        NoAllow.Kind, null -> "Rivet always asks before this kind of action, so this works only with Deny."
    }
    return "If I say no, don't ask again about $label" to helper
}

@Composable
private fun ApprovalCard(
    a: ApprovalRequest,
    modifier: Modifier,
    names: Map<String, String>,
    approved: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    standing: Boolean,
    onStanding: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val tone = Plain.toneFor(a.tier)
    val headline = Plain.people(a.headline, names)
    var details by remember(a.id) { mutableStateOf(false) }
    RivetCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(iconFor(a.tool), tone = tone, size = 44.dp, iconSize = 22.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(headline, style = Type.title, color = p.ink)
                a.because?.let { Text(it, style = Type.cardBody, color = p.ink2) }
            }
            if (onToggle != null) {
                Spacer(Modifier.width(8.dp))
                RivetToggle(approved, onToggle, Modifier.semantics { contentDescription = "Approve: $headline" })
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RiskMeter(a.tier, tone)
            Spacer(Modifier.width(10.dp))
            Text(Plain.risk(a.tier, a.tool), style = Type.cardBody, color = p.ink2, modifier = Modifier.weight(1f))
        }
        Blocks(a.blocks, names)
        if (a.untrustedTurn) {
            InfoStrip(
                "This came up while Rivet was reading a message, an email or a web page. Check it carefully.",
                Modifier.padding(top = 12.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Amber,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextKey(
            "Technical details", { details = !details }, Modifier.offset { IntOffset(-8.dp.roundToPx(), 0) },
            leading = if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
        )
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
                if (a.preview.isNotBlank()) {
                    val well = RoundedCornerShape(12.dp)
                    val input = remember(a.preview) {
                        ApprovalPreview.fields(a.preview)?.joinToString("\n") { (label, value) -> "$label: $value" } ?: a.preview
                    }
                    Text(
                        input,
                        style = Type.mono, color = p.ink,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 220.dp).clip(well).background(p.field)
                            .border(1.dp, p.edge, well).verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        }
        ruleCopy(a.offer, names)?.let { (label, helper) ->
            Rule(color = p.edgeSoft)
            Spacer(Modifier.height(4.dp))
            NestedRow(label, standing, onStanding)
            Text(helper, style = Type.cardBody, color = p.ink2, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
    }
}

/** What the action will do: short fields as rows in one well, quoted text as cards, Rivet's notes as formatted text. */
@Composable
private fun Blocks(blocks: List<AlertBlock>, names: Map<String, String>) {
    val p = LocalPalette.current
    val well = RoundedCornerShape(12.dp)
    val rows = blocks.filterIsInstance<AlertBlock.Field>()
    if (rows.isNotEmpty()) {
        Column(
            Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(max = 220.dp).clip(well).background(p.field).border(1.dp, p.edge, well)
                .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            rows.forEachIndexed { i, field ->
                if (i > 0) Rule(color = p.line)
                val value = Plain.people(field.value, names, withNumber = true)
                val stacked = value.length > 28 || '\n' in value
                if (stacked) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(field.label.uppercase(), style = Type.labelSm, color = p.ink2)
                        Text(value, style = Type.bodyLg, color = p.ink)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(field.label.uppercase(), style = Type.labelSm, color = p.ink2, maxLines = 1)
                        Spacer(Modifier.width(16.dp))
                        Text(value, style = Type.bodyLg, color = p.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    blocks.forEach { block ->
        when (block) {
            is AlertBlock.Quote -> Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(well).background(p.field).border(1.dp, p.edge, well).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Eyebrow(block.label.uppercase(), tone = Tone.Neutral, dot = false)
                Text("\u201C${block.text}\u201D", style = Type.quote, color = p.ink, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
            }
            is AlertBlock.Note -> Text(Rich.format(block.markdown, p), style = Type.cardBody, color = p.ink2, modifier = Modifier.padding(top = 12.dp))
            is AlertBlock.Field -> Unit
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

/** Five segments lit up to the action's risk: read, send, write, delete, purchase. */
@Composable
private fun RiskMeter(tier: Tier, tone: Tone) {
    val p = LocalPalette.current
    val c = tone.color()
    val level = when (tier) {
        Tier.Read -> 1
        Tier.Send -> 2
        Tier.Write -> 3
        Tier.Destructive -> 4
        Tier.Purchase, Tier.Unknown -> 5
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

private fun authenticate(activity: FragmentActivity, rule: Boolean, onOk: () -> Unit, onError: (String) -> Unit) {
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
            .setSubtitle(
                if (rule) "You're telling Rivet to always do this without asking." else "This spends money, deletes something, or is something Rivet hasn't done before.",
            )
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}
