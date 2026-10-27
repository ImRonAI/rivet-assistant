package com.hiron.rivet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.CallText
import com.hiron.rivet.core.LiveCall
import com.hiron.rivet.core.LiveCallStatus
import kotlinx.coroutines.delay

const val LIVE_CALL_TAG = "live-call"

/**
 * The call Rivet is answering, as a plate: a crimson live LED and a running clock, who is calling, the latest
 * lines of what is being said, and "Take over" to have the call put through to you. When the call ends it
 * shows Rivet's summary until dismissed.
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
    val (pip, label) = when (call.status) {
        LiveCallStatus.Answering -> p.rec to "Live call"
        LiveCallStatus.Transferring -> p.amb to "Putting it through"
        LiveCallStatus.Ended -> p.ok to "Call ended"
    }
    val clock = if (ended) call.durationSeconds?.let(CallText::duration).orEmpty() else "%d:%02d".format(elapsed / 60, elapsed % 60)
    HudCard(
        label,
        modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag(LIVE_CALL_TAG),
        pip = pip,
        live = !ended,
        meta = clock.ifBlank { null },
        metaColor = if (ended) null else pip,
        outline = pip.copy(alpha = 0.45f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(call.caller, style = Type.headline, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (call.number.isNotBlank() && call.number != call.caller) Text(call.number, style = Type.mono, color = p.ink2)
            }
            if (call.verified) Tag("Verified", p.ok)
        }
        if (ended) {
            Text(call.summary.ifBlank { "No summary." }, style = Type.bodyLg, color = p.ink)
            val result = if (call.takenOver) "You took it over." else CallText.outcome(call.outcome)
            if (result.isNotBlank()) Tag(result.removeSuffix("."), if (call.takenOver) p.accent else p.ok)
            Keycap("Dismiss", onDismiss)
        } else {
            val shown = call.lines.takeLast(3)
            if (shown.isEmpty()) {
                Text("Rivet picked up. What's said will show here.", style = Type.body, color = p.ink2)
            } else {
                val well = RoundedCornerShape(8.dp)
                Column(
                    Modifier.fillMaxWidth().clip(well).background(p.tile).border(1.dp, p.keyLine, well).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown.forEach { line ->
                        Row {
                            Text(
                                CallText.speaker(line).uppercase(), style = Type.labelSm,
                                color = if (line.speaker == "caller") p.cy else p.accent,
                                modifier = Modifier.width(56.dp).padding(top = 3.dp),
                            )
                            Text(line.text, style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (call.status == LiveCallStatus.Transferring) "Your phone will ring." else "Rivet is taking a message.",
                    style = Type.body, color = p.ink2, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Keycap("Take over", onTakeOver, tone = KeyTone.Accent, enabled = call.status == LiveCallStatus.Answering)
            }
        }
    }
}
