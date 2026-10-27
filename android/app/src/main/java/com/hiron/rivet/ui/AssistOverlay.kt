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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    /** The mono status readout, e.g. LISTENING or SEARCHING. */
    val status: String = "",
    /** Why Rivet is stuck, or what the user can do next. */
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

/**
 * Rivet over another app: a scrim, and a HUD plate along the bottom with Rivet's head and status, the words
 * as they are heard, Rivet's reply as it streams, and the talk, type and open-app keys. Tapping the scrim
 * closes it.
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
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.36f), 0.5f to Color.Black.copy(alpha = 0.56f), 1f to Color.Black.copy(alpha = 0.86f)))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close Rivet", onClick = onDismiss)
                .testTag(ASSIST_SCRIM_TAG),
        )
        AnimatedVisibility(
            shown,
            modifier = Modifier.statusBarsPadding(),
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it / 3 } + fadeIn(tween(160)),
        ) {
            Plate(ui, onMic, onTyping, onSend, onOpen, onAllowMic)
        }
    }
}

private data class Led(val color: Color, val live: Boolean)

private fun led(phase: AssistPhase, p: Palette) = when (phase) {
    AssistPhase.Ready -> Led(p.ink2, false)
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
    val shape = RoundedCornerShape(22.dp)
    val led = led(ui.phase, p)
    val replies = ui.phase in setOf(AssistPhase.Working, AssistPhase.Replying, AssistPhase.Done, AssistPhase.Waiting) ||
        (ui.phase == AssistPhase.Failed && ui.note != null)
    Column(
        Modifier.navigationBarsPadding().imePadding().padding(horizontal = 10.dp).padding(bottom = 10.dp)
            .widthIn(max = 560.dp).fillMaxWidth()
            .shadow(24.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.raised, p.hud)))
            .border(1.dp, p.hudLine, shape)
            .rivets(p.keyLine, inset = 9.dp)
            .drawWithContent {
                drawContent()
                drawLine(Color.White.copy(alpha = 0.07f), Offset(22.dp.toPx(), 0.75.dp.toPx()), Offset(size.width - 22.dp.toPx(), 0.75.dp.toPx()), 1.dp.toPx())
            }
            // Taps anywhere on the plate stay on it; only the scrim around it closes the overlay.
            .pointerInput(Unit) { detectTapGestures() }
            .testTag(ASSIST_PANEL_TAG),
    ) {
        Header(ui.status, led)
        Rule(color = p.hudLine)
        val blocked = ui.phase == AssistPhase.NeedsMic || ui.phase == AssistPhase.Locked
        // With the keyboard open and nothing asked yet, the field is the whole body.
        val heard = !blocked && !(ui.typing && ui.heard.isBlank() && ui.phase == AssistPhase.Ready)
        if (blocked || heard || replies) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (blocked) Blocked(ui)
                if (heard) Heard(ui)
                if (replies) Reply(ui, onOpen)
            }
        }
        if (ui.typing && ui.phase != AssistPhase.Locked) TypeRow(onSend)
        Keys(ui, onMic, onTyping, onOpen, onAllowMic)
    }
}

/** Rivet's head and wordmark on the left; the mode readout with its LED on the right, as in the app's header. */
@Composable
private fun Header(status: String, led: Led) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 18.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(led.color.copy(alpha = if (led.live) 0.34f else 0.16f), Color.Transparent)), radius = size.minDimension / 2)
            },
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.rivet_head), contentDescription = "Rivet", modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append("RIV")
                    withStyle(SpanStyle(color = p.accent)) { append("E") }
                    append("T")
                },
                style = Type.brand.copy(fontSize = 24.sp, lineHeight = 24.sp), color = p.ink,
            )
            Spacer(Modifier.size(4.dp))
            Text("R-7 · ASSIST", style = Type.labelSm, color = p.ink2, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("MODE", style = Type.labelSm, color = p.ink2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pip(led.color, live = led.live, size = 7.dp)
                Spacer(Modifier.width(2.dp))
                Odometer(status, Type.tele, led.color)
            }
        }
    }
}

/** The user's words in large type, with a crimson caret and a level meter while the microphone is live. */
@Composable
private fun Heard(ui: AssistUi) {
    val p = LocalPalette.current
    val listening = ui.phase == AssistPhase.Listening
    val text = tail(ui.heard)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("YOU", style = Type.labelSm, color = if (listening) p.rec else p.ink2)
        Text(
            buildAnnotatedString {
                if (text.isBlank()) withStyle(SpanStyle(color = p.ink2)) { append(if (listening) "Listening…" else "Ask Rivet anything") } else append(text)
                // A no-break space keeps the caret on the line with the last word.
                if (listening) withStyle(SpanStyle(color = p.rec)) { append(" ▍") }
            },
            style = Type.headline, color = p.ink,
        )
        if (listening) LevelMeter(ui.level)
        if (ui.phase == AssistPhase.Ready && ui.note != null) Text(ui.note, style = Type.body, color = p.ink2)
    }
}

/** Why the overlay cannot listen yet, in place of the user's words. */
@Composable
private fun Blocked(ui: AssistUi) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (ui.phase == AssistPhase.Locked) "Your phone is locked" else "Rivet can't hear you yet", style = Type.headline, color = p.ink)
        ui.note?.let { Text(it, style = Type.body, color = p.ink2) }
    }
}

/** A row of LED segments that light crimson with the microphone level. */
@Composable
private fun LevelMeter(level: Float) {
    val p = LocalPalette.current
    val segments = 24
    val lit = (level.coerceIn(0f, 1f) * segments).toInt()
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("MIC", style = Type.labelSm, color = p.ink2)
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(segments) { i ->
                Box(Modifier.size(width = 4.dp, height = 10.dp).clip(RoundedCornerShape(1.dp)).background(if (i < lit) p.rec else p.keyLine))
            }
        }
    }
}

/** Rivet's side: a recessed tile like its chat bubbles, live cyan while it works and streams. */
@Composable
private fun Reply(ui: AssistUi, onReview: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    val outline = when (ui.phase) {
        AssistPhase.Waiting -> p.amb.copy(alpha = 0.55f)
        AssistPhase.Failed -> p.rec.copy(alpha = 0.7f)
        else -> p.keyLine
    }
    val scroll = rememberScrollState()
    LaunchedEffect(ui.reply) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(p.tile).border(1.dp, outline, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (ui.phase) {
                AssistPhase.Working -> {
                    Text("RIVET", style = Type.labelSm, color = p.cy)
                    Spacer(Modifier.width(6.dp))
                    repeat(3) { Pip(p.cy, live = true, size = 5.dp) }
                }
                AssistPhase.Replying -> {
                    Text("RIVET", style = Type.labelSm, color = p.cy)
                    Spacer(Modifier.width(2.dp))
                    Pip(p.cy, live = true, size = 5.dp)
                }
                AssistPhase.Waiting -> {
                    Pip(p.amb, live = true, size = 5.dp)
                    Spacer(Modifier.width(2.dp))
                    Text("NEEDS YOUR OK", style = Type.labelSm, color = p.amb)
                }
                AssistPhase.Failed -> Text("RIVET · STUCK", style = Type.labelSm, color = p.rec)
                else -> {
                    Text("RIVET", style = Type.labelSm, color = p.ink2)
                    Spacer(Modifier.width(2.dp))
                    Pip(p.ok, size = 5.dp)
                }
            }
        }
        val body = when {
            ui.reply.isNotBlank() -> ui.reply + if (ui.phase == AssistPhase.Replying) " ▍" else ""
            ui.phase == AssistPhase.Done -> "Done."
            else -> null
        }
        if (body != null) {
            Text(body, style = Type.bodyLg, color = p.ink, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(scroll))
        }
        if (ui.phase == AssistPhase.Waiting) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ui.waiting == 1) "Rivet is waiting on 1 action" else "Rivet is waiting on ${ui.waiting} actions",
                    style = Type.title, color = p.ink, modifier = Modifier.weight(1f),
                )
                Keycap("Review", onReview, tone = KeyTone.Accent)
            }
        }
        if (ui.phase == AssistPhase.Failed && ui.note != null) {
            Text(ui.note, style = Type.bodyLg, color = p.ink)
            Text("Tap the mic to try again, or type.", style = Type.body, color = p.ink2)
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
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp), verticalAlignment = Alignment.Bottom) {
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
        Spacer(Modifier.width(8.dp))
        IconKey(Icons.AutoMirrored.Filled.Send, "Send", send, tone = KeyTone.Accent)
    }
}

/**
 * The key row. The big mic is the one orange key unless something else is the action that matters: allowing
 * the microphone, unlocking, reviewing an approval, or sending typed text.
 */
@Composable
private fun Keys(ui: AssistUi, onMic: () -> Unit, onTyping: (Boolean) -> Unit, onOpen: () -> Unit, onAllowMic: () -> Unit) {
    val p = LocalPalette.current
    val listening = ui.phase == AssistPhase.Listening
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (ui.phase) {
            AssistPhase.Locked -> Keycap("Unlock and open Rivet", onOpen, Modifier.height(64.dp).weight(1f), tone = KeyTone.Accent, icon = Icons.Filled.LockOpen)
            AssistPhase.NeedsMic -> Keycap("Allow mic", onAllowMic, Modifier.height(64.dp), tone = KeyTone.Accent, icon = Icons.Filled.Mic)
            else -> IconKey(
                Icons.Filled.Mic,
                if (listening) "Stop listening" else "Speak to Rivet",
                onMic,
                tone = if (ui.typing || ui.phase == AssistPhase.Waiting) KeyTone.Plain else KeyTone.Accent,
                size = 64.dp,
                ring = if (listening) p.rec else null,
            )
        }
        if (ui.phase != AssistPhase.Locked) {
            SysKey("Type", ui.typing, onTyping)
            Spacer(Modifier.weight(1f))
            Keycap("Open Rivet", onOpen, icon = Icons.AutoMirrored.Filled.OpenInNew)
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
