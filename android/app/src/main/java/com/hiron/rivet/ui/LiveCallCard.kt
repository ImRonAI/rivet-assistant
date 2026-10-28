package com.hiron.rivet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PhoneForwarded
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.CallText
import com.hiron.rivet.core.LiveCall
import com.hiron.rivet.core.LiveCallStatus
import kotlinx.coroutines.delay

const val LIVE_CALL_TAG = "live-call"

/**
 * The call Rivet is answering, as a lit plate: a live tag and a running clock, who is calling, the latest lines of
 * what is being said, and "Take over" to have the call put through to you. When the call ends it shows Rivet's
 * summary until dismissed.
 */
@Composable
fun LiveCallCard(call: LiveCall, onTakeOver: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val ended = call.status == LiveCallStatus.Ended
    val elapsed by produceState(0L, call.callId, ended) {
        while (!ended) {
            value = ((System.currentTimeMillis() - call.startedAt) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    val (tone, tag, label) = when (call.status) {
        LiveCallStatus.Answering -> Triple(Tone.Red, "LIVE", "Live call")
        LiveCallStatus.Transferring -> Triple(Tone.Amber, "PUTTING IT THROUGH", "Putting it through")
        LiveCallStatus.Ended -> Triple(Tone.Green, "CALL ENDED", "Call ended")
    }
    val c = tone.color()
    Plate(c.copy(alpha = if (ended) 0.4f else 0.5f), glow = if (ended) null else c.copy(alpha = 0.10f), modifier = modifier.testTag(LIVE_CALL_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (call.status == LiveCallStatus.Answering) {
                Pip(p.rec, live = true, size = 6.dp)
                Spacer(Modifier.width(2.dp))
            }
            StatusTag(tag, tone)
            Spacer(Modifier.width(10.dp))
            // The label repeats the tag in words, so where it doesn't fit whole it steps aside rather than ending in "…".
            var fits by remember(label) { mutableStateOf(true) }
            Text(
                label, style = Type.labelSm, color = p.ink, maxLines = 1, softWrap = false,
                onTextLayout = { fits = !it.hasVisualOverflow },
                modifier = Modifier.weight(1f).alpha(if (fits) 1f else 0f),
            )
            Spacer(Modifier.width(8.dp))
            if (!ended) {
                TimerPill(elapsed.toInt(), 0)
            } else {
                call.durationSeconds?.let { Text(CallText.duration(it), style = Type.labelMd, color = p.ink2, maxLines = 1) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(call.caller, style = Type.headline, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val number = call.number.isNotBlank() && call.number != call.caller
            if (number || call.verified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (number) Text(call.number, style = Type.mono, color = p.ink2, maxLines = 1, modifier = Modifier.padding(end = 10.dp))
                    if (call.verified) StatusTag("VERIFIED", Tone.Green)
                }
            }
        }
        if (ended) Ended(call, onDismiss) else Live(call, onTakeOver)
    }
}

@Composable
private fun Live(call: LiveCall, onTakeOver: () -> Unit) {
    val p = LocalPalette.current
    val well = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(well).background(p.field).border(1.dp, p.edgeSoft, well).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val shown = call.lines.takeLast(3)
        if (shown.isEmpty()) Text("Rivet picked up. What's said will show here.", style = Type.body, color = p.ink2)
        shown.forEach { line ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val caller = line.speaker == "caller"
                Eyebrow(CallText.speaker(line).uppercase(), tone = if (caller) Tone.Cyan else Tone.Accent, dot = false)
                // One long turn must not push the newer lines and the Take over key off the plate.
                Text(line.text, style = Type.bodyLg, color = p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (call.status == LiveCallStatus.Transferring) "Your phone will ring." else "Rivet is taking a message.",
            style = Type.body, color = p.ink2, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Keycap(
            "Take over", onTakeOver, tone = KeyTone.Accent, icon = Icons.AutoMirrored.Outlined.PhoneForwarded,
            enabled = call.status == LiveCallStatus.Answering,
        )
    }
}

@Composable
private fun Ended(call: LiveCall, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Text(call.summary.ifBlank { "No summary." }, style = Type.quote, color = p.ink)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val outcome = if (call.takenOver) "YOU TOOK IT OVER" else CallText.outcome(call.outcome).removeSuffix(".").uppercase()
        Row(Modifier.weight(1f)) {
            if (outcome.isNotBlank()) StatusTag(outcome, if (call.takenOver) Tone.Accent else Tone.Neutral)
        }
        Spacer(Modifier.width(12.dp))
        Keycap("Dismiss", onDismiss)
    }
}

/** The call's card: the card fill, an edge in the call's status colour and, while it is live, a faint glow of it. */
@Composable
private fun Plate(edge: Color, glow: Color?, modifier: Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .then(if (glow != null) Modifier.glow(glow, 20.dp, shape) else Modifier)
            .clip(shape).background(p.card).border(1.dp, edge, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}
