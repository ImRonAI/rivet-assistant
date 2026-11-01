package com.hiron.emrys.ui

import android.telephony.PhoneNumberUtils
import java.util.Locale

/** Plain words for tool ids, log kinds and phone numbers, so nothing on screen reads like a system code. */
object Plain {
    /** Tool id to (what Emrys wants to do, what Emrys did). */
    private val TOOLS = mapOf(
        "sms_send" to ("Send a text" to "Sent a text"),
        "sms_list" to ("Read your texts" to "Read texts"),
        "contacts_search" to ("Look up a contact" to "Looked up a contact"),
        "calls_recent" to ("Check recent calls" to "Checked recent calls"),
        "phone_call" to ("Place a call" to "Placed a call"),
        "calendar_list" to ("Check your calendar" to "Checked the calendar"),
        "calendar_create" to ("Add a calendar event" to "Added a calendar event"),
        "notifications_list" to ("Read your notifications" to "Read notifications"),
        "notification_reply" to ("Reply to a notification" to "Replied to a notification"),
        "reminder_create" to ("Set a reminder" to "Set a reminder"),
        "reminders_list" to ("Check your reminders" to "Checked reminders"),
        "reminder_cancel" to ("Cancel a reminder" to "Cancelled a reminder"),
        "email_draft" to ("Draft an email" to "Drafted an email"),
        "apps_list" to ("List your apps" to "Listed apps"),
        "app_open" to ("Open an app" to "Opened an app"),
        "device_status" to ("Check the phone" to "Checked the phone"),
        "screen_read" to ("Read the screen" to "Read the screen"),
        "screen_act" to ("Tap or type on the screen" to "Tapped or typed on the screen"),
    )

    private val KINDS = mapOf(
        "device" to ("Phone" to Tone.Cyan), "approval" to ("Approval" to Tone.Amber), "error" to ("Problem" to Tone.Red),
        "call" to ("Call" to Tone.Green),
    )

    private val TOOL_LINE = Regex("""^([a-z_]+)(?::\s*(.*))?$""", RegexOption.DOT_MATCHES_ALL)
    private val NUMBER = Regex("""\+\d{8,15}\b""")
    private val BARE_NUMBER = Regex("""^\+?\d{7,15}$""")
    private val NANP = Regex("""^\+1(\d{3})(\d{3})(\d{4})$""")

    /** A phone number grouped the way people write it ("+1 801-555-0123"); anything else is returned as-is. */
    fun phone(value: String): String {
        val v = value.trim()
        if (!BARE_NUMBER.matches(v)) return value
        NANP.matchEntire(v)?.let { val (area, office, line) = it.destructured; return "+1 $area-$office-$line" }
        return runCatching { PhoneNumberUtils.formatNumber(v, Locale.getDefault().country) }.getOrNull() ?: value
    }

    /** [text] with every international number in it grouped by [phone]. */
    fun numbers(text: String): String = NUMBER.replace(text) { phone(it.value) }

    /** What Emrys tried with a tool: "Send a text". */
    fun action(tool: String): String = TOOLS[tool]?.first ?: spoken(tool)

    /** The activity log's line for a tool: "Sent a text". */
    fun done(tool: String): String = TOOLS[tool]?.second ?: spoken(tool)

    /**
     * A log entry's text in plain words; anything that isn't a tool id passes through unchanged.
     * `sms_send` and `sms_send: done` read "Sent a text"; any other result reads after what Emrys tried ("Send a
     * text · …"), so a failure never reads as done.
     */
    fun log(kind: String, text: String): String {
        TOOL_LINE.matchEntire(text)?.let { m ->
            val id = m.groupValues[1]
            if (id in TOOLS) {
                val rest = m.groupValues[2].trim()
                return if (rest.isEmpty() || rest == "done") done(id) else "${action(id)} · ${numbers(rest)}"
            }
        }
        return numbers(text)
    }

    /** A log kind as a short word in sentence case, and its tone. */
    fun kind(kind: String): Pair<String, Tone> = KINDS[kind] ?: (spoken(kind) to Tone.Neutral)

    private fun spoken(id: String): String = id.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
