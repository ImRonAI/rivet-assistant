package com.hiron.emrys.device

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** The phone's calendars through [CalendarContract]. */
class CalendarTool(private val context: Context) {

    suspend fun list(days: Int, query: String?): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis() - HOUR
        val end = start + days.coerceIn(1, 90) * DAY
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val rows = JSONArray()
        val needle = query?.lowercase()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && rows.length() < 100) {
                val title = c.getString(1).orEmpty()
                val location = c.getString(4).orEmpty()
                if (needle != null && !title.lowercase().contains(needle) && !location.lowercase().contains(needle)) continue
                rows.put(
                    JSONObject()
                        .put("event_id", c.getLong(0))
                        .put("title", title)
                        .put("start", Times.format(c.getLong(2)))
                        .put("end", Times.format(c.getLong(3)))
                        .put("location", location)
                        .put("all_day", c.getInt(5) == 1)
                        .put("calendar", c.getString(6).orEmpty()),
                )
            }
        }
        ToolResult.ok(JSONObject().put("events", rows))
    }

    suspend fun create(title: String, start: String, end: String?, location: String?, description: String?): ToolResult =
        withContext(Dispatchers.IO) {
            val begin = Times.parse(start) ?: return@withContext ToolResult.error("Can't read start time \"$start\"; use ISO-8601.")
            val finish = end?.let { Times.parse(it) ?: return@withContext ToolResult.error("Can't read end time \"$end\".") }
                ?: (begin + HOUR)
            if (finish <= begin) return@withContext ToolResult.error("The event ends before it starts.")
            val calendarId = writableCalendar()
                ?: return@withContext ToolResult.error("No writable calendar on this phone. Add a Google account calendar first.")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, finish)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@withContext ToolResult.error("The calendar refused the event.")
            ToolResult.ok(
                JSONObject()
                    .put("event_id", ContentUris.parseId(inserted))
                    .put("title", title)
                    .put("start", Times.format(begin))
                    .put("end", Times.format(finish)),
            )
        }

    private fun writableCalendar(): Long? = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
        "${CalendarContract.Calendars.IS_PRIMARY} DESC",
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
    }
}
