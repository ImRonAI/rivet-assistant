package com.hiron.emrys.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.hiron.emrys.core.AlertBlock
import com.hiron.emrys.core.ApprovalRequest
import com.hiron.emrys.core.Decision
import com.hiron.emrys.core.NoAllow
import com.hiron.emrys.core.Protocol
import com.hiron.emrys.core.RuleOffer
import com.hiron.emrys.core.Tier

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** The one approval's headline, and the batch's title: the sheet's serif moment. */
private val Headline = Type.step.copy(fontSize = 30.sp, lineHeight = 35.sp)

/** Each approval's headline on a card in a batch. */
private val CardHeadline = Type.step.copy(fontSize = 22.sp, lineHeight = 27.sp)

/** The message Emrys will send, set as a pull quote when it is the only approval. */
private val PullQuote = Type.quote.copy(fontSize = 24.sp, lineHeight = 31.sp)

/** Field labels whose value names who the action reaches; their values read as name chips. */
private val RECIPIENTS = setOf("To", "Copy to", "Number")

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
    val gutter = if (with(density) { window.width.toDp() } >= 400.dp) 24.dp else 20.dp
    val single = batch.singleOrNull()
    // Deleting, spending and the unknown carry a coral hairline and a coral accent on the headline, never a coral fill.
    val grave = single?.high == true
    val edge = if (grave) p.rec.copy(alpha = 0.7f) else p.lineHi

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
        shape = SheetShape,
        dragHandle = {
            // The handle's row also draws the sheet's glass edge along the top.
            Box(Modifier.fillMaxWidth().height(22.dp).drawBehind { glassEdge(edge) }, contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.padding(bottom = 6.dp).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.lineHi))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = gutter).padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Needs your OK", Modifier.weight(1f), tone = Tone.Amber, live = true)
                Text(
                    if (batch.size == 1) "1 action" else "${batch.size} actions",
                    style = Type.labelLg.copy(fontFeatureSettings = "tnum"), color = p.ink2, maxLines = 1,
                )
            }
            if (single != null) {
                Spacer(Modifier.height(22.dp))
                // The kicker over the headline: what kind of action it is, in coral when it can't be taken back.
                val accent = if (grave) p.rec else p.ink2
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(single.tool), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Emrys wants to:", style = Type.title, color = accent)
                }
                Spacer(Modifier.height(10.dp))
            } else {
                Spacer(Modifier.height(16.dp))
                Text("Emrys wants to do ${batch.size} things:", style = Headline, color = p.ink)
                Spacer(Modifier.height(20.dp))
            }
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
                        framed = single == null,
                        approved = choices[a.id] == true,
                        onToggle = if (batch.size > 1) ({ choices[a.id] = it }) else null,
                        standing = standing[a.id] == true,
                        onStanding = { standing[a.id] = it },
                    )
                }
            }
            error?.let { InfoStrip(it, Modifier.padding(top = 14.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
            Spacer(Modifier.height(24.dp))
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
        NoAllow.Purchase -> "Emrys always asks before spending money, so this works only with Deny."
        NoAllow.Destructive -> "Emrys always asks before deleting anything, so this works only with Deny."
        NoAllow.Unknown -> "Emrys always asks before things it doesn't recognise, so this works only with Deny."
        NoAllow.Untrusted ->
            "Emrys read a message, an email or a web page while working on this, so it can't be always allowed right now. This works only with Deny."
        NoAllow.Kind, null -> "Emrys always asks before this kind of action, so this works only with Deny."
    }
    return "If I say no, don't ask again about $label" to helper
}

/**
 * One approval. On its own it is laid straight on the sheet like a page; in a batch ([framed]) it sits on a glass
 * card with its own switch. Either way: the serif headline and why, the risk, what will happen, the technical
 * details folded away, and "don't ask again".
 */
@Composable
private fun ApprovalCard(
    a: ApprovalRequest,
    modifier: Modifier,
    names: Map<String, String>,
    framed: Boolean,
    approved: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    standing: Boolean,
    onStanding: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val tone = Plain.toneFor(a.tier)
    val headline = Plain.people(a.headline, names)
    var details by remember(a.id) { mutableStateOf(false) }
    val body: @Composable ColumnScope.() -> Unit = {
        if (framed) {
            Row(verticalAlignment = Alignment.Top) {
                IconWell(iconFor(a.tool), Modifier.padding(top = 2.dp), tone = if (a.high) Tone.Red else Tone.Neutral, size = 36.dp, iconSize = 18.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(headline, style = CardHeadline, color = p.ink)
                    a.because?.let { Text(it, style = Type.cardBody, color = p.ink2) }
                }
                if (onToggle != null) {
                    Spacer(Modifier.width(10.dp))
                    EmrysToggle(approved, onToggle, Modifier.semantics { contentDescription = "Approve: $headline" })
                }
            }
        } else {
            Text(headline, style = Headline, color = p.ink)
            a.because?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Type.bodyLg, color = p.ink2)
            }
        }
        Spacer(Modifier.height(if (framed) 16.dp else 20.dp))
        // The meter sits on the first line of the words, however many lines they take.
        val firstLine = with(LocalDensity.current) { Type.body.lineHeight.toDp() }
        Row(verticalAlignment = Alignment.Top) {
            RiskMeter(a.tier, tone, Modifier.padding(top = firstLine / 2 - 1.5.dp))
            Spacer(Modifier.width(14.dp))
            Text(Plain.risk(a.tier, a.tool), style = Type.body, color = p.ink2, modifier = Modifier.weight(1f))
        }
        Blocks(a.blocks, names, framed)
        if (a.untrustedTurn) {
            InfoStrip(
                "This came up while Emrys was reading a message, an email or a web page. Check it carefully.",
                Modifier.padding(top = 16.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Amber,
            )
        }
        Spacer(Modifier.height(16.dp))
        Rule(color = p.line)
        Disclosure("Technical details", details) { details = !details }
        AnimatedVisibility(details, enter = expandVertically(tween(Motion.BASE)) + fadeIn(), exit = shrinkVertically(tween(Motion.BASE)) + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.tool, style = Type.mono, color = p.ink2)
                a.operations.forEach { op ->
                    Text(
                        "${op.method ?: "?"} ${op.name}" + if (op.summary.isNotBlank()) " — ${op.summary}" else "",
                        style = Type.mono, color = p.ink2,
                    )
                }
                if (a.preview.isNotBlank()) {
                    val well = RoundedCornerShape(14.dp)
                    val input = remember(a.preview) {
                        ApprovalPreview.fields(a.preview)?.joinToString("\n") { (label, value) -> "$label: $value" } ?: a.preview
                    }
                    Text(
                        input,
                        style = Type.mono, color = p.ink,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 220.dp).clip(well).background(p.field)
                            .border(1.dp, p.line, well).verticalScroll(rememberScrollState()).padding(14.dp),
                    )
                }
            }
        }
        ruleCopy(a.offer, names)?.let { (label, helper) ->
            Rule(color = p.line)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(standing, role = Role.Switch, onValueChange = onStanding).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = Type.title, color = p.ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                EmrysToggle(standing, null)
            }
            Text(helper, style = Type.cardBody, color = p.ink2, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
    if (framed) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier.fillMaxWidth().clip(shape).background(p.card)
                .border(1.dp, if (a.high) p.rec.copy(alpha = 0.45f) else p.line, shape)
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
            content = body,
        )
    } else {
        Column(modifier.fillMaxWidth(), content = body)
    }
}

/** A quiet row that folds a section open and shut: a Geist label and a chevron that turns. */
@Composable
private fun Disclosure(text: String, open: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(Motion.BASE, easing = Motion.Emphasized), label = "disclosure")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.labelLg, color = p.ink2, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp).rotate(turn))
    }
}

/**
 * What the action will do: short fields as a ruled table (who it reaches as name chips), quoted text as a serif
 * pull quote on a thin halo rule, Emrys's notes as formatted text.
 */
@Composable
private fun Blocks(blocks: List<AlertBlock>, names: Map<String, String>, framed: Boolean) {
    val p = LocalPalette.current
    val rows = blocks.filterIsInstance<AlertBlock.Field>()
    if (rows.isNotEmpty()) {
        Column(Modifier.padding(top = 16.dp).fillMaxWidth()) {
            rows.forEach { field ->
                Rule(color = p.line)
                val value = Plain.people(field.value, names, withNumber = true)
                val stacked = value.length > 28 || '\n' in value
                when {
                    field.label in RECIPIENTS && '\n' !in value -> FlowRow(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(field.label, style = Type.labelLg, color = p.ink2, maxLines = 1, modifier = Modifier.padding(end = 16.dp))
                        NameChip(value)
                    }
                    stacked -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(field.label, style = Type.labelLg, color = p.ink2)
                        Text(value, style = Type.bodyLg, color = p.ink)
                    }
                    else -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(field.label, style = Type.labelLg, color = p.ink2, maxLines = 1)
                        Spacer(Modifier.width(16.dp))
                        Text(value, style = Type.bodyLg, color = p.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
            }
            Rule(color = p.line)
        }
    }
    blocks.forEach { block ->
        when (block) {
            is AlertBlock.Quote -> Column(Modifier.padding(top = 18.dp).fillMaxWidth()) {
                Eyebrow(block.label, tone = Tone.Neutral, dot = false)
                Spacer(Modifier.height(10.dp))
                Text(
                    "“${block.text}”",
                    style = if (framed) Type.quote else PullQuote, color = p.ink,
                    modifier = Modifier.fillMaxWidth().haloRule(p).padding(start = 18.dp, top = 2.dp, bottom = 2.dp)
                        .heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
            }
            is AlertBlock.Note -> Text(Rich.format(block.markdown, p), style = Type.body, color = p.ink2, modifier = Modifier.padding(top = 14.dp))
            is AlertBlock.Field -> Unit
        }
    }
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

/** The thin halo rule down the left of a pull quote. */
private fun Modifier.haloRule(p: Palette) = drawBehind {
    val w = 2.dp.toPx()
    drawRoundRect(
        Brush.verticalGradient(listOf(p.accentHi, p.accent, p.accentDeep)),
        size = Size(w, size.height), cornerRadius = CornerRadius(w / 2),
    )
}

/**
 * Who the action reaches, as a chip: an initial (or a person mark for a bare number) and the value, the contact's
 * name in porcelain and the number after it in grey. Read as the value alone.
 */
@Composable
private fun NameChip(value: String) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    val name = value.substringBefore(" · ")
    val initial = name.firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar()
    Row(
        Modifier.clip(pill).background(p.field).border(1.dp, p.line, pill).padding(start = 5.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(p.high).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            if (initial != null) {
                Text("$initial", style = Type.labelLg, color = p.ink)
            } else {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = p.ink2, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            buildAnnotatedString {
                append(name)
                withStyle(SpanStyle(color = p.ink2)) { append(value.substring(name.length)) }
            },
            style = Type.chip, color = p.ink,
        )
    }
}

/** Fades the last stretch of a scrolling list into [color] while more of it is below. */
private fun Modifier.fadeBottom(color: Color, show: Boolean) = drawWithContent {
    drawContent()
    if (!show) return@drawWithContent
    val h = 36.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, color), startY = size.height - h, endY = size.height), Offset(0f, size.height - h), Size(size.width, h))
}

/**
 * Five slim segments, one per step of risk: read, send, write, delete, purchase. The steps below the action's own
 * are porcelain, its own step alone is in the risk's colour, and the rest stay dark.
 */
@Composable
private fun RiskMeter(tier: Tier, tone: Tone, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val c = tone.color()
    val level = when (tier) {
        Tier.Read -> 1
        Tier.Send -> 2
        Tier.Write -> 3
        Tier.Destructive -> 4
        Tier.Purchase, Tier.Unknown -> 5
    }
    val bar = RoundedCornerShape(50)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val fill = when {
                i < level - 1 -> p.ink.copy(alpha = 0.34f)
                i == level - 1 -> c
                else -> p.edge
            }
            Box(
                Modifier.size(width = 16.dp, height = 3.dp).then(if (i == level - 1) Modifier.glow(c.copy(alpha = 0.6f), 5.dp, bar) else Modifier)
                    .clip(bar).background(fill),
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
            .setTitle("Approve Emrys's action")
            .setSubtitle(
                if (rule) "You're telling Emrys to always do this without asking." else "This spends money, deletes something, or is something Emrys hasn't done before.",
            )
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}
