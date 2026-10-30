package com.hiron.emrys.ui

import android.telephony.PhoneNumberUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector
import com.hiron.emrys.core.Tier
import java.util.Locale

/** Plain words for tool ids, approval tiers, log kinds and phone numbers, so nothing on screen reads like a system code. */
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
        "turn" to ("You asked" to Tone.Accent), "search" to ("Search" to Tone.Cyan),
        "device" to ("Phone" to Tone.Cyan), "approval" to ("Approval" to Tone.Amber), "backoff" to ("Trying again" to Tone.Amber),
        "restart" to ("Restart" to Tone.Amber), "notice" to ("Note" to Tone.Neutral), "error" to ("Problem" to Tone.Red),
        "call" to ("Call" to Tone.Green), "task" to ("Task" to Tone.Accent),
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

    /** The international numbers in [text]. */
    fun numbersIn(text: String): List<String> = NUMBER.findAll(text).map { it.value }.distinct().toList()

    /**
     * [text] with each international number replaced by its contact's name from [names] ("Dana"), or with the name
     * and the grouped number when [withNumber] ("Dana · +1 801-555-0100"); numbers without a name are grouped.
     * Everything from the first “ on is a quote of what will be sent and is kept exactly as written.
     */
    fun people(text: String, names: Map<String, String>, withNumber: Boolean = false): String {
        val quote = text.indexOf('“')
        val head = if (quote < 0) text else text.substring(0, quote)
        return NUMBER.replace(head) { m ->
            val name = names[m.value] ?: return@replace phone(m.value)
            if (withNumber) "$name · ${phone(m.value)}" else name
        } + text.substring(head.length)
    }

    /** One line on what kind of thing the action does and whether it can be taken back. */
    fun risk(tier: Tier, tool: String): String = when (tier) {
        Tier.Read -> "Only looks · changes nothing"
        Tier.Send -> if (tool == "phone_call") "Places a call · you can't undo it" else "Sends a message · you can't undo it"
        Tier.Write -> "Changes something on your phone or account"
        Tier.Destructive -> "Deletes something · you can't undo it"
        Tier.Purchase -> "Spends money · you can't undo it"
        Tier.Unknown -> "Something Emrys hasn't done before"
    }

    fun toneFor(tier: Tier): Tone = when (tier) {
        Tier.Read -> Tone.Cyan
        Tier.Send, Tier.Write -> Tone.Amber
        Tier.Destructive, Tier.Purchase, Tier.Unknown -> Tone.Red
    }

    /** The approval sheet's line for a tool: "Send a text". */
    fun action(tool: String): String = TOOLS[tool]?.first ?: spoken(tool)

    /** The activity log's line for a tool: "Sent a text". */
    fun done(tool: String): String = TOOLS[tool]?.second ?: spoken(tool)

    /**
     * A log entry's text in plain words; anything that isn't a tool id or a retry passes through unchanged.
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
        if (kind == "backoff") BACKOFF.matchEntire(text)?.let { return "Emrys's model is busy. Trying again in ${it.groupValues[1]}s." }
        return numbers(text)
    }

    /** A log kind as a short word in sentence case, and its tone. */
    fun kind(kind: String): Pair<String, Tone> = KINDS[kind] ?: (spoken(kind) to Tone.Neutral)

    private fun spoken(id: String): String = id.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** The icon for a tool, on approval cards and rule rows. */
internal fun iconFor(tool: String): ImageVector = when {
    tool.startsWith("sms_") || tool == "notification_reply" -> Icons.AutoMirrored.Outlined.Chat
    tool.startsWith("email_") -> Icons.Outlined.MailOutline
    tool == "phone_call" || tool == "calls_recent" -> Icons.Outlined.Call
    tool.startsWith("calendar_") -> Icons.Outlined.Event
    tool.startsWith("reminder") -> Icons.Outlined.Alarm
    tool.startsWith("contacts_") -> Icons.Outlined.Person
    tool.startsWith("screen_") -> Icons.Outlined.TouchApp
    tool.startsWith("app") -> Icons.Outlined.Apps
    tool.startsWith("notifications_") -> Icons.Outlined.Notifications
    tool.startsWith("telnyx_") || tool.startsWith("call_") -> Icons.Outlined.Dialpad
    tool == "device_status" -> Icons.Outlined.PhoneAndroid
    else -> Icons.Outlined.Bolt
}
