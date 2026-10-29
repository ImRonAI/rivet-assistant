package com.hiron.rivet.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One answer the user gave to an approval that offered "don't ask again". */
data class DecisionRecord(val target: RuleTarget, val label: String, val approved: Boolean, val at: Long)

/** Something the user keeps saying no to: a candidate standing deny for the recommended settings. */
data class DenyCandidate(val target: RuleTarget, val label: String, val denials: Int)

/**
 * The user's recent approval answers, kept on this phone only. Recommended settings suggest a standing deny for
 * what was declined [MIN_DENIALS] times and never approved within [WINDOW_MS].
 */
class DecisionLog(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private var records: List<DecisionRecord> = load()

    @Synchronized
    fun record(target: RuleTarget, label: String, approved: Boolean) {
        val now = clock()
        records = (records + DecisionRecord(target, label, approved, now)).filter { now - it.at <= WINDOW_MS }.takeLast(LIMIT)
        save()
    }

    @Synchronized
    fun denyCandidates(rules: RuleSet): List<DenyCandidate> {
        val now = clock()
        return records.filter { now - it.at <= WINDOW_MS }
            .groupBy { it.target }
            .filter { (target, answers) -> answers.none { it.approved } && answers.size >= MIN_DENIALS && rules.match(target) == null }
            .map { (target, answers) -> DenyCandidate(target, answers.last().label, answers.size) }
            .sortedByDescending { it.denials }
    }

    private fun load(): List<DecisionRecord> {
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return emptyList()
        val list = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val tool = o.optStringOrNull("tool") ?: return@mapNotNull null
            val scope = RuleCodec.scope(o.optJSONObject("scope")) ?: return@mapNotNull null
            DecisionRecord(RuleTarget(tool, scope), o.optString("label"), o.optBoolean("approved"), o.optLong("at"))
        }
    }

    private fun save() {
        val list = JSONArray()
        records.forEach {
            list.put(
                JSONObject().put("tool", it.target.tool).put("scope", JSONObject(it.target.scope))
                    .put("label", it.label).put("approved", it.approved).put("at", it.at),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(list.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        const val LIMIT = 500
        const val MIN_DENIALS = 3
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}
