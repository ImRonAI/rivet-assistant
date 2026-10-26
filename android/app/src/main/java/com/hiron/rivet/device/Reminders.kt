package com.hiron.rivet.device

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hiron.rivet.service.Notifications
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Reminders the user (through Rivet) sets on the phone, delivered by WorkManager even after a reboot. */
class Reminders(private val context: Context, private val store: ReminderStore) {

    fun create(text: String, at: String): ToolResult {
        val due = Times.parse(at) ?: return ToolResult.error("Can't read the time \"$at\"; use ISO-8601 like 2026-10-27T09:00.")
        val now = System.currentTimeMillis()
        if (due < now - 60_000) return ToolResult.error("That time (${Times.format(due)}) has already passed.")
        val id = UUID.randomUUID().toString().take(8)
        store.put(id, text, due)
        WorkManager.getInstance(context).enqueueUniqueWork(
            work(id),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay((due - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ReminderWorker.ID to id))
                .addTag(TAG)
                .build(),
        )
        return ToolResult.ok(JSONObject().put("id", id).put("text", text).put("at", Times.format(due)))
    }

    fun list(): ToolResult = ToolResult.ok(JSONObject().put("reminders", store.all()))

    fun cancel(id: String): ToolResult {
        if (!store.remove(id)) return ToolResult.error("No reminder with id $id.")
        WorkManager.getInstance(context).cancelUniqueWork(work(id))
        return ToolResult.ok(JSONObject().put("cancelled", id))
    }

    companion object {
        const val TAG = "rivet-reminder"
        fun work(id: String) = "reminder-$id"
    }
}

/** Pending reminders, persisted as one JSON file. */
class ReminderStore(private val file: File) {
    @Synchronized
    fun put(id: String, text: String, at: Long) = save(load().put(id, JSONObject().put("text", text).put("at", at)))

    @Synchronized
    fun get(id: String): JSONObject? = load().optJSONObject(id)

    @Synchronized
    fun remove(id: String): Boolean {
        val all = load()
        if (!all.has(id)) return false
        all.remove(id)
        save(all)
        return true
    }

    @Synchronized
    fun all(): JSONArray {
        val all = load()
        val rows = all.keys().asSequence().map { id ->
            val r = all.getJSONObject(id)
            JSONObject().put("id", id).put("text", r.optString("text")).put("at", Times.format(r.optLong("at"))).put("ms", r.optLong("at"))
        }.sortedBy { it.optLong("ms") }.toList()
        return JSONArray(rows.onEach { it.remove("ms") })
    }

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save(o: JSONObject) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) file.writeText(o.toString())
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(ID) ?: return Result.success()
        val store = ReminderStore(File(applicationContext.filesDir, ReminderStoreFile))
        val reminder = store.get(id) ?: return Result.success()
        Notifications.reminder(applicationContext, id, reminder.optString("text"))
        store.remove(id)
        return Result.success()
    }

    companion object {
        const val ID = "id"
    }
}

const val ReminderStoreFile = "reminders.json"
