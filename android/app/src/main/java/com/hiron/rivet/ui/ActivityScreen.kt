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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.LogEntry
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    LazyColumn(Modifier.fillMaxSize().background(p.bg), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "stats") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(today.count { it.kind in setOf("device", "tool", "task", "turn") }, "Steps", p.cy, Modifier.weight(1f))
                StatTile(today.count { it.kind == "approval" }, "Approvals", p.amb, Modifier.weight(1f))
                StatTile(today.count { it.kind == "call" }, "Calls", p.accent, Modifier.weight(1f))
            }
        }
        if (reminders.length() > 0) {
            item(key = "reminders") {
                HudCard("Reminders", Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), pip = p.amb, meta = "${reminders.length()} set") {
                    (0 until reminders.length()).map { reminders.getJSONObject(it) }.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.optString("text"), style = Type.bodyLg, color = p.ink)
                                Text(r.optString("at"), style = Type.mono, color = p.ink2)
                            }
                            Keycap("Cancel", {
                                runtime.reminders.cancel(r.optString("id"))
                                reminders = (runtime.reminders.list().data as? JSONObject)?.optJSONArray("reminders") ?: JSONArray()
                            })
                        }
                    }
                }
            }
        }
        item(key = "log") {
            Spacer(Modifier.height(12.dp))
            HudHeaderRow(
                "Activity log",
                pip = if (state.activeTurn != null) p.cy else p.ink2,
                live = state.activeTurn != null,
                meta = "${state.log.size} events",
            )
        }
        if (state.log.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing yet. Ask Rivet for something and every step it takes shows up here.",
                    color = p.ink2, style = Type.body, modifier = Modifier.padding(16.dp),
                )
            }
        }
        days.forEach { (day, entries) ->
            stickyHeader(key = "day:$day") { DayHeader(day) }
            itemsIndexed(entries) { i, e -> TraceRow(e, time.format(Date(e.at)), first = i == 0, last = i == entries.lastIndex) }
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
    val today = dayOf(System.currentTimeMillis())
    val name = when (day) {
        today -> "Today"
        dayOf(today - 12 * 3_600_000L) -> "Yesterday"
        else -> android.text.format.DateFormat.format("EEEE", Date(day)).toString()
    }
    val date = android.text.format.DateFormat.format("d MMM", Date(day)).toString()
    Row(Modifier.fillMaxWidth().background(p.bg).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name.uppercase(Locale.getDefault()), style = Type.labelMd, color = p.ink)
        Spacer(Modifier.size(8.dp))
        Text(date.uppercase(Locale.getDefault()), style = Type.labelMd, color = p.ink2)
        Spacer(Modifier.size(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(p.rule))
    }
}

private fun kindColor(kind: String, p: Palette): Color = when (kind) {
    "error" -> p.rec
    "approval", "restart", "backoff" -> p.amb
    "device", "tool" -> p.cy
    "task" -> p.ok
    "call" -> p.accent
    else -> p.ink2
}

/** One step of the trace: time, a node on the spine, the kind plate and what happened. */
@Composable
private fun TraceRow(e: LogEntry, at: String, first: Boolean, last: Boolean) {
    val p = LocalPalette.current
    val color = kindColor(e.kind, p)
    val nodeY = 19.dp
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        Text(at, style = Type.labelSm.copy(letterSpacing = Type.labelSm.letterSpacing * 0.3f), color = p.ink2, maxLines = 1, modifier = Modifier.width(58.dp).padding(top = 14.dp))
        Box(
            Modifier.width(22.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val top = if (first) nodeY.toPx() else 0f
                val bottom = if (last) nodeY.toPx() else size.height
                if (bottom > top) drawLine(p.hudLine, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx())
                drawCircle(color.copy(alpha = 0.18f), 10.dp.toPx(), Offset(x, nodeY.toPx()))
            },
        ) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = nodeY - 6.dp).size(12.dp).clip(CircleShape)
                    .background(p.hud).border(2.dp, color, CircleShape),
            )
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 9.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Tag(e.kind, color)
            Text(e.text, style = Type.body, color = p.ink)
        }
    }
}
