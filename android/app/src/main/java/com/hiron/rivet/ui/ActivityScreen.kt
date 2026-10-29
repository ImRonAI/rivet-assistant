package com.hiron.rivet.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hiron.rivet.core.LogEntry
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.device.Times
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * What Rivet did and what it has scheduled: today's counters, pending reminders, then the activity log as a
 * trace grouped by day (newest first).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(runtime: RivetRuntime, state: RivetState) {
    val p = LocalPalette.current
    var reminders by remember { mutableStateOf(JSONArray()) }
    LaunchedEffect(state.log.size) {
        reminders = (runtime.reminders.list().data as? JSONObject)?.optJSONArray("reminders") ?: JSONArray()
    }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val days = remember(state.log) { state.log.asReversed().groupBy { dayOf(it.at) } }
    val today = state.log.filter { dayOf(it.at) == dayOf(System.currentTimeMillis()) }
    val newest = state.log.lastOrNull()
    LazyColumn(Modifier.fillMaxSize().background(p.bg), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "stats") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CountTile(today.count { it.kind in setOf("device", "tool", "task", "turn") }, "Things done", p.cy, Modifier.weight(1f).enter(0))
                CountTile(today.count { it.kind == "approval" }, "Approvals", p.amb, Modifier.weight(1f).enter(1))
                CountTile(today.count { it.kind == "call" }, "Calls", p.accent, Modifier.weight(1f).enter(2))
            }
        }
        if (reminders.length() > 0) {
            item(key = "reminders") {
                Reminders(reminders, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).enter(3)) { id ->
                    runtime.reminders.cancel(id)
                    reminders = (runtime.reminders.list().data as? JSONObject)?.optJSONArray("reminders") ?: JSONArray()
                }
            }
        }
        item(key = "log") {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("EVERYTHING RIVET DID", style = Type.eyebrow, color = p.ink2, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(
                    if (state.log.size == 1) "1 EVENT" else "${state.log.size} EVENTS",
                    style = Type.eyebrow.copy(fontSize = 10.sp), color = p.ink2.copy(alpha = 0.6f), maxLines = 1,
                )
            }
        }
        if (state.log.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing yet. Ask Rivet for something and every step it takes shows up here.",
                    color = p.ink2, style = Type.body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
        days.forEach { (day, entries) ->
            stickyHeader(key = "day:$day") { DayHeader(day) }
            itemsIndexed(entries) { i, e ->
                TraceRow(
                    e, time.format(Date(e.at)), first = i == 0, last = i == entries.lastIndex,
                    live = e === newest && state.activeTurn != null,
                )
            }
        }
    }
}

/**
 * A counter tile: a tone bar and label over a big display numeral. The label wraps to two lines so three tiles
 * fit side by side on a narrow phone.
 */
@Composable
private fun CountTile(value: Int, label: String, color: Color, modifier: Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(p.card).border(1.dp, p.edge, shape).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 16.dp, height = 3.dp).glow(color.copy(alpha = 0.6f), 4.dp, RoundedCornerShape(2.dp)).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label.uppercase(), style = Type.labelSm.copy(letterSpacing = 0.06.em), color = p.ink2, maxLines = 2, minLines = 2)
        Odometer("$value", Type.stat, p.ink)
    }
}

/** Reminders Rivet has set, soonest first, each with a cancel key. */
@Composable
private fun Reminders(reminders: JSONArray, modifier: Modifier, onCancel: (String) -> Unit) {
    val p = LocalPalette.current
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val list = (0 until reminders.length()).map { reminders.getJSONObject(it) }
    RivetCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("REMINDERS", Modifier.weight(1f), tone = Tone.Amber)
            Text("${list.size} SET", style = Type.eyebrow, color = p.ink2, maxLines = 1)
        }
        list.forEach { r ->
            Spacer(Modifier.height(12.dp))
            Rule(color = p.line)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val due = Times.parse(r.optString("at"))
                    Text(
                        due?.let { android.text.format.DateFormat.format("EEE d MMM", Date(it)).toString() + " · " + time.format(Date(it)) } ?: r.optString("at"),
                        style = Type.body, color = p.ink2,
                    )
                    Text(r.optString("text"), style = Type.bodyLg, color = p.ink)
                }
                Keycap("Cancel", { onCancel(r.optString("id")) })
            }
        }
    }
}

private fun dayOf(at: Long): Long = Calendar.getInstance().apply {
    timeInMillis = at
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun DayHeader(day: Long) {
    val p = LocalPalette.current
    val locale = LocalConfiguration.current.locales[0]
    val today = dayOf(System.currentTimeMillis())
    val name = when (day) {
        today -> "Today"
        dayOf(today - 12 * 3_600_000L) -> "Yesterday"
        else -> android.text.format.DateFormat.format("EEEE", Date(day)).toString()
    }
    val date = android.text.format.DateFormat.format("d MMM", Date(day)).toString()
    Row(
        Modifier.fillMaxWidth().background(p.bg).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name.uppercase(locale), style = Type.step.copy(fontSize = 20.sp, lineHeight = 22.sp), color = p.ink, maxLines = 1)
        Spacer(Modifier.size(10.dp))
        Text(date.uppercase(locale), style = Type.labelMd, color = p.ink2, maxLines = 1)
        Spacer(Modifier.size(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(p.line))
    }
}

/** One step of the trace: time, a ring on the spine in the kind's tone, the kind tag and what happened. */
@Composable
private fun TraceRow(e: LogEntry, at: String, first: Boolean, last: Boolean, live: Boolean) {
    val p = LocalPalette.current
    val (word, tone) = Plain.kind(e.kind)
    val color = tone.color()
    val nodeY = 19.dp
    val pulseAlpha = pulse(live, low = 0.4f, periodMs = 1200)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 20.dp)) {
        Text(
            at, style = Type.labelSm.copy(letterSpacing = Type.labelSm.letterSpacing * 0.3f), color = p.ink2, maxLines = 1,
            modifier = Modifier.width(56.dp).padding(top = 13.dp),
        )
        Box(
            Modifier.width(20.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val top = if (first) nodeY.toPx() else 0f
                val bottom = if (last) nodeY.toPx() else size.height
                if (bottom > top) drawLine(p.line, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
            },
        ) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = nodeY - 4.5.dp).size(9.dp).alpha(pulseAlpha)
                    .then(if (live) Modifier.glow(color.copy(alpha = 0.7f), 6.dp) else Modifier)
                    .clip(CircleShape).background(if (live) color else p.bg).border(1.5.dp, color, CircleShape),
            )
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            StatusTag(word, tone)
            Text(Plain.log(e.kind, e.text), style = Type.body, color = p.ink)
        }
    }
}
