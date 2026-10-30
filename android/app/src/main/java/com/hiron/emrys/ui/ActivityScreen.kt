package com.hiron.emrys.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.LogEntry
import com.hiron.emrys.device.Times
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** The trace's serif heading. */
private val TraceTitle = Type.step.copy(fontSize = 28.sp, lineHeight = 32.sp)

/** Times in the trace: small Geist Mono figures. */
private val TraceTime = Type.mono.copy(fontSize = 11.sp, lineHeight = 14.sp)

/** The trace's first line (a kind's label and its time) starts this far down its row. */
private val TraceTop = 10.dp

/** What happened, in the trace. */
private val TraceText = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp)

/**
 * What Emrys did and what it has scheduled: pending reminders, then the activity log as a trace grouped by day
 * (newest first), each day under an eyebrow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(runtime: EmrysRuntime, state: EmrysState) {
    val p = LocalPalette.current
    val gutter = gutter()
    var reminders by remember { mutableStateOf(JSONArray()) }
    LaunchedEffect(state.log.size) {
        reminders = (runtime.reminders.list().data as? JSONObject)?.optJSONArray("reminders") ?: JSONArray()
    }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val days = remember(state.log) { state.log.asReversed().groupBy { dayOf(it.at) } }
    val newest = state.log.lastOrNull()
    LazyColumn(Modifier.fillMaxSize().background(p.bg), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (reminders.length() > 0) {
            item(key = "reminders") {
                Reminders(reminders, Modifier.padding(start = gutter, end = gutter, top = 24.dp).enter(0)) { id ->
                    runtime.reminders.cancel(id)
                    reminders = (runtime.reminders.list().data as? JSONObject)?.optJSONArray("reminders") ?: JSONArray()
                }
            }
        }
        item(key = "log") {
            Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = 36.dp)) {
                Text(
                    "Everything Emrys did", style = TraceTitle, color = p.ink,
                    modifier = Modifier.weight(1f).alignByBaseline().semantics { heading() },
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (state.log.size == 1) "1 event" else "${state.log.size} events",
                    style = Type.labelMd.copy(fontFeatureSettings = "tnum"), color = p.ink2, maxLines = 1, modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (state.log.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing yet. Ask Emrys for something and every step it takes shows up here.",
                    color = p.ink2, style = Type.bodyLg, modifier = Modifier.padding(horizontal = gutter, vertical = 16.dp),
                )
            }
        }
        days.forEach { (day, entries) ->
            stickyHeader(key = "day:$day") { DayHeader(day, gutter) }
            itemsIndexed(entries) { i, e ->
                TraceRow(
                    e, time.format(Date(e.at)), first = i == 0, last = i == entries.lastIndex,
                    live = e === newest && state.activeTurn != null, gutter = gutter,
                )
            }
        }
    }
}

/** Screen gutters: 20dp, and 16dp on phones under 360dp wide. */
@Composable
private fun gutter(): Dp = if (with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < 360.dp) 16.dp else 20.dp

/** Reminders Emrys has set, soonest first, each with a cancel key. */
@Composable
private fun Reminders(reminders: JSONArray, modifier: Modifier, onCancel: (String) -> Unit) {
    val p = LocalPalette.current
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val list = (0 until reminders.length()).map { reminders.getJSONObject(it) }
    EmrysCard(modifier, padding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Reminders", Modifier.weight(1f), tone = Tone.Amber)
            Text("${list.size} set", style = Type.labelMd.copy(fontFeatureSettings = "tnum"), color = p.ink2, maxLines = 1)
        }
        list.forEach { r ->
            Rule(Modifier.padding(horizontal = 18.dp), color = p.line)
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val due = Times.parse(r.optString("at"))
                    Text(
                        due?.let { android.text.format.DateFormat.format("EEE d MMM", Date(it)).toString() + " · " + time.format(Date(it)) } ?: r.optString("at"),
                        style = TraceTime, color = p.ink2,
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

/** A day of the trace: its name and date as an eyebrow, then a hairline to the edge. It sticks while its day scrolls. */
@Composable
private fun DayHeader(day: Long, gutter: Dp) {
    val p = LocalPalette.current
    val today = dayOf(System.currentTimeMillis())
    val name = when (day) {
        today -> "Today"
        dayOf(today - 12 * 3_600_000L) -> "Yesterday"
        else -> android.text.format.DateFormat.format("EEEE", Date(day)).toString()
    }
    val date = android.text.format.DateFormat.format("d MMM", Date(day)).toString()
    Row(
        Modifier.fillMaxWidth().background(p.bg).padding(start = gutter, end = gutter, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow("$name · $date", tone = Tone.Neutral, dot = false)
        Spacer(Modifier.width(14.dp))
        Rule(Modifier.weight(1f), color = p.line)
    }
}

/** A log kind's label in sentence case. */
private fun kindLabel(word: String, locale: Locale): String = word.lowercase(locale).replaceFirstChar { it.titlecase(locale) }

/**
 * One step of the trace: the time in small mono figures, a dot in the kind's tone on a hairline spine (breathing
 * while Emrys is still on it), the kind's label and what happened.
 */
@Composable
private fun TraceRow(e: LogEntry, at: String, first: Boolean, last: Boolean, live: Boolean, gutter: Dp) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val locale = LocalConfiguration.current.locales[0]
    val (word, tone) = Plain.kind(e.kind)
    val color = tone.color()
    // The node sits on the middle of the first line, and the time column grows with the text size.
    val nodeY = TraceTop + with(density) { (TraceTime.lineHeight / 2).toDp() }
    val timeWidth = (58f * density.fontScale).dp
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = gutter)) {
        Text(at, style = TraceTime, color = p.ink2, maxLines = 1, modifier = Modifier.width(timeWidth).padding(top = TraceTop))
        Box(
            Modifier.width(20.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val top = if (first) nodeY.toPx() else 0f
                val bottom = if (last) nodeY.toPx() else size.height
                if (bottom > top) drawLine(p.line, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
            },
        ) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = nodeY - 3.5.dp)) { SignalDot(color, live = live, size = 7.dp) }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, top = TraceTop, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(kindLabel(word, locale), style = Type.labelMd.copy(lineHeight = TraceTime.lineHeight), color = if (live) color else p.ink2, maxLines = 1)
            Text(Plain.log(e.kind, e.text), style = TraceText, color = p.ink)
        }
    }
}
