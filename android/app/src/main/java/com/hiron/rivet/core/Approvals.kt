package com.hiron.rivet.core

/** An action's risk tier, as the brain classifies it (brain/src/rivet_brain/approvals.py `Tier`). */
enum class Tier(val wire: String) {
    Read("read"), Send("send"), Write("write"), Destructive("destructive"), Purchase("purchase"), Unknown("unknown");

    /** Needs the strongest confirmation; never a standing allow. */
    val high: Boolean get() = this == Destructive || this == Purchase || this == Unknown

    companion object {
        /** Anything unrecognised is [Unknown], and so high. */
        fun of(wire: String?): Tier = entries.firstOrNull { it.wire == wire } ?: Unknown
    }
}

/** What a standing rule does. */
enum class Decision(val wire: String) {
    Allow("allow"), Deny("deny");

    companion object {
        fun of(wire: String?): Decision? = entries.firstOrNull { it.wire == wire }
    }
}

/** Which phone request a `rules.refused` event answers (brain/src/rivet_brain/gateway.py `RuleRequest`). */
enum class RuleRequest(val wire: String) {
    Set("set"), Revoke("revoke");

    companion object {
        fun of(wire: String?): RuleRequest? = entries.firstOrNull { it.wire == wire }
    }
}

/** Why a rule offer can only be a standing deny (brain/src/rivet_brain/rules.py `NoAllow`). */
enum class NoAllow(val wire: String) {
    Purchase("purchase"), Destructive("destructive"), Unknown("unknown"), Kind("kind"), Untrusted("untrusted");

    companion object {
        fun of(wire: String?): NoAllow? = entries.firstOrNull { it.wire == wire }
    }
}

/** Action + recipient: what a standing rule is about. */
data class RuleTarget(val tool: String, val scope: Map<String, String>)

/** The "don't ask again" choices an approval offers. */
data class RuleOffer(val target: RuleTarget, val label: String, val allow: Boolean, val deny: Boolean, val noAllow: NoAllow?)

/** One "always allow" or "always decline", as the brain keeps it. [key] is the brain's id for revoking it. */
data class StandingRule(val key: String, val target: RuleTarget, val decision: Decision, val label: String, val createdAt: Double)

/** Every standing rule of the session; [revision] grows with every change on the brain. */
data class RuleSet(val revision: Int, val rules: List<StandingRule>) {
    fun match(target: RuleTarget): StandingRule? = rules.firstOrNull { it.target == target }

    companion object {
        val EMPTY = RuleSet(0, emptyList())
    }
}

/** One piece of an approval's body. */
sealed interface AlertBlock {
    data class Field(val label: String, val value: String) : AlertBlock
    data class Quote(val label: String, val text: String) : AlertBlock

    /** Brain-authored markdown, rendered with [com.hiron.rivet.ui.Rich]. */
    data class Note(val markdown: String) : AlertBlock
}

/** One approval Rivet is waiting on, as rendered on the approval sheet and in notifications. */
data class ApprovalRequest(
    val id: String,
    val lane: String,
    val turnId: String,
    val tool: String,
    val tier: Tier,
    val headline: String,
    val blocks: List<AlertBlock>,
    /** Why Rivet is asking, in the user's terms ("You asked: …"). */
    val because: String?,
    /** The raw input, for the technical details. */
    val preview: String,
    val untrustedTurn: Boolean,
    val operations: List<Operation>,
    val offer: RuleOffer?,
    val expiresAt: Double?,
) {
    val high: Boolean get() = tier.high

    data class Operation(val name: String, val method: String?, val summary: String)
}
