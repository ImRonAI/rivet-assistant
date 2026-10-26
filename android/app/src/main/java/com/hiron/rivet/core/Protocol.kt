package com.hiron.rivet.core

import org.json.JSONArray
import org.json.JSONObject

/** One approval the brain is waiting on, as rendered on the approval sheet. */
data class ApprovalRequest(
    val id: String,
    val lane: String,
    val turnId: String,
    val tool: String,
    val tier: String,
    val high: Boolean,
    val summary: String,
    val preview: String,
    val canRemember: Boolean,
    val untrustedTurn: Boolean,
    val operations: List<Operation>,
    val expiresAt: Double?,
) {
    data class Operation(val name: String, val method: String?, val summary: String)
}

/** Messages from the brain's gateway (see brain/src/rivet_brain/gateway.py). */
sealed interface ServerEvent {
    data class TurnAccepted(val turnId: String?) : ServerEvent
    data class TurnStarted(val turnId: String, val lane: String, val goal: String?) : ServerEvent
    data class TextDelta(val delta: String, val lane: String) : ServerEvent
    data class Thinking(val delta: String, val lane: String) : ServerEvent
    data class Searching(val queries: List<String>, val lane: String) : ServerEvent
    data class Sources(val kind: String, val titles: List<String>, val lane: String) : ServerEvent
    data class Report(val kind: String, val lane: String) : ServerEvent
    data class ToolStarted(val id: String, val name: String, val lane: String) : ServerEvent
    data class ToolsDone(val results: List<Pair<String, String>>, val lane: String) : ServerEvent
    data class Backoff(val delaySeconds: Double, val lane: String) : ServerEvent
    data class Usage(val totalCost: Double?, val lane: String) : ServerEvent
    data class Notice(val kind: String, val detail: String, val lane: String) : ServerEvent
    data class ApprovalsRequested(val turnId: String, val lane: String, val approvals: List<ApprovalRequest>, val resent: Boolean) : ServerEvent
    data class ApprovalExpired(val turnId: String, val lane: String) : ServerEvent
    data class Restart(val reason: String, val lane: String) : ServerEvent
    data class TurnCompleted(val turnId: String, val lane: String, val text: String, val restarts: Int, val goal: String?) : ServerEvent
    data class TurnFailed(val turnId: String?, val lane: String, val error: String) : ServerEvent
    data class DeviceCall(val actionId: String, val tool: String, val input: JSONObject) : ServerEvent
    data object SessionReady : ServerEvent
    data object Pong : ServerEvent
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
            "sources" -> ServerEvent.Sources(o.optString("kind"), titles(o.optJSONArray("results")), lane)
            "report" -> ServerEvent.Report(o.optString("kind"), lane)
            "tool" -> ServerEvent.ToolStarted(o.optString("id"), o.optString("name"), lane)
            "tool_done" -> ServerEvent.ToolsDone(toolResults(o.optJSONArray("tools")), lane)
            "backoff" -> ServerEvent.Backoff(o.optDouble("delay", 0.0), lane)
            "usage" -> ServerEvent.Usage(o.optJSONObject("cost")?.optDouble("total_cost"), lane)
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
            "session.ready" -> ServerEvent.SessionReady
            "pong" -> ServerEvent.Pong
            "error" -> ServerEvent.Error(o.optString("error", "error"))
            else -> ServerEvent.Unknown(type)
        }
    }

    fun turn(text: String, turnId: String): String =
        JSONObject().put("type", "turn").put("text", text).put("turn_id", turnId).toString()

    fun task(goal: String, taskId: String): String =
        JSONObject().put("type", "task").put("goal", goal).put("task_id", taskId).toString()

    /** [answers]: interrupt id to `{"approved": bool, "remember_minutes"?: int}`. */
    fun approvalResponse(lane: String, answers: Map<String, Answer>): String {
        val body = JSONObject()
        for ((id, answer) in answers) {
            val a = JSONObject().put("approved", answer.approved)
            if (answer.rememberMinutes > 0) a.put("remember_minutes", answer.rememberMinutes)
            body.put(id, a)
        }
        return JSONObject().put("type", "approval.response").put("lane", lane).put("answers", body).toString()
    }

    fun deviceResult(actionId: String, result: JSONObject): String =
        JSONObject().put("type", "device.result").put("action_id", actionId).put("result", result).toString()

    fun resume(): String = JSONObject().put("type", "resume").toString()

    fun ping(): String = JSONObject().put("type", "ping").toString()

    data class Answer(val approved: Boolean, val rememberMinutes: Int = 0)

    private fun approvals(o: JSONObject, lane: String): List<ApprovalRequest> {
        val list = o.optJSONArray("approvals") ?: return emptyList()
        val turnId = o.optString("turn_id")
        val expiresAt = if (o.has("expires_at")) o.optDouble("expires_at") else null
        return (0 until list.length()).mapNotNull { i ->
            val a = list.optJSONObject(i) ?: return@mapNotNull null
            val r = a.optJSONObject("request") ?: JSONObject().put("summary", a.optString("request"))
            ApprovalRequest(
                id = a.optString("id"),
                lane = lane,
                turnId = turnId,
                tool = r.optString("tool", a.optString("name")),
                tier = r.optString("tier", "unknown"),
                high = r.optBoolean("high", true),
                summary = r.optString("summary", "Rivet wants to run a tool"),
                preview = r.optString("preview"),
                canRemember = r.optBoolean("remember", false),
                untrustedTurn = r.optBoolean("untrusted_turn", false),
                operations = operations(r.optJSONArray("operations")),
                expiresAt = expiresAt,
            )
        }
    }

    private fun operations(list: JSONArray?): List<ApprovalRequest.Operation> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val op = list.optJSONObject(i) ?: return@mapNotNull null
            ApprovalRequest.Operation(op.optString("name"), op.optStringOrNull("method"), op.optString("summary"))
        }

    private fun titles(list: JSONArray?): List<String> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val r = list.optJSONObject(i) ?: return@mapNotNull null
            r.optStringOrNull("title") ?: r.optStringOrNull("url")
        }

    private fun toolResults(list: JSONArray?): List<Pair<String, String>> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
            val r = list.optJSONObject(i) ?: return@mapNotNull null
            r.optString("id") to r.optString("status")
        }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
