package com.hiron.rivet.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.Activity
import com.hiron.rivet.core.ChatMessage
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.LiveCallStatus
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date

/** One-tap requests on the dock, the widget and the launcher shortcuts: the things people ask Rivet for most. */
enum class Quick(val label: String, val tag: String, val prompt: String) {
    CatchUp("Catch me up", "ALL", "Catch me up: unread texts, missed calls, messages callers left, and anything waiting on me."),
    MyDay("My day", "CAL", "What's on my calendar today?"),
    Texts("Texts", "SMS", "Read my unread texts and suggest replies."),
    Calls("Calls", "TEL", "Did anyone call or leave a message? Tell me what they wanted."),
    ;

    companion object {
        fun of(key: String?): Quick? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}

const val STAGE_HINT = "Drag to look · Tap to say hi"

/**
 * Home: Rivet on its stage with a greeting, the transcript sheet once there is a conversation, and the dock with
 * quick requests and the composer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    runtime: RivetRuntime,
    state: RivetState,
    onListen: () -> Unit,
    onReviewApprovals: () -> Unit,
    focusRequests: Int = 0,
) {
    val p = LocalPalette.current
    val keyboard = WindowInsets.isImeVisible
    val talking = state.messages.isNotEmpty()
    val voice = runtime.settings.voiceModel
    var expanded by rememberSaveable { mutableStateOf(false) }
    // While a call is live its plate holds the room the transcript would take, so the robot keeps a readable stage.
    val callLive = state.liveCall.let { it != null && it.status != LiveCallStatus.Ended }
    val sheet = talking && (!callLive || keyboard)
    // The robot owns the screen until there is a conversation, then shares it with the transcript sheet.
    val target = when {
        !sheet -> 1f
        keyboard -> 0.2f
        expanded -> 0.24f
        state.messages.size == 1 -> 0.62f
        else -> 0.5f
    }
    val stageShare by animateFloatAsState(target, spring(dampingRatio = 0.85f, stiffness = 300f), label = "stage")
    Column(Modifier.fillMaxSize().background(p.bg)) {
        val idle = !talking && !keyboard && !callLive
        val fade by animateDpAsState(if (idle) 240.dp else 96.dp, tween(Motion.SLOW, easing = Motion.Emphasized), label = "fade")
        StageFrame(
            Modifier.weight(stageShare).fillMaxWidth(),
            framed = false,
            hint = if (idle) STAGE_HINT else null,
            mood = moodColor(state.activity),
        ) {
            // While the greeting shows, Rivet stands in the upper part of the stage with the words under its feet.
            RobotView(runtime, p.dark, Modifier.fillMaxSize(), focus = if (idle) "greet" else "stage")
            // The stage melts into what sits below it, and the greeting stays legible over the floor.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(fade)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to p.bg.copy(alpha = 0.72f), 1f to p.bg.copy(alpha = 0.96f))),
            )
            StageGreeting(idle, state, voice)
        }
        AnimatedVisibility(state.approvals.isNotEmpty()) {
            ApprovalBanner(state.approvals.size, onReviewApprovals)
        }
        AnimatedVisibility(state.liveCall != null) {
            state.liveCall?.let { call ->
                LiveCallCard(call, onTakeOver = { runtime.takeOverCall(call.callId) }, onDismiss = runtime::dismissLiveCall)
            }
        }
        if (sheet) {
            TranscriptSheet(state, expanded || keyboard, { expanded = it }, Modifier.weight((1f - stageShare).coerceAtLeast(0.01f)))
        } else {
            state.lastError?.let { InfoStrip(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), icon = Icons.Outlined.WarningAmber, tone = Tone.Red) }
            if (stageShare < 1f) Spacer(Modifier.weight(1f - stageShare))
        }
        Dock(
            keyboard = keyboard,
            voice = voice,
            listening = state.activity == Activity.Listening,
            focusRequests = focusRequests,
            onSend = { text, asTask -> if (asTask) runtime.startTask(text) else runtime.send(text) },
            onListen = onListen,
        )
    }
}

const val STAGE_TAG = "stage"

/** The stage lights in the colour of what Rivet is doing: cyan working, amber waiting on you, green done, crimson listening or stuck. */
@Composable
private fun moodColor(activity: Activity): Color? {
    val p = LocalPalette.current
    return when (activity) {
        Activity.Listening, Activity.Failed -> p.rec
        Activity.Waiting -> p.amb
        Activity.Done -> p.ok
        Activity.Thinking, Activity.Searching, Activity.Working, Activity.Texting, Activity.Emailing, Activity.Calling -> p.cy
        Activity.Idle -> null
    }
}

@Composable
private fun BoxScope.StageGreeting(visible: Boolean, state: RivetState, voice: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.SLOW)) + slideInVertically(tween(Motion.SLOW, easing = Motion.Emphasized)) { it / 4 },
        exit = fadeOut(tween(Motion.QUICK)),
        // Clears the stage hint under it.
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 52.dp),
    ) { Greeting(state, voice) }
}

/** The date and time, the big greeting, and one plain line on what to do next. */
@Composable
private fun Greeting(state: RivetState, voice: Boolean) {
    val p = LocalPalette.current
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val words = when (hour) {
        in 5..11 -> "GOOD MORNING"
        in 12..16 -> "GOOD AFTERNOON"
        in 17..21 -> "GOOD EVENING"
        else -> "UP LATE"
    }
    val date = DateFormat.format("EEE d MMM", Date(now)).toString()
    val time = DateFormat.getTimeFormat(context).format(Date(now))
    val (note, tone) = when {
        state.approvals.isNotEmpty() -> "${state.approvals.size} waiting on you" to p.amb
        state.connection == Connection.Offline -> "Not connected" to p.rec
        state.connection == Connection.Connecting -> "Connecting…" to p.amb
        voice -> "Tap the mic or pick a key below." to p.ink2
        else -> "Type below or pick a key." to p.ink2
    }
    Column {
        Eyebrow("${date.uppercase(locale)} · ${time.uppercase(locale)}", tone = Tone.Accent)
        Spacer(Modifier.height(6.dp))
        HeroTitle(words, style = Type.greeting)
        Spacer(Modifier.height(8.dp))
        Text(note, style = Type.body, color = tone)
    }
}

@Composable
private fun ApprovalBanner(count: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .glow(p.amb.copy(alpha = 0.10f), 24.dp, shape)
            .clip(shape).background(p.card).border(1.dp, p.amb.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pip(p.amb, live = true, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Eyebrow("NEEDS YOUR OK", tone = Tone.Amber, dot = false)
            }
            Text(
                if (count == 1) "Rivet is waiting on 1 action" else "Rivet is waiting on $count actions",
                style = Type.title, color = p.ink,
            )
        }
        Spacer(Modifier.width(12.dp))
        Keycap("Review", onClick, tone = KeyTone.Accent)
    }
}

/** The transcript as a sheet over the stage: tap or drag its header to pull it up over Rivet, or back down. */
@Composable
private fun TranscriptSheet(state: RivetState, expanded: Boolean, onExpand: (Boolean) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    val streaming = state.messages.any { it.streaming }
    val working = state.activeTurn != null && state.messages.lastOrNull()?.let { it.fromUser || (it.streaming && it.text.isBlank()) } == true
    var drag by remember { mutableFloatStateOf(0f) }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.BASE, easing = Motion.Emphasized), label = "chevron")
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(p.raised).border(1.dp, p.line, shape)) {
        Column(
            Modifier.fillMaxWidth()
                .draggable(
                    rememberDraggableState { drag += it },
                    Orientation.Vertical,
                    onDragStopped = {
                        if (drag < -24f) onExpand(true) else if (drag > 24f) onExpand(false)
                        drag = 0f
                    },
                )
                .clickable { onExpand(!expanded) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.dot))
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val live = streaming || working
                if (live) {
                    Pip(p.cy, live = true)
                    Spacer(Modifier.width(4.dp))
                } else {
                    Spacer(Modifier.width(4.dp))
                }
                Text("CONVERSATION", style = Type.labelSm, color = p.ink, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                val meta = when {
                    streaming -> "Replying…"
                    working -> "Thinking…"
                    else -> null
                }
                if (meta != null) Text(meta, style = Type.labelSm, color = p.ink2, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.KeyboardArrowUp, contentDescription = if (expanded) "Show Rivet" else "Expand transcript",
                    tint = p.ink2, modifier = Modifier.size(22.dp).rotate(turn),
                )
            }
        }
        Chat(state.messages, state.lastError, Modifier.weight(1f), working = working)
    }
}

const val CHAT_TAG = "chat"
const val COMPOSER_TAG = "composer"

@Composable
internal fun Chat(messages: List<ChatMessage>, error: String?, modifier: Modifier, working: Boolean = false) {
    val p = LocalPalette.current
    val list = rememberLazyListState()
    // Newest at the bottom (reversed layout): streaming text stays in view while you're at the bottom and
    // never pulls you back down if you scrolled up; a new message brings you to the bottom.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(0)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                error ?: "Ask Rivet to text someone, check your calendar, reply to a message, or run a task.",
                color = if (error != null) p.rec else p.ink2, style = Type.body,
            )
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth().testTag(CHAT_TAG).fadeTop(list.canScrollForward),
        state = list,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        item(key = "end") { Spacer(Modifier.height(4.dp)) }
        if (working) item(key = "working") { Working(Modifier.animateItem()) }
        if (error != null) item(key = "error") { Text(error, color = p.rec, style = Type.body, modifier = Modifier.padding(horizontal = 16.dp)) }
        items(messages.asReversed(), key = { it.id }) { Bubble(it, Modifier.animateItem()) }
        item(key = "start") { Spacer(Modifier.height(4.dp)) }
    }
}

/** Older messages fade out under the sheet's header instead of being cut off by it. */
private fun Modifier.fadeTop(on: Boolean): Modifier = if (!on) {
    this
} else {
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 24.dp.toPx() / size.height to Color.Black), blendMode = BlendMode.DstIn)
    }
}

/** Rivet has the message and is working on it, before any reply text arrives. */
@Composable
private fun Working(modifier: Modifier) {
    val p = LocalPalette.current
    val phase = if (rememberReduceMotion()) {
        null
    } else {
        rememberInfiniteTransition(label = "working").animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "working").value
    }
    Row(modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow("RIVET", tone = Tone.Cyan)
        Spacer(Modifier.width(10.dp))
        repeat(3) { i ->
            // Each dot brightens in turn.
            val a = phase?.let { 0.3f + 0.7f * bump((it - i * 0.18f + 1f) % 1f) } ?: 1f
            Box(Modifier.padding(end = 5.dp).size(6.dp).alpha(a).clip(CircleShape).background(p.cy))
        }
    }
}

/** 0 → 1 → 0 over the first half of [t], then rest. */
private fun bump(t: Float): Float = if (t < 0.5f) 1f - kotlin.math.abs(t * 4f - 1f) else 0f

@Composable
private fun Bubble(m: ChatMessage, modifier: Modifier) {
    val p = LocalPalette.current
    val radius = 18.dp
    val tail = 6.dp
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = if (m.fromUser) RoundedCornerShape(radius, radius, tail, radius) else RoundedCornerShape(radius, radius, radius, tail)
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (m.fromUser) p.accentSoft else p.card)
                .border(1.dp, if (m.failed) p.rec.copy(alpha = 0.6f) else if (m.fromUser) p.accent.copy(alpha = 0.30f) else p.edge, shape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!m.fromUser) Eyebrow("RIVET", tone = Tone.Cyan, live = m.streaming)
            val body = remember(m.text, m.fromUser) { if (m.fromUser) AnnotatedString(m.text) else Rich.format(m.text.ifBlank { "…" }, p) }
            ReplyText(body, caret = m.streaming)
        }
        if (m.failed) StatusTag("NOT DELIVERED", Tone.Red)
    }
}

/** The message text as one node; while it streams, a blinking caret is drawn after its last character. */
@Composable
private fun ReplyText(body: AnnotatedString, caret: Boolean) {
    val p = LocalPalette.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val blink = pulse(caret, low = 0.1f, periodMs = 1000)
    Text(
        body,
        color = p.ink,
        style = Type.bodyLg,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithContent {
            drawContent()
            val l = layout
            if (caret && l != null && l.lineCount > 0) {
                val line = l.lineCount - 1
                val top = l.getLineTop(line) + 3.dp.toPx()
                val bottom = l.getLineBottom(line) - 3.dp.toPx()
                drawRect(p.cy.copy(alpha = blink), Offset(l.getLineRight(line) + 3.dp.toPx(), top), Size(2.dp.toPx(), bottom - top))
            }
        },
    )
}

/** The dock: a task latch and quick requests, then the composer with its talk or send key. */
@Composable
private fun Dock(keyboard: Boolean, voice: Boolean, listening: Boolean, focusRequests: Int, onSend: (String, Boolean) -> Unit, onListen: () -> Unit) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    var asTask by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            focus.requestFocus()
            ime?.show()
        }
    }
    val send = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSend(t, asTask)
            text = ""
            asTask = false
        }
    }
    Column(Modifier.fillMaxWidth().background(p.bg)) {
        Rule(color = p.line)
        if (!keyboard) {
            ChipRail(Modifier.padding(top = 12.dp), start = 16.dp) {
                item(key = "task") {
                    PillChip(
                        "Task", { asTask = !asTask }, Modifier.semantics { selected = asTask },
                        leading = Icons.Outlined.Bolt, selected = asTask,
                    )
                }
                items(Quick.entries, key = { it.name }) { q -> PillChip(q.label, { onSend(q.prompt, false) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            RecessedField(
                text, { text = it },
                modifier = Modifier.weight(1f),
                placeholder = if (asTask) "Describe the goal for a background task…" else "Ask Rivet anything",
                singleLine = false,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                fieldModifier = Modifier.focusRequester(focus).testTag(COMPOSER_TAG),
            )
            Spacer(Modifier.width(10.dp))
            // A field-high slot: the talk key's halo spills past it without changing the row's height.
            Box(Modifier.size(width = 52.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                val unbounded = Modifier.wrapContentSize(unbounded = true)
                when {
                    text.isNotBlank() -> SendKey(send, unbounded)
                    voice -> HaloTalkKey(if (listening) TalkState.Listening else TalkState.Idle, onListen, "Speak to Rivet", unbounded)
                    else -> Box(
                        unbounded.alpha(0.4f).clearAndSetSemantics {
                            contentDescription = "Send"
                            disabled()
                        },
                    ) { SendKey({}) }
                }
            }
        }
    }
}
