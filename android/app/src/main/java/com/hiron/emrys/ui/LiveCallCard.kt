package com.hiron.emrys.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PhoneForwarded
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.emrys.core.CallText
import com.hiron.emrys.core.LiveCall
import com.hiron.emrys.core.LiveCallStatus
import kotlinx.coroutines.delay

const val LIVE_CALL_TAG = "live-call"

/** Who is calling: a serif line, like a byline. */
private val Caller = Type.step.copy(fontSize = 28.sp, lineHeight = 32.sp)

/**
 * The call Emrys is answering, on a glass card: a status dot and a sentence with the running clock, who is calling,
 * the latest lines of what is being said, and "Take over" to have the call put through to you. When the call ends
 * it shows Emrys's summary until dismissed.
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
    val (tone, status) = when (call.status) {
        LiveCallStatus.Answering -> Tone.Red to "Live"
        LiveCallStatus.Transferring -> Tone.Amber to "Putting it through"
        LiveCallStatus.Ended -> Tone.Green to "Call ended"
    }
    Plate(live = !ended, modifier = modifier.testTag(LIVE_CALL_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalDot(tone.color(), live = call.status == LiveCallStatus.Answering, size = 7.dp)
            Spacer(Modifier.width(10.dp))
            Text(status, style = Type.labelLg, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            if (!ended) {
                Text(clock(elapsed.toInt()), style = Type.tele, color = p.ink2, maxLines = 1)
            } else {
                call.durationSeconds?.let { Text(CallText.duration(it), style = Type.tele, color = p.ink2, maxLines = 1) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Plain.phone(call.caller), style = Caller, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val number = call.number.isNotBlank() && call.number != call.caller
            if (number || call.verified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (number) Text(Plain.phone(call.number), style = Type.mono, color = p.ink2, maxLines = 1, modifier = Modifier.padding(end = 14.dp))
                    if (call.verified) StatusTag("Verified", Tone.Green, outline = true)
                }
            }
        }
        if (ended) Ended(call, onDismiss) else Live(call, onTakeOver)
    }
}

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun Live(call: LiveCall, onTakeOver: () -> Unit) {
    val p = LocalPalette.current
    // The card shows the latest two lines so the robot keeps its stage; "Show all" opens the last three in full.
    var all by remember(call.callId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Rule(color = p.line)
        val shown = call.lines.takeLast(if (all) 3 else 2)
        if (shown.isEmpty()) Text("Emrys picked up. What's said will show here.", style = Type.body, color = p.ink2)
        shown.forEach { line ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(CallText.speaker(line), style = Type.labelMd, color = p.ink2, maxLines = 1)
                // One long turn must not push the newer lines and the Take over key off the card.
                Text(line.text, style = Type.bodyLg, color = p.ink, maxLines = if (all) 3 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (call.lines.size > 2) {
            TextKey(if (all) "Show less" else "Show all", { all = !all }, Modifier.offset { IntOffset(-10.dp.roundToPx(), 0) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (call.status == LiveCallStatus.Transferring) "Your phone will ring." else "Emrys is taking a message.",
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
    Rule(color = p.line)
    Text(call.summary.ifBlank { "No summary." }, style = Type.quote, color = p.ink)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val outcome = if (call.takenOver) "You took it over" else CallText.outcome(call.outcome).removeSuffix(".")
        Row(Modifier.weight(1f)) {
            if (outcome.isNotBlank()) StatusTag(outcome, if (call.takenOver) Tone.Accent else Tone.Neutral, outline = !call.takenOver)
        }
        Spacer(Modifier.width(12.dp))
        Keycap("Dismiss", onDismiss)
    }
}

/** The call's glass card: a hairline that brightens while the call is live, and no fill of colour. */
@Composable
private fun Plate(live: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .clip(shape).background(p.hud).border(1.dp, if (live) p.lineHi else p.line, shape)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}
