package com.hiron.rivet.voice

import com.hiron.rivet.core.AlertBlock
import com.hiron.rivet.core.ApprovalRequest
import com.hiron.rivet.core.NoAllow
import com.hiron.rivet.core.RuleOffer
import com.hiron.rivet.core.RuleScope
import com.hiron.rivet.core.Tier
import org.json.JSONObject
import java.util.UUID

/**
 * What the phone's own approvals say (the ElevenLabs agents' phone tools). The copy is the brain's, byte for byte
 * (brain/src/rivet_brain/alerts.py `alert_for` and rules.py labels), so both paths read the same.
 */
object AgentAlerts {
    private const val HEADLINE_LIMIT = 80
    private const val NOT_CHECKED = "Rivet can't tell what this does, so it asks first."

    /** Tools whose calls can become standing allows on this path; the others can only be standing denies. */
    val ALLOWS = setOf("sms_send", "phone_call", "calendar_create")

    /** The approval this call needs before it runs, or null when the brain would run it without asking. */
    fun request(name: String, input: JSONObject, tainted: Boolean, lastAsked: String?): ApprovalRequest? {
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
    fun alert(name: String, input: JSONObject): Pair<String, List<AlertBlock>> {
        fun text(key: String): String = if (!input.has(key) || input.isNull(key)) "" else input.opt(key).toString()
        return when (name) {
            "sms_send" -> "Text ${text("to")}: “${clip(text("body"))}”" to
                listOf(AlertBlock.Field("To", text("to")), AlertBlock.Quote("Message", text("body")))
            "phone_call" -> "Call ${text("number")}" to
                listOf(AlertBlock.Field("Number", text("number")), AlertBlock.Note("Your phone places the call now. You do the talking."))
            "notification_reply" -> "Reply to a message: “${clip(text("text"))}”" to
                listOf(AlertBlock.Quote("Reply", text("text")), AlertBlock.Note("Sent from the app the message came from."))
            "calendar_create" -> "Add “${text("title")}” to your calendar" to listOfNotNull(
                AlertBlock.Field("When", text("start")),
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
