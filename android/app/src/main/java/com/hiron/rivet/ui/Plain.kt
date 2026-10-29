package com.hiron.rivet.ui

import android.telephony.PhoneNumberUtils
import java.util.Locale

/** Plain words for tool ids, approval tiers, log kinds and phone numbers, so nothing on screen reads like a system code. */
object Plain {
    /** Tool id to (what Rivet wants to do, what Rivet did). */
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
        "call_answering_number" to ("Look up the answering number" to "Looked up the answering number"),
        "screen_read" to ("Read the screen" to "Read the screen"),
        "screen_act" to ("Tap or type on the screen" to "Tapped or typed on the screen"),
        "email_search" to ("Search your email" to "Searched email"),
        "email_read" to ("Read an email" to "Read an email"),
        "email_send" to ("Send an email" to "Sent an email"),
        "email_reply" to ("Reply to an email" to "Replied to an email"),
        "email_mark" to ("File an email" to "Filed an email"),
        "telnyx_execute" to ("Use your phone service" to "Used the phone service"),
    )

    private val KINDS = mapOf(
        "turn" to ("YOU ASKED" to Tone.Accent), "search" to ("SEARCH" to Tone.Cyan), "tool" to ("DID" to Tone.Cyan),
        "device" to ("PHONE" to Tone.Cyan), "approval" to ("APPROVAL" to Tone.Amber), "backoff" to ("TRYING AGAIN" to Tone.Amber),
        "restart" to ("RESTART" to Tone.Amber), "notice" to ("NOTE" to Tone.Neutral), "error" to ("PROBLEM" to Tone.Red),
        "call" to ("CALL" to Tone.Green), "task" to ("TASK" to Tone.Accent),
    )

    private val TOOL_LINE = Regex("""^([a-z_]+)(?::\s*(.*))?$""", RegexOption.DOT_MATCHES_ALL)
    private val BACKOFF = Regex("""^Model busy; retrying in (\d+)s$""")
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

    /** One line on what kind of thing the action does and whether it can be taken back. */
    fun risk(tier: String, tool: String): String = when (tier) {
        "read" -> "Only looks · changes nothing"
        "send" -> if (tool == "phone_call") "Places a call · you can't undo it" else "Sends a message · you can't undo it"
        "write" -> "Changes something on your phone or account"
        "destructive" -> "Deletes something · you can't undo it"
        "purchase" -> "Spends money · you can't undo it"
        else -> "Something Rivet hasn't done before"
    }

    fun toneFor(tier: String): Tone = when (tier) {
        "read" -> Tone.Cyan
        "send", "write" -> Tone.Amber
        else -> Tone.Red
    }

    /** The approval sheet's line for a tool: "Send a text". */
    fun action(tool: String): String = TOOLS[tool]?.first ?: spoken(tool)

    /** The activity log's line for a tool: "Sent a text". */
    fun done(tool: String): String = TOOLS[tool]?.second ?: spoken(tool)

    /** A log entry's text in plain words; anything that isn't a tool id or a retry passes through unchanged. */
    fun log(kind: String, text: String): String {
        TOOL_LINE.matchEntire(text)?.let { m ->
            val id = m.groupValues[1]
            if (id in TOOLS) {
                // "done" repeats what the past tense already says.
                val rest = m.groupValues[2].trim().takeUnless { it.isEmpty() || it == "done" }
                return done(id) + (rest?.let { " · ${numbers(it)}" } ?: "")
            }
        }
        if (kind == "backoff") BACKOFF.matchEntire(text)?.let { return "Rivet's model is busy. Trying again in ${it.groupValues[1]}s." }
        return numbers(text)
    }

    /** A log kind as a tag word and its tone. */
    fun kind(kind: String): Pair<String, Tone> = KINDS[kind] ?: (kind.uppercase() to Tone.Neutral)

    private fun spoken(id: String): String = id.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
