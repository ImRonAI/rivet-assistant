package com.hiron.rivet.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's read-only copy of the brain's standing rules, kept on disk so the ElevenLabs path can follow them
 * while offline. Only the brain's `rules` events change it ([replace]); the phone asks for changes with
 * `rules.set` and `rules.revoke`.
 */
class RuleBook(private val file: File) {
    private val _state = MutableStateFlow(RuleSet.EMPTY)
    val state: StateFlow<RuleSet> = _state.asStateFlow()

    @Synchronized
    fun load() {
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        _state.value = runCatching { RuleCodec.set(JSONObject(text)) }.getOrNull() ?: return
    }

    /**
     * Takes [set] unless it is older than the copy held. [authoritative] is the brain's answer to `resume`, the full
     * list for the session this phone is on now, which replaces the copy whatever its revision.
     */
    @Synchronized
    fun replace(set: RuleSet, authoritative: Boolean = false) {
        if (!authoritative && set.revision < _state.value.revision) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(RuleCodec.json(set).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        _state.value = set
    }
}

/** The JSON shape of rules: the brain's `rules` event and the phone's copy on disk. */
internal object RuleCodec {
    fun set(o: JSONObject): RuleSet {
        val list = o.optJSONArray("rules") ?: JSONArray()
        return RuleSet(o.optInt("revision", 0), (0 until list.length()).mapNotNull { rule(list.optJSONObject(it)) })
    }

    fun rule(o: JSONObject?): StandingRule? {
        if (o == null) return null
        val decision = Decision.of(o.optStringOrNull("decision")) ?: return null
        val tool = o.optStringOrNull("tool")?.takeIf { it.isNotBlank() } ?: return null
        val key = o.optStringOrNull("key") ?: return null
        val scope = scope(o.optJSONObject("scope")) ?: return null
        return StandingRule(key, RuleTarget(tool, scope), decision, o.optString("label"), o.optDouble("created_at", 0.0))
    }

    fun offer(o: JSONObject?): RuleOffer? {
        if (o == null) return null
        val tool = o.optStringOrNull("tool") ?: return null
        val scope = scope(o.optJSONObject("scope")) ?: return null
        return RuleOffer(
            RuleTarget(tool, scope), o.optString("label"), o.optBoolean("allow", false), o.optBoolean("deny", false),
            NoAllow.of(o.optStringOrNull("no_allow")),
        )
    }

    /** String values only; anything else makes the whole scope unusable. */
    fun scope(o: JSONObject?): Map<String, String>? {
        if (o == null) return null
        val scope = mutableMapOf<String, String>()
        for (key in o.keys()) scope[key] = o.opt(key) as? String ?: return null
        return scope
    }

    fun json(set: RuleSet): JSONObject = JSONObject()
        .put("revision", set.revision)
        .put("rules", JSONArray().apply { set.rules.forEach { put(json(it)) } })

    private fun json(rule: StandingRule): JSONObject = JSONObject()
        .put("key", rule.key)
        .put("tool", rule.target.tool)
        .put("scope", JSONObject(rule.target.scope))
        .put("decision", rule.decision.wire)
        .put("label", rule.label)
        .put("created_at", rule.createdAt)
}
