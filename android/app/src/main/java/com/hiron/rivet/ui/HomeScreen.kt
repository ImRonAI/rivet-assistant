package com.hiron.rivet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hiron.rivet.core.ChatMessage
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState

/** One-tap requests on the dock: the things people ask Rivet for most. */
private data class Quick(val label: String, val tag: String, val prompt: String)

private val QUICK = listOf(
    Quick("Catch me up", "ALL", "Catch me up: unread texts, missed calls, messages callers left, and anything waiting on me."),
    Quick("My day", "CAL", "What's on my calendar today?"),
    Quick("Texts", "SMS", "Read my unread texts and suggest replies."),
    Quick("Calls", "TEL", "Did anyone call or leave a message? Tell me what they wanted."),
)

/**
 * The test bay: the robot on its stage, the transcript once there is one, and the teach-pendant dock with
 * quick keys and the composer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(runtime: RivetRuntime, state: RivetState, onListen: () -> Unit, onReviewApprovals: () -> Unit) {
    val p = LocalPalette.current
    val keyboard = WindowInsets.isImeVisible
    val talking = state.messages.isNotEmpty()
    // The robot gives the transcript room once there is a conversation, and more while typing.
    val stageShare = when {
        keyboard -> 0.28f
        talking -> 0.45f
        else -> 1f
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Stage(
            runtime,
            hint = if (talking || keyboard) null else "Tap the mic or a key below",
            modifier = Modifier.weight(stageShare),
        )
        AnimatedVisibility(state.approvals.isNotEmpty()) {
            ApprovalBanner(state.approvals.size, onReviewApprovals)
        }
        if (talking) {
            Transcript(state, Modifier.weight(1f - stageShare))
        } else {
            state.lastError?.let { ErrorStrip(it) }
            if (keyboard) Spacer(Modifier.weight(1f - stageShare))
        }
        Dock(
            keyboard = keyboard,
            onSend = { text, asTask -> if (asTask) runtime.startTask(text) else runtime.send(text) },
            onListen = onListen,
        )
    }
}

@Composable
private fun Stage(runtime: RivetRuntime, hint: String?, modifier: Modifier) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().background(Brush.radialGradient(listOf(p.raised, p.bg))).testTag(STAGE_TAG)) {
        RobotView(runtime, p.dark, Modifier.fillMaxSize())
        if (hint != null) {
            Text(
                hint.uppercase(), style = Type.labelMd.copy(letterSpacing = 0.06.em), color = p.ink2,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
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
            .border(1.dp, p.amb.copy(alpha = 0.55f), shape).clickable(onClick = onClick)
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

@Composable
private fun Transcript(state: RivetState, modifier: Modifier) {
    val p = LocalPalette.current
    val streaming = state.messages.any { it.streaming }
    Column(modifier.fillMaxWidth()) {
        Rule()
        HudHeaderRow(
            "Transcript",
            pip = if (streaming) p.cy else p.ink2,
            live = streaming,
            meta = if (streaming) "Streaming" else "${state.messages.size} msgs",
            metaColor = if (streaming) p.cy else null,
        )
        Chat(state.messages, state.lastError, Modifier.weight(1f))
    }
}

const val CHAT_TAG = "chat"
const val STAGE_TAG = "stage"
const val COMPOSER_TAG = "composer"

@Composable
internal fun Chat(messages: List<ChatMessage>, error: String?, modifier: Modifier) {
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
        item { Spacer(Modifier.height(4.dp)) }
        if (error != null) item { Text(error, color = p.rec, style = Type.body, modifier = Modifier.padding(horizontal = 16.dp)) }
        items(messages.asReversed(), key = { it.id }) { Bubble(it) }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(12.dp, 12.dp, if (m.fromUser) 4.dp else 12.dp, if (m.fromUser) 12.dp else 4.dp)
        Column(
            Modifier.widthIn(max = 320.dp).clip(shape)
                .background(if (m.fromUser) p.accent else p.tile)
                .then(if (m.failed) Modifier.border(1.dp, p.rec, shape) else Modifier)
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
private fun Dock(keyboard: Boolean, onSend: (String, Boolean) -> Unit, onListen: () -> Unit) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    var asTask by remember { mutableStateOf(false) }
    val send = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSend(t, asTask)
            text = ""
            asTask = false
        }
    }
    Column(Modifier.fillMaxWidth().background(p.raised)) {
        Rule()
        if (!keyboard) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SysKey("Task", asTask, { asTask = it })
                QUICK.forEach { q -> Keycap(q.label, { onSend(q.prompt, false) }, tag = q.tag) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            RecessedField(
                text, { text = it },
                modifier = Modifier.weight(1f),
                placeholder = if (asTask) "Describe the goal for a background task…" else "Message Rivet",
                singleLine = false,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                fieldModifier = Modifier.testTag(COMPOSER_TAG),
            )
            Spacer(Modifier.size(8.dp))
            if (text.isBlank()) {
                IconKey(Icons.Filled.Mic, "Speak to Rivet", onListen)
            } else {
                IconKey(Icons.AutoMirrored.Filled.Send, "Send", send, tone = KeyTone.Accent)
            }
        }
    }
}
