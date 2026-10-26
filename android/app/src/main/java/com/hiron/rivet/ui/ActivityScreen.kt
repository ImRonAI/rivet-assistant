package com.hiron.rivet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.LogEntry
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** What Rivet did and what it has scheduled: the activity log (newest first) and pending reminders. */
@Composable
fun ActivityScreen(runtime: RivetRuntime, state: RivetState) {
    val p = LocalPalette.current
    var reminders by remember { mutableStateOf(JSONArray()) }
    LaunchedEffect(state.log.size) {
        reminders = (runtime.reminders.list().data as? JSONObject)?.optJSONArray("reminders") ?: JSONArray()
    }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize().background(p.bg).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Spacer(Modifier.size(16.dp))
            Text("ACTIVITY", style = Label, color = p.accent)
            Text("What Rivet is doing", style = MaterialTheme.typography.headlineSmall, color = p.ink)
            Spacer(Modifier.size(12.dp))
        }
        if (reminders.length() > 0) {
            item { Text("REMINDERS", style = Label, color = p.ink2, modifier = Modifier.padding(vertical = 6.dp)) }
            items((0 until reminders.length()).map { reminders.getJSONObject(it) }, key = { "r:" + it.optString("id") }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.optString("text"), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                        Text(r.optString("at"), style = MaterialTheme.typography.labelSmall, color = p.ink2)
                    }
                    TextButton(onClick = {
                        runtime.reminders.cancel(r.optString("id"))
                        reminders = (runtime.reminders.list().data as? JSONObject)?.optJSONArray("reminders") ?: JSONArray()
                    }) { Text("Cancel") }
                }
            }
            item { HorizontalDivider(color = p.line, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        if (state.log.isEmpty()) {
            item { Text("Nothing yet. Connect in Settings and ask Rivet for something.", color = p.ink2, style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.log.asReversed()) { e -> LogRow(e, time.format(Date(e.at))) }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun LogRow(e: LogEntry, at: String) {
    val p = LocalPalette.current
    val color = when (e.kind) {
        "error" -> p.rec
        "approval" -> p.accent
        "restart", "backoff" -> p.amb
        "device", "tool" -> p.cy
        "task" -> p.ok
        else -> p.ink2
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(at, style = MaterialTheme.typography.labelSmall, color = p.ink2, modifier = Modifier.width(64.dp))
        Text(
            e.kind.uppercase(), style = Label, color = Color.White,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(e.text, style = MaterialTheme.typography.bodySmall, color = p.ink, modifier = Modifier.weight(1f))
    }
}
