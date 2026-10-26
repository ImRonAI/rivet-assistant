package com.hiron.rivet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.rivet.core.Activity
import com.hiron.rivet.core.ChatMessage
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState

@Composable
fun HomeScreen(runtime: RivetRuntime, state: RivetState, onListen: () -> Unit, onReviewApprovals: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        Box(Modifier.fillMaxWidth().weight(0.9f)) {
            RobotView(runtime, p.dark, Modifier.fillMaxSize())
            StatusChip(state, Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        AnimatedVisibility(state.approvals.isNotEmpty()) {
            ApprovalBanner(state.approvals.size, onReviewApprovals)
        }
        Chat(state.messages, state.lastError, Modifier.weight(1f))
        Composer(
            busy = state.activeTurn != null,
            onSend = { text, asTask -> if (asTask) runtime.startTask(text) else runtime.send(text) },
            onListen = onListen,
        )
    }
}

@Composable
private fun StatusChip(state: RivetState, modifier: Modifier) {
    val p = LocalPalette.current
    val (color, label) = when (state.connection) {
        Connection.Online -> p.ok to activityLabel(state.activity)
        Connection.Connecting -> p.amb to "CONNECTING"
        Connection.Offline -> p.ink2 to "OFFLINE"
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(p.card.copy(alpha = 0.86f)).border(1.dp, p.line, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(8.dp))
        Text(label, style = Label, color = p.ink)
    }
}

private fun activityLabel(a: Activity) = when (a) {
    Activity.Idle -> "READY"
    Activity.Listening -> "LISTENING"
    Activity.Thinking -> "THINKING"
    Activity.Searching -> "SEARCHING"
    Activity.Working -> "WORKING"
    Activity.Texting -> "TEXTING"
    Activity.Emailing -> "EMAIL"
    Activity.Calling -> "CALLING"
    Activity.Waiting -> "NEEDS YOUR OK"
    Activity.Done -> "DONE"
    Activity.Failed -> "STUCK"
}

@Composable
private fun ApprovalBanner(count: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(p.accent).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White)
        Spacer(Modifier.size(10.dp))
        Text(
            if (count == 1) "Rivet needs your OK on 1 action" else "Rivet needs your OK on $count actions",
            color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
        )
        Text("REVIEW", style = Label, color = Color.White)
    }
}

@Composable
private fun Chat(messages: List<ChatMessage>, error: String?, modifier: Modifier) {
    val p = LocalPalette.current
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                error ?: "Ask Rivet to text someone, check your calendar, reply to a message, or run a task.",
                color = if (error != null) p.rec else p.ink2, style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        items(messages, key = { it.id }) { Bubble(it) }
        if (error != null) item { Text(error, color = p.rec, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(16.dp, 16.dp, if (m.fromUser) 4.dp else 16.dp, if (m.fromUser) 16.dp else 4.dp)
        Column(
            Modifier.widthIn(max = 320.dp).clip(shape)
                .background(if (m.fromUser) p.ink else p.card)
                .border(1.dp, if (m.failed) p.rec else p.line, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (!m.fromUser) Text("RIVET", style = Label.copy(fontSize = 9.sp), color = p.accent)
            Text(
                m.text.ifBlank { "…" } + if (m.streaming) " ▍" else "",
                color = if (m.fromUser) p.bg else p.ink,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Composer(busy: Boolean, onSend: (String, Boolean) -> Unit, onListen: () -> Unit) {
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
    Column(Modifier.fillMaxWidth().background(p.bgHi).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = asTask, onClick = { asTask = !asTask },
                label = { Text("Background task", style = MaterialTheme.typography.labelMedium) },
            )
            Spacer(Modifier.weight(1f))
            if (busy) Text("WORKING", style = Label, color = p.cy, fontWeight = FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (asTask) "Describe the goal…" else "Message Rivet") },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.accent, unfocusedBorderColor = p.line,
                    focusedContainerColor = p.card, unfocusedContainerColor = p.card,
                ),
            )
            Spacer(Modifier.size(6.dp))
            if (text.isBlank()) {
                IconButton(
                    onClick = onListen, modifier = Modifier.size(52.dp).semantics { contentDescription = "Speak to Rivet" },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = p.ink, contentColor = p.bg),
                ) { Icon(Icons.Filled.Mic, contentDescription = null) }
            } else {
                IconButton(
                    onClick = send, modifier = Modifier.size(52.dp).semantics { contentDescription = "Send" },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = p.accent, contentColor = Color.White),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }
            }
        }
    }
}
