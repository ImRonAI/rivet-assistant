package com.hiron.rivet.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * The tools the brain can run on this phone, by the names the brain registers them under
 * (brain/src/rivet_brain/device_tools.py). Every tool answers a [ToolResult]; a missing permission is an
 * error that tells the model what the user has to grant, never a crash.
 */
class DeviceTools(
    private val context: Context,
    private val reminders: Reminders,
    private val isVisible: () -> Boolean,
) {
    private val messaging = Messaging(context)
    private val calendar = CalendarTool(context)
    private val apps = Apps(context, isVisible)

    private val tools: Map<String, Tool> = mapOf(
        "sms_send" to Tool(listOf(Manifest.permission.SEND_SMS)) { messaging.send(it.req("to"), it.req("body")) },
        "sms_list" to Tool(listOf(Manifest.permission.READ_SMS)) {
            messaging.list(it.optString("contact").ifBlank { null }, it.optInt("limit", 20))
        },
        "contacts_search" to Tool(listOf(Manifest.permission.READ_CONTACTS)) {
            messaging.contacts(it.req("query"), it.optInt("limit", 10))
        },
        "calls_recent" to Tool(listOf(Manifest.permission.READ_CALL_LOG)) { messaging.recentCalls(it.optInt("limit", 20)) },
        "phone_call" to Tool(listOf(Manifest.permission.CALL_PHONE)) { messaging.call(it.req("number")) },
        "calendar_list" to Tool(listOf(Manifest.permission.READ_CALENDAR)) {
            calendar.list(it.optInt("days", 7), it.optString("query").ifBlank { null })
        },
        "calendar_create" to Tool(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            calendar.create(
                it.req("title"), it.req("start"), it.optString("end").ifBlank { null },
                it.optString("location").ifBlank { null }, it.optString("description").ifBlank { null },
            )
        },
        "notifications_list" to Tool { RivetNotificationListener.list(context, it.optString("app").ifBlank { null }) },
        "notification_reply" to Tool { RivetNotificationListener.reply(context, it.req("key"), it.req("text")) },
        "reminder_create" to Tool { reminders.create(it.req("text"), it.req("at")) },
        "reminders_list" to Tool { reminders.list() },
        "reminder_cancel" to Tool { reminders.cancel(it.req("id")) },
        "email_draft" to Tool {
            apps.emailDraft(it.optString("to"), it.optString("subject"), it.optString("body"))
        },
        "apps_list" to Tool { apps.list(it.optString("query").ifBlank { null }) },
        "app_open" to Tool { apps.open(it.req("app")) },
        "device_status" to Tool { apps.status() },
        "screen_read" to Tool { ScreenOperator.read() },
        "screen_act" to Tool {
            ScreenOperator.act(
                it.req("action"),
                if (it.has("node")) it.optInt("node") else null,
                it.optString("label").ifBlank { null },
                it.optString("text").ifBlank { null },
            )
        },
    )

    val names: Set<String> get() = tools.keys

    suspend fun run(tool: String, input: JSONObject): ToolResult {
        val entry = tools[tool] ?: return ToolResult.error("This phone has no tool named $tool. Update the Rivet app.")
        val missing = entry.permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            return ToolResult.error(
                "Rivet doesn't have permission for this on the phone (${missing.joinToString { it.substringAfterLast('.') }}). " +
                    "Ask the user to open Rivet › Settings › Permissions and allow it.",
            )
        }
        return try {
            entry.run(input)
        } catch (e: MissingInput) {
            ToolResult.error(e.message ?: "missing input")
        } catch (e: SecurityException) {
            ToolResult.error("Android refused this action: ${e.message}. A permission or role may be missing.")
        }
    }

    private class Tool(val permissions: List<String> = emptyList(), val run: suspend (JSONObject) -> ToolResult)
}

class MissingInput(field: String) : IllegalArgumentException("Missing required input \"$field\".")

internal fun JSONObject.req(key: String): String =
    optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() } ?: throw MissingInput(key)
