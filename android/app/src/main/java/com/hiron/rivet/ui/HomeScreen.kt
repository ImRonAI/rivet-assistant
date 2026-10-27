package com.hiron.rivet.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hiron.rivet.core.Activity
import com.hiron.rivet.core.ChatMessage
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

/**
 * The test bay: the robot on its stage with a greeting, the transcript sheet once there is a conversation,
 * and the teach-pendant dock with quick keys and the composer.
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The robot owns the screen until there is a conversation, then shares it with the transcript sheet.
    val target = when {
        !talking -> 1f
        keyboard -> 0.2f
        expanded -> 0.24f
        else -> 0.5f
    }
    val stageShare by animateFloatAsState(target, spring(dampingRatio = 0.85f, stiffness = 300f), label = "stage")
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Stage(runtime, Modifier.weight(stageShare)) { StageGreeting(!talking && !keyboard, state) }
        AnimatedVisibility(state.approvals.isNotEmpty()) {
            ApprovalBanner(state.approvals.size, onReviewApprovals)
        }
        if (talking) {
            TranscriptSheet(state, expanded || keyboard, { expanded = it }, Modifier.weight((1f - stageShare).coerceAtLeast(0.01f)))
        } else {
            state.lastError?.let { ErrorStrip(it) }
            if (stageShare < 1f) Spacer(Modifier.weight(1f - stageShare))
        }
        Dock(
            keyboard = keyboard,
            listening = state.activity == Activity.Listening,
            focusRequests = focusRequests,
            onSend = { text, asTask -> if (asTask) runtime.startTask(text) else runtime.send(text) },
            onListen = onListen,
        )
    }
}

const val STAGE_TAG = "stage"

@Composable
private fun Stage(runtime: RivetRuntime, modifier: Modifier, overlay: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().background(Brush.radialGradient(listOf(p.raised, p.bg))).testTag(STAGE_TAG)) {
        RobotView(runtime, p.dark, Modifier.fillMaxSize())
        // The stage melts into the dock below it, and anything written on it stays legible.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(132.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, p.bg.copy(alpha = 0.92f)))),
        )
        overlay()
    }
}

@Composable
private fun BoxScope.StageGreeting(visible: Boolean, state: RivetState) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomStart),
    ) { Greeting(state) }
}

/** The big display greeting on the stage, with the date and time in telemetry type under it. */
@Composable
private fun Greeting(state: RivetState) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val words = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Up late"
    }
    val date = DateFormat.format("EEE d MMM", Date(now)).toString()
    val time = DateFormat.getTimeFormat(context).format(Date(now))
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            buildAnnotatedString {
                append(words.uppercase(Locale.getDefault()))
                withStyle(SpanStyle(color = p.accent)) { append(".") }
            },
            style = Type.greeting, color = p.ink,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${date.uppercase(Locale.getDefault())} · ${time.uppercase(Locale.getDefault())}", style = Type.labelMd, color = p.ink)
            Spacer(Modifier.size(10.dp))
            Box(Modifier.size(width = 18.dp, height = 1.dp).background(p.ink2))
            Spacer(Modifier.size(10.dp))
            Text(
                if (state.approvals.isNotEmpty()) "${state.approvals.size} WAITING ON YOU" else "TAP THE MIC OR A KEY",
                style = Type.labelMd.copy(letterSpacing = 0.08.em), color = if (state.approvals.isNotEmpty()) p.amb else p.ink2,
            )
        }
    }
}

@Composable
private fun ErrorStrip(text: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.rec.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pip(p.rec)
        Spacer(Modifier.size(6.dp))
        Text(text, style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ApprovalBanner(count: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(shape).background(p.hud)
            .border(1.dp, p.amb.copy(alpha = 0.55f), shape).rivets(p.keyLine).clickable(onClick = onClick)
            .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pip(p.amb, live = true)
        Spacer(Modifier.size(6.dp))
        Column(Modifier.weight(1f)) {
            Text("NEEDS YOUR OK", style = Type.labelSm, color = p.amb)
            Text(
                if (count == 1) "Rivet is waiting on 1 action" else "Rivet is waiting on $count actions",
                style = Type.title, color = p.ink,
            )
        }
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
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(p.raised).border(1.dp, p.hudLine, shape)) {
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
            Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.hudLine))
            HudHeaderRow(
                "Transcript",
                pip = if (streaming || working) p.cy else p.ink2,
                live = streaming || working,
                meta = when {
                    streaming -> "Streaming"
                    working -> "Thinking"
                    else -> "${state.messages.size} msgs"
                },
                metaColor = if (streaming || working) p.cy else null,
                trailing = {
                    Icon(
                        Icons.Filled.KeyboardArrowUp, contentDescription = if (expanded) "Show Rivet" else "Expand transcript",
                        tint = p.ink2, modifier = Modifier.padding(start = 8.dp).size(18.dp).rotate(turn),
                    )
                },
            )
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
        modifier.fillMaxWidth().testTag(CHAT_TAG),
        state = list,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
    ) {
        item(key = "end") { Spacer(Modifier.height(6.dp)) }
        if (working) item(key = "working") { Working(Modifier.animateItem()) }
        if (error != null) item(key = "error") { Text(error, color = p.rec, style = Type.body, modifier = Modifier.padding(horizontal = 16.dp)) }
        items(messages.asReversed(), key = { it.id }) { Bubble(it, Modifier.animateItem()) }
        item(key = "start") { Spacer(Modifier.height(6.dp)) }
    }
}

/** Rivet has the message and is working on it, before any reply text arrives. */
@Composable
private fun Working(modifier: Modifier) {
    val p = LocalPalette.current
    Row(modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("RIVET", style = Type.labelSm, color = p.cy)
        Spacer(Modifier.size(6.dp))
        repeat(3) { Pip(p.cy, live = true, size = 5.dp) }
    }
}

@Composable
private fun Bubble(m: ChatMessage, modifier: Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(14.dp, 14.dp, if (m.fromUser) 4.dp else 14.dp, if (m.fromUser) 14.dp else 4.dp)
        Column(
            Modifier.widthIn(max = 320.dp).clip(shape)
                .background(if (m.fromUser) Brush.verticalGradient(listOf(Color(0xFFFF6A33), p.accent)) else Brush.verticalGradient(listOf(p.tile, p.tile)))
                .border(1.dp, if (m.failed) p.rec else if (m.fromUser) Color.Transparent else p.keyLine, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!m.fromUser) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RIVET", style = Type.labelSm, color = if (m.streaming) p.cy else p.ink2)
                    if (m.streaming) {
                        Spacer(Modifier.size(2.dp))
                        Pip(p.cy, live = true, size = 5.dp)
                    }
                }
            }
            Text(
                m.text.ifBlank { "…" } + if (m.streaming) " ▍" else "",
                color = if (m.fromUser) p.accentInk else p.ink,
                style = Type.bodyLg,
            )
            if (m.failed) Text("NOT DELIVERED", style = Type.labelSm, color = if (m.fromUser) p.accentInk else p.rec)
        }
    }
}

/** The teach pendant: a task latch and quick keys, then the recessed composer with its talk/send key. */
@Composable
private fun Dock(keyboard: Boolean, listening: Boolean, focusRequests: Int, onSend: (String, Boolean) -> Unit, onListen: () -> Unit) {
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
    Column(Modifier.fillMaxWidth().background(p.raised)) {
        Rule(color = p.hudLine)
        if (!keyboard) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SysKey("Task", asTask, { asTask = it })
                Quick.entries.forEach { q -> Keycap(q.label, { onSend(q.prompt, false) }, tag = q.tag) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
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
            Spacer(Modifier.size(8.dp))
            if (text.isBlank()) {
                IconKey(Icons.Filled.Mic, "Speak to Rivet", onListen, tone = KeyTone.Accent, ring = if (listening) p.rec else null)
            } else {
                IconKey(Icons.AutoMirrored.Filled.Send, "Send", send, tone = KeyTone.Accent)
            }
        }
    }
}
