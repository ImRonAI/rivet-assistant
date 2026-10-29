package com.hiron.rivet.core

import android.Manifest
import android.os.Build
import com.hiron.rivet.device.ForwardMode
import java.net.URLDecoder
import java.net.URLEncoder

/** One group in Settings › What Rivet can use: its name and the Android permissions behind it. */
data class PermissionGroup(val name: String, val why: String, val permissions: List<String>)

object PermissionGroups {
    /** The runtime permissions Rivet asks for, in the order Settings lists them. */
    val ALL: List<PermissionGroup> = buildList {
        add(PermissionGroup("Texts", "Send and read SMS", listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)))
        add(PermissionGroup("Contacts", "Find people by name", listOf(Manifest.permission.READ_CONTACTS)))
        add(PermissionGroup("Calendar", "Read and add events", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)))
        add(PermissionGroup("Phone", "Place calls and read recent calls", listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)))
        add(PermissionGroup("Microphone", "Talk to Rivet out loud", listOf(Manifest.permission.RECORD_AUDIO)))
        if (Build.VERSION.SDK_INT >= 33) {
            add(PermissionGroup("Notifications", "Show approvals and reminders", listOf(Manifest.permission.POST_NOTIFICATIONS)))
        }
    }
}

enum class RecId { AutoConnect, QuietOverlay, OfferReplies, UnansweredCalls, Permissions, Assistant, StopAsking }

/** The settings a [Recommendation.Setting] changes. */
enum class SettingId(val rec: RecId) {
    AutoConnect(RecId.AutoConnect),
    QuietOverlay(RecId.QuietOverlay),
    OfferReplies(RecId.OfferReplies),
    UnansweredCalls(RecId.UnansweredCalls),
}

/** What the recommendations are compared against. */
data class Current(
    val autoConnect: Boolean,
    val speakInOverlay: Boolean,
    val offerReplies: Boolean,
    val answeringMode: ForwardMode,
    /** The carrier was last told to forward every call to Rivet. */
    val forwardingAllCalls: Boolean,
    val missingPermissions: List<PermissionGroup>,
    val assistantHeld: Boolean,
    val denyCandidates: List<DenyCandidate>,
)

/** One change Rivet suggests: what it is, what it changes, and how it is undone. */
sealed interface Recommendation {
    val id: RecId
    val title: String
    val change: String
    val undo: String

    /** A setting Rivet changes itself; [now] and [after] are its readouts. */
    data class Setting(
        val setting: SettingId,
        override val title: String,
        override val change: String,
        override val undo: String,
        val now: String,
        val after: String,
    ) : Recommendation {
        override val id = setting.rec
    }

    /** One Android permission request for every missing group. */
    data class AskPermissions(val groups: List<PermissionGroup>) : Recommendation {
        override val id = RecId.Permissions
        override val title = "Allow what Rivet uses"
        override val change = "Android asks you once for: ${groups.joinToString(", ") { it.name }}"
        override val undo = "Turn them off in Android Settings › Apps › Rivet › Permissions."
    }

    /** Android doesn't let an app make itself the assistant; this opens the screen where the user picks Rivet. */
    data object OpenAssistantSettings : Recommendation {
        override val id = RecId.Assistant
        override val title = "Make Rivet your assistant"
        override val change = "Opens Android's default apps screen so you can pick Rivet. Android doesn't let apps set this themselves."
        override val undo = "Pick another assistant in the same screen."
    }

    /** A standing deny for something the user keeps declining. */
    data class StopAsking(val candidate: DenyCandidate) : Recommendation {
        override val id = RecId.StopAsking
        override val title = "Stop asking about ${candidate.label}"
        override val change = "You said no ${candidate.denials} times in the last 30 days. Rivet will always decline it."
        override val undo = "Removes the rule."
    }
}

/** The preferences recommended settings change, and the undo snapshot they keep ([Settings] implements it). */
interface RecommendedPrefs {
    var autoConnect: Boolean
    var speakInOverlay: Boolean
    var offerReplies: Boolean
    var answeringMode: ForwardMode
    var recommendedUndo: RecommendedUndo?
}

/** What applying recommendations changed, so undo can put it back. Null fields were not changed. */
data class RecommendedUndo(
    val autoConnect: Boolean? = null,
    val speakInOverlay: Boolean? = null,
    val offerReplies: Boolean? = null,
    val answeringMode: ForwardMode? = null,
    val ruleTargets: Set<RuleTarget> = emptySet(),
    val at: Long = 0,
) {
    /** The titles of what undo puts back. */
    fun titles(): List<String> = listOfNotNull(
        autoConnect?.let { "Connect automatically" },
        speakInOverlay?.let { "Quiet assistant overlay" },
        offerReplies?.let { "Offer replies to new messages" },
        answeringMode?.let { "Answer only calls you miss" },
        if (ruleTargets.isEmpty()) null else if (ruleTargets.size == 1) "1 rule" else "${ruleTargets.size} rules",
    )
}

object Recommended {
    private const val ON = "On"
    private const val OFF = "Off"

    /** Every recommendation that would change something, in the order the sheet shows them. */
    fun list(current: Current): List<Recommendation> = buildList {
        if (!current.autoConnect) {
            add(
                Recommendation.Setting(
                    SettingId.AutoConnect, "Connect automatically", "Rivet reconnects by itself, also after your phone restarts.",
                    "Turns automatic connection off again.", OFF, ON,
                ),
            )
        }
        if (current.speakInOverlay) {
            add(
                Recommendation.Setting(
                    SettingId.QuietOverlay, "Quiet assistant overlay",
                    "Replies in the assistant overlay are shown, not read aloud. Replies in the app are unchanged.",
                    "Reads overlay replies aloud again.", "Read aloud", "Shown",
                ),
            )
        }
        if (!current.offerReplies) {
            add(
                Recommendation.Setting(
                    SettingId.OfferReplies, "Offer replies to new messages",
                    "When a message arrives, Rivet drafts a reply and asks you before it sends anything.", "Stops offering replies.", OFF, ON,
                ),
            )
        }
        if (current.answeringMode != ForwardMode.Unanswered) {
            val carrier = if (current.forwardingAllCalls) {
                " Your carrier keeps forwarding every call until you turn call answering on again in Settings › Call answering."
            } else {
                ""
            }
            add(
                Recommendation.Setting(
                    SettingId.UnansweredCalls, "Answer only calls you miss",
                    "Rivet's line takes the calls you don't pick up, which is what lets you take a call over.$carrier",
                    "Goes back to answering every call (your carrier settings don't change).", "Every call", "Missed calls",
                ),
            )
        }
        if (current.missingPermissions.isNotEmpty()) add(Recommendation.AskPermissions(current.missingPermissions))
        if (!current.assistantHeld) add(Recommendation.OpenAssistantSettings)
        current.denyCandidates.forEach { add(Recommendation.StopAsking(it)) }
    }

    /**
     * Applies the [Recommendation.Setting] items in [chosen] to [prefs] and records what they were, with the targets of
     * the [Recommendation.StopAsking] items, as the undo snapshot. Permissions, the assistant and the rules themselves
     * are the caller's (they need Android screens and the brain).
     */
    fun apply(chosen: List<Recommendation>, prefs: RecommendedPrefs, now: Long): RecommendedUndo {
        var undo = RecommendedUndo(at = now)
        for (item in chosen) {
            when (item) {
                is Recommendation.Setting -> undo = applySetting(item.setting, prefs, undo)
                is Recommendation.StopAsking -> undo = undo.copy(ruleTargets = undo.ruleTargets + item.candidate.target)
                is Recommendation.AskPermissions, Recommendation.OpenAssistantSettings -> Unit
            }
        }
        val changed = undo.titles().isNotEmpty()
        if (changed) prefs.recommendedUndo = undo
        return undo
    }

    private fun applySetting(setting: SettingId, prefs: RecommendedPrefs, undo: RecommendedUndo): RecommendedUndo = when (setting) {
        SettingId.AutoConnect -> undo.copy(autoConnect = prefs.autoConnect).also { prefs.autoConnect = true }
        SettingId.QuietOverlay -> undo.copy(speakInOverlay = prefs.speakInOverlay).also { prefs.speakInOverlay = false }
        SettingId.OfferReplies -> undo.copy(offerReplies = prefs.offerReplies).also { prefs.offerReplies = true }
        SettingId.UnansweredCalls -> undo.copy(answeringMode = prefs.answeringMode).also { prefs.answeringMode = ForwardMode.Unanswered }
    }

    /** Puts back what [RecommendedPrefs.recommendedUndo] recorded and clears it; returns it (the caller revokes its rules). */
    fun undo(prefs: RecommendedPrefs): RecommendedUndo? {
        val undo = prefs.recommendedUndo ?: return null
        undo.autoConnect?.let { prefs.autoConnect = it }
        undo.speakInOverlay?.let { prefs.speakInOverlay = it }
        undo.offerReplies?.let { prefs.offerReplies = it }
        undo.answeringMode?.let { prefs.answeringMode = it }
        prefs.recommendedUndo = null
        return undo
    }
}

/** A rule target as one string for a preference set: the tool, then each `field=value`, all URL-encoded, NUL-separated. */
object RuleTargetCodec {
    private const val SEPARATOR = '\u0000'

    fun encode(target: RuleTarget): String =
        (listOf(enc(target.tool)) + target.scope.toSortedMap().map { (k, v) -> "${enc(k)}=${enc(v)}" }).joinToString(SEPARATOR.toString())

    fun decode(text: String): RuleTarget? {
        val parts = text.split(SEPARATOR)
        val tool = parts.firstOrNull()?.let(::dec)?.takeIf { it.isNotBlank() } ?: return null
        val scope = mutableMapOf<String, String>()
        for (part in parts.drop(1)) {
            val at = part.indexOf('=')
            if (at < 0) return null
            scope[dec(part.substring(0, at))] = dec(part.substring(at + 1))
        }
        return RuleTarget(tool, scope)
    }

    private fun enc(v: String): String = URLEncoder.encode(v, Charsets.UTF_8.name())

    private fun dec(v: String): String = URLDecoder.decode(v, Charsets.UTF_8.name())
}
