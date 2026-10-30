package com.hiron.emrys.core

import org.json.JSONArray
import org.json.JSONObject

/** Messages from the brain's gateway (see brain/src/emrys_brain/gateway.py). */
sealed interface ServerEvent {
    data class TurnAccepted(val turnId: String?) : ServerEvent
    data class TurnStarted(val turnId: String, val lane: String, val goal: String?) : ServerEvent
    data class TextDelta(val delta: String, val lane: String) : ServerEvent
    data class Thinking(val delta: String, val lane: String) : ServerEvent
    data class Searching(val queries: List<String>, val lane: String) : ServerEvent
    data class ToolStarted(val id: String, val name: String, val lane: String) : ServerEvent
    data class ToolsDone(val results: List<Pair<String, String>>, val lane: String) : ServerEvent
    data class Backoff(val delaySeconds: Double, val lane: String) : ServerEvent
    data class Notice(val kind: String, val detail: String, val lane: String) : ServerEvent
    data class ApprovalsRequested(val turnId: String, val lane: String, val approvals: List<ApprovalRequest>, val resent: Boolean) : ServerEvent
    data class ApprovalExpired(val turnId: String, val lane: String) : ServerEvent
    data class Restart(val reason: String, val lane: String) : ServerEvent
    data class TurnCompleted(val turnId: String, val lane: String, val text: String, val restarts: Int, val goal: String?) : ServerEvent
    data class TurnFailed(val turnId: String?, val lane: String, val error: String) : ServerEvent
    data class DeviceCall(val actionId: String, val tool: String, val input: JSONObject) : ServerEvent
    data class CallAnswering(val from: String, val verified: Boolean, val callId: String = "") : ServerEvent
    data class CallMessage(
        val callerName: String,
        val callbackNumber: String,
        val message: String,
        val urgent: Boolean,
        val hungUpEarly: Boolean,
        /** The call it was taken on; empty when the brain doesn't know it. */
        val callId: String = "",
    ) : ServerEvent

    /** A call Emrys is on: its state and the transcript lines since the last one (all of them when [snapshot]). */
    data class CallLive(
        val callId: String,
        val from: String,
        val verified: Boolean,
        val startedAt: Double?,
        val status: String,
        val lines: List<CallLineText>,
        val snapshot: Boolean,
    ) : ServerEvent
    data class CallTakeOverFailed(val callId: String, val error: String) : ServerEvent
    data class CallEnded(
        val callId: String,
        val from: String,
        val callerName: String,
        val startedAt: Double?,
        val endedAt: Double?,
        val durationSeconds: Int?,
        val summary: String,
        val outcome: String,
        val takenOver: Boolean,
    ) : ServerEvent
    /** Every standing rule of the session (on `resume` and after every change). */
    data class Rules(val set: RuleSet) : ServerEvent

    /** The brain refused a `rules.set` or `rules.revoke` ([request] is null for a kind this app doesn't know); [error] is plain text for the user. */
    data class RulesRefused(val request: RuleRequest?, val error: String) : ServerEvent

    /** A standing rule decided one of the brain's calls without asking. */
    data class RulesApplied(val decision: Decision, val key: String, val label: String, val tool: String) : ServerEvent

    /** What one of the brain's search tools found ([toolId] is its call), for the phone to show as cards. */
    data class SearchResults(val toolId: String, val results: com.hiron.emrys.core.SearchResults, val lane: String) : ServerEvent

    /** The model Emrys's own brain runs for this session and the ones it could; on `resume` and after every change. */
    data class Model(val model: BrainModel?, val available: Set<BrainModel>) : ServerEvent

    /** The brain refused a `model.set`; [error] is plain text for the user. */
    data class ModelRefused(val error: String) : ServerEvent
    data object SessionReady : ServerEvent
    data class Error(val message: String) : ServerEvent
    data class Unknown(val type: String) : ServerEvent
}

object Protocol {
    fun parse(raw: String): ServerEvent {
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return ServerEvent.Error("unreadable message from the brain")
        }
        val lane = o.optString("lane", "default")
        return when (val type = o.optString("type")) {
            "turn.accepted" -> ServerEvent.TurnAccepted(o.optStringOrNull("turn_id"))
            "turn.started" -> ServerEvent.TurnStarted(o.optString("turn_id"), lane, o.optStringOrNull("goal"))
            "text" -> ServerEvent.TextDelta(o.optString("delta"), lane)
            "thinking" -> ServerEvent.Thinking(o.optString("delta"), lane)
            "searching" -> ServerEvent.Searching(o.optJSONArray("queries").strings(), lane)
            "tool" -> ServerEvent.ToolStarted(o.optString("id"), o.optString("name"), lane)
            "tool_done" -> ServerEvent.ToolsDone(toolResults(o.optJSONArray("tools")), lane)
            "backoff" -> ServerEvent.Backoff(o.optDouble("delay", 0.0), lane)
            "notice" -> ServerEvent.Notice(o.optString("kind"), o.optString("reason", o.optString("kind")), lane)
            "approval.request" -> ServerEvent.ApprovalsRequested(
                o.optString("turn_id"), lane, approvals(o, lane), o.optBoolean("resent", false),
            )
            "approval.expired" -> ServerEvent.ApprovalExpired(o.optString("turn_id"), lane)
            "restart" -> ServerEvent.Restart(o.optString("reason"), lane)
            "turn.completed" -> ServerEvent.TurnCompleted(
                o.optString("turn_id"), lane, o.optString("text"), o.optInt("restarts", 0), o.optStringOrNull("goal"),
            )
            "turn.failed" -> ServerEvent.TurnFailed(o.optStringOrNull("turn_id"), lane, o.optString("error", "failed"))
            "device.call" -> ServerEvent.DeviceCall(
                o.optString("action_id"), o.optString("tool"), o.optJSONObject("input") ?: JSONObject(),
            )
            "call.answering" -> ServerEvent.CallAnswering(o.optString("from"), o.optBoolean("verified", false), o.optString("call_id"))
            "call.message" -> ServerEvent.CallMessage(
                o.optString("caller_name"), o.optString("callback_number"), o.optString("message"),
                o.optBoolean("urgent", false), o.optBoolean("hung_up_early", false), o.optString("call_id"),
            )
            "call.live" -> ServerEvent.CallLive(
                o.optString("call_id"), o.optString("from"), o.optBoolean("verified", false), o.optDoubleOrNull("started_at"),
                o.optString("status", "answering"), callLines(o.optJSONArray("lines")), o.optBoolean("snapshot", false),
            )
            "call.take_over.failed" -> ServerEvent.CallTakeOverFailed(
                o.optString("call_id"), o.optStringOrNull("error") ?: "Emrys couldn't transfer the call",
            )
            "call.ended" -> ServerEvent.CallEnded(
                o.optString("call_id"), o.optString("from"), o.optString("caller_name"), o.optDoubleOrNull("started_at"),
                o.optDoubleOrNull("ended_at"), o.optDoubleOrNull("duration")?.toInt(), o.optString("summary"),
                o.optStringOrNull("outcome") ?: "unknown", o.optBoolean("taken_over", false),
            )
            "rules" -> ServerEvent.Rules(RuleCodec.set(o))
            "rules.refused" -> ServerEvent.RulesRefused(RuleRequest.of(o.optStringOrNull("request")), o.optStringOrNull("error") ?: "Emrys couldn't change that rule.")
            "rules.applied" -> Decision.of(o.optStringOrNull("decision"))?.let {
                ServerEvent.RulesApplied(it, o.optString("key"), o.optString("label"), o.optString("tool"))
            } ?: ServerEvent.Unknown(type)
            "search.results" -> SearchCodec.results(o.optJSONObject("results"))?.let {
                ServerEvent.SearchResults(o.optString("tool_id"), it, lane)
            } ?: ServerEvent.Unknown(type)
            "model" -> ServerEvent.Model(
                BrainModel.of(o.optStringOrNull("model")), o.optJSONArray("available").strings().mapNotNull(BrainModel::of).toSet(),
            )
            "model.refused" -> ServerEvent.ModelRefused(o.optStringOrNull("error") ?: "Emrys couldn't change the model.")
            "session.ready" -> ServerEvent.SessionReady
            "error" -> ServerEvent.Error(o.optString("error", "error"))
            else -> ServerEvent.Unknown(type)
        }
    }

    /** A chat turn; [attachments] are results the user added (gateway.py caps them at 5). */
    fun turn(text: String, turnId: String, attachments: List<ContextItem> = emptyList()): String {
        val body = JSONObject().put("type", "turn").put("text", text).put("turn_id", turnId)
        if (attachments.isNotEmpty()) {
            body.put(
                "attachments",
                JSONArray(
                    attachments.map { a ->
                        JSONObject().put("kind", a.kind.wire).put("title", a.title).put("url", a.url).apply {
                            a.description?.let { put("description", it) }
                            a.watchUrl?.let { put("watch_url", it) }
                            a.imageUrl?.let { put("image_url", it) }
                        }
                    },
                ),
            )
        }
        return body.toString()
    }

    fun modelSet(model: BrainModel): String = JSONObject().put("type", "model.set").put("model", model.wire).toString()

    /** A goal-driven background task on its own lane. */
    fun task(goal: String, taskId: String): String =
        JSONObject().put("type", "task").put("goal", goal).put("task_id", taskId).toString()

    /** [answers]: interrupt id to `{"approved": bool, "standing"?: "allow" | "deny"}`. */
    fun approvalResponse(lane: String, answers: Map<String, Answer>): String {
        val body = JSONObject()
        for ((id, answer) in answers) {
            val a = JSONObject().put("approved", answer.approved)
            answer.standing?.let { a.put("standing", it.wire) }
            body.put(id, a)
        }
        return JSONObject().put("type", "approval.response").put("lane", lane).put("answers", body).toString()
    }

    /** Asks the brain for a standing rule about [target] (for an approval the phone asked itself). */
    fun rulesSet(decision: Decision, target: RuleTarget): String = JSONObject()
        .put("type", "rules.set").put("decision", decision.wire).put("tool", target.tool).put("scope", JSONObject(target.scope))
        .toString()

    fun rulesRevoke(key: String): String = JSONObject().put("type", "rules.revoke").put("key", key).toString()

    /** Revokes the rule about [target], for one the phone asked for before the brain's `rules` event listed it. */
    fun rulesRevoke(target: RuleTarget): String = JSONObject()
        .put("type", "rules.revoke").put("tool", target.tool).put("scope", JSONObject(target.scope))
        .toString()

    fun deviceResult(actionId: String, result: JSONObject): String =
        JSONObject().put("type", "device.result").put("action_id", actionId).put("result", result).toString()

    /** Transfers live call [callId] to [to], the user's own number; the brain refuses one that isn't E.164. */
    fun takeOver(callId: String, to: String): String =
        JSONObject().put("type", "call.take_over").put("call_id", callId).put("to", to).toString()

    fun resume(): String = JSONObject().put("type", "resume").toString()

    /** One approval's answer. A [standing] rule goes only with the matching answer: allow with yes, deny with no. */
    data class Answer(val approved: Boolean, val standing: Decision? = null) {
        init {
            require(standing == null || (standing == Decision.Allow) == approved) { "a standing $standing contradicts the answer" }
        }
    }

    /** Prompt v2 (`approvals.Assessment.prompt`); a v1 prompt from an older brain falls back to its summary. */
    private fun approvals(o: JSONObject, lane: String): List<ApprovalRequest> {
        val list = o.optJSONArray("approvals") ?: return emptyList()
        val turnId = o.optString("turn_id")
        val expiresAt = o.optDoubleOrNull("expires_at")
        return (0 until list.length()).mapNotNull { i ->
            val a = list.optJSONObject(i) ?: return@mapNotNull null
            val r = a.optJSONObject("request") ?: JSONObject().put("headline", a.optString("request"))
            ApprovalRequest(
                id = a.optString("id"),
                lane = lane,
                turnId = turnId,
                tool = r.optString("tool", a.optString("name")),
                tier = Tier.of(r.optStringOrNull("tier")),
                headline = r.optStringOrNull("headline") ?: r.optStringOrNull("summary") ?: "Emrys wants to use a tool",
                blocks = blocks(r.optJSONArray("blocks")),
                because = a.optStringOrNull("because")?.takeIf { it.isNotBlank() },
                preview = r.optString("preview"),
                untrustedTurn = r.optBoolean("untrusted_turn", false),
                operations = operations(r.optJSONArray("operations")),
                offer = RuleCodec.offer(r.optJSONObject("offer")),
                expiresAt = expiresAt,
            )
        }
    }

    private fun blocks(list: JSONArray?): List<AlertBlock> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val b = list.optJSONObject(i) ?: return@mapNotNull null
            when (b.optString("kind")) {
                "field" -> AlertBlock.Field(b.optString("label"), b.optString("value"))
                "quote" -> AlertBlock.Quote(b.optString("label"), b.optString("text"))
                "note" -> AlertBlock.Note(b.optString("markdown"))
                else -> null
            }
        }

    private fun operations(list: JSONArray?): List<ApprovalRequest.Operation> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val op = list.optJSONObject(i) ?: return@mapNotNull null
            ApprovalRequest.Operation(op.optString("name"), op.optStringOrNull("method"), op.optString("summary"))
        }

    private fun toolResults(list: JSONArray?): List<Pair<String, String>> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val r = list.optJSONObject(i) ?: return@mapNotNull null
            r.optString("id") to r.optString("status")
        }

    private fun callLines(list: JSONArray?): List<CallLineText> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val line = list.optJSONObject(i) ?: return@mapNotNull null
            CallLineText(line.optString("speaker"), line.optString("text")).takeIf { it.text.isNotBlank() }
        }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
