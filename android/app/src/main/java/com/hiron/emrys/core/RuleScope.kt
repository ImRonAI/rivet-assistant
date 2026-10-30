package com.hiron.emrys.core

import org.json.JSONObject

/**
 * Rule targets for the approvals the phone asks itself (the ElevenLabs agents' phone tools). Normalizing matches
 * brain/src/emrys_brain/rules.py `normalize`, so a rule the brain stores matches the same call here.
 */
object RuleScope {
    private val PHONE_NOISE = setOf(' ', '\t', '\n', '\r', '\u000c', '\u000b', '(', ')', '.', '-')

    fun phone(v: String): String = v.filterNot { it in PHONE_NOISE }

    /** Leading and trailing whitespace removed, as Python's `str.strip()` does. */
    fun text(v: String): String = v.trim()

    /** The rule target of one call, or null when it can't have a rule. */
    internal fun target(tool: String, input: JSONObject): RuleTarget? = when (tool) {
        "sms_send" -> string(input, "to")?.let(::phone)?.takeIf { it.isNotEmpty() }?.let { RuleTarget(tool, mapOf("to" to it)) }
        "phone_call" -> string(input, "number")?.let(::phone)?.takeIf { it.isNotEmpty() }?.let { RuleTarget(tool, mapOf("number" to it)) }
        "calendar_create" -> RuleTarget(tool, emptyMap())
        "screen_act" -> if (string(input, "action") != "tap") {
            null
        } else {
            string(input, "label")?.let(::text)?.takeIf { it.isNotEmpty() }?.let { RuleTarget(tool, mapOf("label" to it)) }
        }
        else -> null
    }

    private fun string(input: JSONObject, key: String): String? =
        if (input.has(key) && !input.isNull(key)) input.opt(key) as? String else null
}
