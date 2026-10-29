package com.hiron.rivet.voice

import com.hiron.rivet.core.AlertBlock
import com.hiron.rivet.core.ApprovalRequest
import com.hiron.rivet.core.NoAllow
import com.hiron.rivet.core.RuleOffer
import com.hiron.rivet.core.RuleScope
import com.hiron.rivet.core.Tier
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What the phone's own approvals say (the ElevenLabs agents' phone tools). The copy is the brain's, byte for byte
 * (brain/src/rivet_brain/alerts.py `alert_for` and rules.py labels), so both paths read the same. Event times are
 * the one exception: [eventTime] reads the ISO-8601 forms java.time's ISO parsers take, a subset of what the brain's
 * `datetime.fromisoformat` takes, and shows any other text as written.
 */
object AgentAlerts {
    private const val HEADLINE_LIMIT = 80
    private const val NOT_CHECKED = "Rivet can't tell what this does, so it asks first."
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}""")

    /** Tools whose calls can become standing allows on this path; the others can only be standing denies. */
    val ALLOWS = setOf("sms_send", "phone_call", "calendar_create")

    /** The approval this call needs before it runs, or null when the brain would run it without asking. */
    internal fun request(name: String, input: JSONObject, tainted: Boolean, lastAsked: String?): ApprovalRequest? {
        val tier = AgentTools.tier(name, input)
        if (tier == Tier.Read) return null
        val (headline, blocks) = alert(name, input)
        return ApprovalRequest(
            id = "agent:" + UUID.randomUUID().toString(),
            lane = AgentTools.LANE,
            turnId = "",
            tool = name,
            tier = tier,
            headline = headline,
            blocks = blocks,
            because = lastAsked?.takeIf { it.isNotBlank() }?.let { "You asked: “${clip(it.trim())}”" },
            preview = input.toString(),
            untrustedTurn = tainted,
            operations = emptyList(),
            offer = offer(name, input, tier, tainted),
            expiresAt = (System.currentTimeMillis() + AgentTools.APPROVAL_TIMEOUT_MS) / 1000.0,
        )
    }

    /** The headline and blocks for one call. */
    internal fun alert(name: String, input: JSONObject): Pair<String, List<AlertBlock>> {
        // Python's str(): booleans read True and False.
        fun text(key: String): String = when (val v = if (input.isNull(key)) null else input.opt(key)) {
            null -> ""
            is Boolean -> if (v) "True" else "False"
            else -> v.toString()
        }
        return when (name) {
            "sms_send" -> "Text ${text("to")}: “${clip(text("body"))}”" to
                listOf(AlertBlock.Field("To", text("to")), AlertBlock.Quote("Message", text("body")))
            "phone_call" -> "Call ${text("number")}" to
                listOf(AlertBlock.Field("Number", text("number")), AlertBlock.Note("Your phone places the call now. You do the talking."))
            "notification_reply" -> "Reply to a message: “${clip(text("text"))}”" to
                listOf(AlertBlock.Quote("Reply", text("text")), AlertBlock.Note("Sent from the app the message came from."))
            "calendar_create" -> "Add “${text("title")}” to your calendar" to listOfNotNull(
                AlertBlock.Field("When", eventTime(text("start"), text("end"))),
                text("location").takeIf { it.isNotEmpty() }?.let { AlertBlock.Field("Where", it) },
                text("description").takeIf { it.isNotEmpty() }?.let { AlertBlock.Quote("Notes", it) },
            )
            "screen_act" -> when {
                text("action") != "tap" -> "Do something Rivet doesn't recognise in the app on screen" to listOf(AlertBlock.Note(NOT_CHECKED))
                text("label").isBlank() -> "Tap something without a label in the app on screen" to listOf(AlertBlock.Note(NOT_CHECKED))
                else -> "Tap “${text("label")}” in the app on screen" to listOf(AlertBlock.Note("Rivet checks the button still says this before it taps."))
            }
            else -> "Use a tool Rivet doesn't know: $name" to listOf(AlertBlock.Note("Rivet has no rule for this tool, so it asks first."))
        }
    }

    /** The rule label for a target's normalized scope (rules.py and device_tools.py `Standing` labels). */
    fun label(tool: String, scope: Map<String, String>): String? = when (tool) {
        "sms_send" -> scope["to"]?.let { "Texts to $it" }
        "phone_call" -> scope["number"]?.let { "Calls to $it" }
        "calendar_create" -> "Adding events to your calendar"
        "screen_act" -> scope["label"]?.let { "Tapping “$it” in apps" }
        else -> null
    }

    /** alerts.py `when`: "Tue Oct 27, 9:00 AM to 10:00 AM" in fixed English; [start] as written when it isn't ISO-8601. */
    fun eventTime(start: String, end: String = ""): String {
        val begins = moment(start) ?: return start
        val text = "${day(begins)}, ${clock(begins)}"
        val ends = (if (end.isEmpty()) null else moment(end)) ?: return text
        if (ends.toLocalDate() == begins.toLocalDate()) return "$text to ${clock(ends)}"
        return "$text to ${day(ends)}, ${clock(ends)}"
    }

    /** The wall-clock time as written (an offset is kept, not converted), as Python's `datetime.fromisoformat` reads it. */
    private fun moment(value: String): LocalDateTime? {
        if (!ISO_DATE.containsMatchIn(value)) return null
        return runCatching { OffsetDateTime.parse(value).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value) }.getOrNull()
            ?: runCatching { LocalDate.parse(value).atStartOfDay() }.getOrNull()
    }

    private fun day(moment: LocalDateTime): String = "${DAYS[moment.dayOfWeek.value - 1]} ${MONTHS[moment.monthValue - 1]} ${moment.dayOfMonth}"

    private fun clock(moment: LocalDateTime): String {
        val hour = (moment.hour % 12).takeIf { it != 0 } ?: 12
        return "$hour:${moment.minute.toString().padStart(2, '0')} ${if (moment.hour < 12) "AM" else "PM"}"
    }

    /** `text` for a headline: on one line, cut at a space and ended with an ellipsis past 80 characters. */
    fun clip(text: String): String {
        val line = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        if (line.length <= HEADLINE_LIMIT) return line
        val cut = line.lastIndexOf(' ', HEADLINE_LIMIT)
        return (if (cut > 0) line.substring(0, cut) else line.take(HEADLINE_LIMIT)) + "…"
    }

    private fun offer(name: String, input: JSONObject, tier: Tier, tainted: Boolean): RuleOffer? {
        val target = RuleScope.target(name, input) ?: return null
        val label = label(name, target.scope) ?: return null
        val noAllow = when {
            tier == Tier.Purchase -> NoAllow.Purchase
            tier == Tier.Destructive -> NoAllow.Destructive
            tier == Tier.Unknown -> NoAllow.Unknown
            name !in ALLOWS -> NoAllow.Kind
            tainted -> NoAllow.Untrusted
            else -> null
        }
        return RuleOffer(target, label, allow = noAllow == null, deny = true, noAllow = noAllow)
    }
}

/**
 * Whether the current ElevenLabs conversation turn has read third-party content (texts, calendar, notifications,
 * screens) since the user last spoke; standing allows don't apply until they speak again. Thread-safe.
 */
class AgentTaint {
    @Volatile
    var tainted: Boolean = false
        private set

    @Volatile
    var lastAsked: String? = null
        private set

    fun mark() {
        tainted = true
    }

    /** The user said or typed [text]: a new turn. */
    fun asked(text: String) {
        lastAsked = text
        tainted = false
    }

    /** A new conversation. */
    fun reset() {
        lastAsked = null
        tainted = false
    }
}
