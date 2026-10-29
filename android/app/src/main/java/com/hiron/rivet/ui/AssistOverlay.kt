package com.hiron.rivet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.rivet.R

/** What the assistant overlay is doing; it picks the status LED, the readout colour and the keys. */
enum class AssistPhase { Ready, Listening, Working, Replying, Done, Waiting, Failed, NeedsMic, Locked }

/** Everything the assistant overlay draws, as plain values, so it renders the same with or without a voice session. */
data class AssistUi(
    val phase: AssistPhase,
    /** What the user is saying (live while listening), or what they asked. */
    val heard: String = "",
    /** Rivet's reply so far. */
    val reply: String = "",
    /** The status in plain words, e.g. LISTENING or SEARCHING. */
    val status: String = "",
    /** Why Rivet didn't finish, or what the user can do next. */
    val note: String? = null,
    /** Actions waiting on the user's OK. */
    val waiting: Int = 0,
    /** Whether the keyboard field is open. */
    val typing: Boolean = false,
    /** Microphone level, 0..1, while listening. */
    val level: Float = 0f,
)

const val ASSIST_SCRIM_TAG = "assist-scrim"
const val ASSIST_PANEL_TAG = "assist-panel"
const val ASSIST_FIELD_TAG = "assist-field"

/** How many recent microphone levels the meter shows when the plate is wide enough for all of them. */
private const val METER_BARS = 24

/**
 * Rivet over another app: a scrim, and a sheet along the bottom with Rivet's head and status, the words as they
 * are heard, Rivet's reply as it streams, and the talk, type and open-app keys. Tapping the scrim closes it.
 */
@Composable
fun AssistOverlay(
    ui: AssistUi,
    modifier: Modifier = Modifier,
    onMic: () -> Unit = {},
    onTyping: (Boolean) -> Unit = {},
    onSend: (String) -> Unit = {},
    onOpen: () -> Unit = {},
    onAllowMic: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // A sibling under the plate rather than its parent, so the plate's text is not read as part of this button.
        Box(
            Modifier.matchParentSize()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.32f), 0.5f to Color.Black.copy(alpha = 0.54f), 1f to Color.Black.copy(alpha = 0.88f)))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close Rivet", onClick = onDismiss)
                .testTag(ASSIST_SCRIM_TAG),
        )
        AnimatedVisibility(
            shown,
            modifier = Modifier.statusBarsPadding(),
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) { it / 2 } + fadeIn(tween(Motion.BASE)),
        ) {
            Plate(ui, onMic, onTyping, onSend, onOpen, onAllowMic)
        }
    }
}

private data class Led(val color: Color, val live: Boolean)

private fun led(phase: AssistPhase, p: Palette) = when (phase) {
    AssistPhase.Ready -> Led(p.ok, false)
    AssistPhase.Listening -> Led(p.rec, true)
    AssistPhase.Working, AssistPhase.Replying -> Led(p.cy, true)
    AssistPhase.Done -> Led(p.ok, false)
    AssistPhase.Waiting -> Led(p.amb, true)
    AssistPhase.Failed -> Led(p.rec, false)
    AssistPhase.NeedsMic, AssistPhase.Locked -> Led(p.amb, false)
}

@Composable
private fun Plate(
    ui: AssistUi,
    onMic: () -> Unit,
    onTyping: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onOpen: () -> Unit,
    onAllowMic: () -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val led = led(ui.phase, p)
    val replies = ui.phase in setOf(AssistPhase.Working, AssistPhase.Replying, AssistPhase.Done, AssistPhase.Waiting) ||
        (ui.phase == AssistPhase.Failed && ui.note != null)
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth()
            .shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(p.raised)
            .border(1.dp, Brush.verticalGradient(listOf(p.lineHi, p.line, Color.Transparent)), shape)
            .drawWithContent {
                drawContent()
                val inset = 40.dp.toPx()
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), inset, size.width - inset),
                    Offset(inset, 0.75.dp.toPx()), Offset(size.width - inset, 0.75.dp.toPx()), 1.dp.toPx(),
                )
            }
            // Taps anywhere on the plate stay on it; only the scrim around it closes the overlay.
            .pointerInput(Unit) { detectTapGestures() }
            .navigationBarsPadding().imePadding()
            .testTag(ASSIST_PANEL_TAG),
    ) {
        Header(ui.status, led)
        Rule(color = p.line)
        val blocked = ui.phase == AssistPhase.NeedsMic || ui.phase == AssistPhase.Locked
        // With the keyboard open and nothing asked yet, the field is the whole body.
        val heard = !blocked && !(ui.typing && ui.heard.isBlank() && ui.phase == AssistPhase.Ready)
        if (blocked || heard || replies) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (blocked) Blocked(ui)
                if (heard) Heard(ui)
                if (replies) Reply(ui, onOpen)
            }
        }
        if (ui.typing && ui.phase != AssistPhase.Locked) TypeRow(onSend)
        Keys(ui, onMic, onTyping, onOpen, onAllowMic)
    }
}

/** Rivet's head in a lit well and the wordmark on the left; one status capsule on the right, as in the app's header. */
@Composable
private fun Header(status: String, led: Led) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(led.color.copy(alpha = if (led.live) 0.30f else 0.16f), Color.Transparent)), radius = size.minDimension / 2)
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(p.field).border(1.dp, p.edge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.rivet_head), contentDescription = "Rivet", modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildAnnotatedString {
                    append("RIV")
                    withStyle(SpanStyle(color = p.accent)) { append("E") }
                    append("T")
                },
                style = Type.brand.copy(fontSize = 20.sp, lineHeight = 20.sp), color = p.ink, maxLines = 1,
            )
            CapsText("Assistant", Type.labelSm, p.ink2, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Capsule(status, led.color, led.live)
    }
}

/** The user's words in large type, with a crimson caret and a level meter while the microphone is live. */
@Composable
private fun Heard(ui: AssistUi) {
    val p = LocalPalette.current
    val listening = ui.phase == AssistPhase.Listening
    val text = tail(ui.heard)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("YOU", tone = Tone.Neutral, dot = false)
        Text(
            buildAnnotatedString {
                if (text.isBlank()) withStyle(SpanStyle(color = p.ink2)) { append(if (listening) "Listening…" else "Ask Rivet anything") } else append(text)
                // A no-break space keeps the caret on the line with the last word.
                if (listening) withStyle(SpanStyle(color = p.rec)) { append(" ▍") }
            },
            style = Type.headline, color = p.ink,
        )
        if (listening) MicMeter(ui.level)
        if (ui.phase == AssistPhase.Ready && ui.note != null) InfoStrip(ui.note, Modifier.padding(top = 4.dp), icon = Icons.Outlined.Info, tone = Tone.Amber)
    }
}

/**
 * The last few microphone levels as rounded bars, newest on the right. It shows as many of [METER_BARS] as fit,
 * so a narrow phone or a large font never clips it.
 */
@Composable
private fun MicMeter(level: Float) {
    val p = LocalPalette.current
    val history = remember { mutableStateListOf<Float>().apply { repeat(METER_BARS) { add(0f) } } }
    LaunchedEffect(level) {
        history.add(level)
        while (history.size > METER_BARS) history.removeAt(0)
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("MIC", style = Type.labelSm, color = p.ink2)
        Spacer(Modifier.width(12.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            // Each bar is 6dp wide with an 8dp gap after it.
            val fits = ((maxWidth + 8.dp) / 14.dp).toInt().coerceIn(1, METER_BARS)
            LevelMeter(history.takeLast(fits), height = 28.dp)
        }
    }
}

/** Why the overlay cannot listen yet, in place of the user's words. */
@Composable
private fun Blocked(ui: AssistUi) {
    val p = LocalPalette.current
    val locked = ui.phase == AssistPhase.Locked
    Row(verticalAlignment = Alignment.Top) {
        IconWell(if (locked) Icons.Outlined.Lock else Icons.Outlined.MicOff, tone = Tone.Amber, size = 44.dp, iconSize = 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (locked) "Your phone is locked" else "Rivet can't hear you yet", style = Type.headline, color = p.ink)
            ui.note?.let { Text(it, style = Type.body, color = p.ink2) }
        }
    }
}

/** Rivet's side: a card like its chat bubbles, with an eyebrow that says what it is doing. */
@Composable
private fun Reply(ui: AssistUi, onReview: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    val alert = when (ui.phase) {
        AssistPhase.Waiting -> p.amb
        AssistPhase.Failed -> p.rec
        else -> null
    }
    val scroll = rememberScrollState()
    LaunchedEffect(ui.reply) { scroll.animateScrollTo(scroll.maxValue) }
    RivetCard(
        if (alert == null) Modifier else Modifier.glow(alert.copy(alpha = 0.10f), 24.dp, shape).border(1.dp, alert.copy(alpha = 0.5f), shape),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (ui.phase) {
                AssistPhase.Working -> {
                    Eyebrow("RIVET", tone = Tone.Cyan, live = true)
                    Spacer(Modifier.width(10.dp))
                    Thinking()
                }
                AssistPhase.Replying -> Eyebrow("RIVET", tone = Tone.Cyan, live = true)
                AssistPhase.Waiting -> {
                    Eyebrow("NEEDS YOUR OK", Modifier.weight(1f), tone = Tone.Amber, live = true)
                    Spacer(Modifier.width(12.dp))
                    Keycap("Review", onReview, tone = KeyTone.Accent)
                }
                AssistPhase.Failed -> Eyebrow("RIVET · DIDN'T FINISH", tone = Tone.Red)
                else -> Eyebrow("RIVET", tone = Tone.Cyan)
            }
        }
        val body = when {
            ui.reply.isNotBlank() -> ui.reply + if (ui.phase == AssistPhase.Replying) " ▍" else ""
            ui.phase == AssistPhase.Done -> "Done."
            else -> null
        }
        if (body != null) {
            Spacer(Modifier.size(10.dp))
            Text(body, style = Type.bodyLg, color = p.ink, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(scroll))
        }
        if (ui.phase == AssistPhase.Failed && ui.note != null) {
            Spacer(Modifier.size(10.dp))
            Text(ui.note, style = Type.bodyLg, color = p.ink)
            Spacer(Modifier.size(6.dp))
            Text("Tap the mic to try again, or type.", style = Type.body, color = p.ink2)
        }
    }
}

/** Three cyan dots that pulse one after another while Rivet works. */
@Composable
private fun Thinking() {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a = pulse(low = 0.25f, periodMs = 900 + i * 150)
            Box(Modifier.size(6.dp).alpha(a).clip(CircleShape).background(p.cy))
        }
    }
}

@Composable
private fun TypeRow(onSend: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        ime?.show()
    }
    val send = {
        if (draft.isNotBlank()) {
            onSend(draft.trim())
            draft = ""
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalAlignment = Alignment.Bottom) {
        RecessedField(
            draft, { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = "Ask Rivet anything",
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            fieldModifier = Modifier.focusRequester(focus).testTag(ASSIST_FIELD_TAG),
        )
        Spacer(Modifier.width(10.dp))
        SendKey(send, Modifier.alpha(if (draft.isBlank()) 0.45f else 1f))
    }
}

/**
 * The key row: the haloed talk key, Type and Open Rivet. When the overlay can't listen, the one action that
 * unblocks it (unlocking, or allowing the microphone) takes the full width above.
 */
@Composable
private fun Keys(ui: AssistUi, onMic: () -> Unit, onTyping: (Boolean) -> Unit, onOpen: () -> Unit, onAllowMic: () -> Unit) {
    val listening = ui.phase == AssistPhase.Listening
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (ui.phase) {
            AssistPhase.Locked -> PrimaryKey("Unlock and open Rivet", onOpen, leading = Icons.Outlined.LockOpen, trailingArrow = false)
            AssistPhase.NeedsMic -> PrimaryKey("Allow mic", onAllowMic, leading = Icons.Outlined.Mic, trailingArrow = false)
            else -> {}
        }
        if (ui.phase != AssistPhase.Locked) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.phase != AssistPhase.NeedsMic) {
                    HaloTalkKey(
                        if (listening) TalkState.Listening else TalkState.Idle,
                        onMic,
                        if (listening) "Stop listening" else "Speak to Rivet",
                    )
                }
                SysKey("Type", ui.typing, onTyping)
                SecondaryKey("Open Rivet", onOpen, Modifier.weight(1f), leading = Icons.AutoMirrored.Outlined.OpenInNew, radius = 24.dp)
            }
        }
    }
}

/** The end of [text] when it runs long: live speech grows at the end, so that is the part to keep in view. */
internal fun tail(text: String, limit: Int = 140): String {
    if (text.length <= limit) return text
    val cut = text.takeLast(limit)
    val space = cut.indexOf(' ')
    return "…" + if (space in 0 until limit / 3) cut.substring(space + 1) else cut
}
