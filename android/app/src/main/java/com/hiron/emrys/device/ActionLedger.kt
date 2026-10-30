package com.hiron.emrys.device

import org.json.JSONObject
import java.io.File

/**
 * The phone's executed-actions ledger, keyed by the brain's tool use id.
 *
 * Every device call is recorded as started before it runs and as completed with its result after, in a file
 * written atomically. A call the brain re-sends (after a reconnect or a brain restart) gets the stored result
 * instead of running again; one that was running when the app died reports that it may have run.
 */
class ActionLedger(private val file: File, private val limit: Int = 500) {
    private val entries: LinkedHashMap<String, JSONObject> = load()
    private val running = mutableSetOf<String>()

    sealed interface Start {
        data object Run : Start
        data object AlreadyRunning : Start
        data class Finished(val result: JSONObject) : Start
        data object Interrupted : Start
    }

    @Synchronized
    fun start(actionId: String, tool: String): Start {
        val entry = entries[actionId]
        return when {
            entry == null -> {
                entries[actionId] = JSONObject().put("tool", tool).put("status", "started")
                running += actionId
                save()
                Start.Run
            }
            entry.optString("status") == "completed" -> Start.Finished(entry.getJSONObject("result"))
            actionId in running -> Start.AlreadyRunning
            else -> Start.Interrupted
        }
    }

    @Synchronized
    fun finish(actionId: String, result: JSONObject) {
        val entry = entries.remove(actionId) ?: JSONObject()
        entries[actionId] = entry.put("status", "completed").put("result", result)
        running -= actionId
        while (entries.size > limit) entries.remove(entries.keys.first())
        save()
    }

    /** Runs [block] at most once per [actionId]; null when the same call is already running here. */
    suspend fun run(actionId: String, tool: String, block: suspend () -> JSONObject): JSONObject? =
        when (val start = start(actionId, tool)) {
            Start.AlreadyRunning -> null
            is Start.Finished -> start.result
            Start.Interrupted -> INTERRUPTED.also { finish(actionId, it) }
            Start.Run -> {
                val result = try {
                    block()
                } catch (e: Exception) {
                    ToolResult.error("This action failed on the phone: ${e.message ?: e.javaClass.simpleName}").toJson()
                }
                finish(actionId, result)
                result
            }
        }

    private fun load(): LinkedHashMap<String, JSONObject> {
        val map = LinkedHashMap<String, JSONObject>()
        if (!file.exists()) return map
        try {
            val o = JSONObject(file.readText())
            o.keys().forEach { key -> o.optJSONObject(key)?.let { map[key] = it } }
        } catch (e: Exception) {
            // A corrupt ledger must not block every future action; start over and keep the bad copy.
            file.copyTo(File(file.path + ".corrupt"), overwrite = true)
        }
        return map
    }

    private fun save() {
        val o = JSONObject()
        entries.forEach { (k, v) -> o.put(k, v) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(o.toString())
            tmp.delete()
        }
    }

    companion object {
        val INTERRUPTED: JSONObject
            get() = ToolResult.error(
                "Emrys's phone app stopped while this action was running. It may or may not have happened; " +
                    "check before trying again.",
            ).toJson()
    }
}

/** A device tool's answer: `{"ok": bool, "data": ...}` as the brain's device proxy expects. */
data class ToolResult(val ok: Boolean, val data: Any?) {
    fun toJson(): JSONObject = JSONObject().put("ok", ok).put("data", data ?: JSONObject.NULL)

    companion object {
        fun ok(data: Any?) = ToolResult(true, data)
        fun error(message: String) = ToolResult(false, message)
    }
}
