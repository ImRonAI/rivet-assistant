package com.hiron.emrys.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The phone tools as MCP tools: the same 18 names and inputs [com.hiron.emrys.device.DeviceTools] runs. */
internal object PhoneTools {
    private const val ISO = "ISO-8601 in the phone's time zone, like 2026-10-27T09:00, or epoch milliseconds"

    val all: List<Tool> = listOf(
        tool("sms_send", "Send a text message.", false, required("to", "body"), "to" to str("Phone number or contact name"), "body" to str("The message")),
        tool("sms_list", "Recent text messages, newest first.", true, none(), "contact" to str("Only messages with this contact"), "limit" to int("How many, default 20")),
        tool("contacts_search", "Find contacts by name or number.", true, required("query"), "query" to str("Name or number"), "limit" to int("How many, default 10")),
        tool("calls_recent", "Recent calls, newest first.", true, none(), "limit" to int("How many, default 20")),
        tool("phone_call", "Place a phone call from this phone.", false, required("number"), "number" to str("The number to call")),
        tool("calendar_list", "Upcoming calendar events.", true, none(), "days" to int("How many days ahead, default 7"), "query" to str("Only events matching this text")),
        tool("calendar_create", "Create a calendar event.", false, required("title", "start"), "title" to str("Event title"), "start" to str("Start, $ISO"), "end" to str("End, $ISO"), "location" to str("Where"), "description" to str("Notes")),
        tool("notifications_list", "Notifications showing on the phone.", true, none(), "app" to str("Only this app")),
        tool("notification_reply", "Reply to a notification that offers a reply.", false, required("key", "text"), "key" to str("The notification's key from notifications_list"), "text" to str("The reply")),
        tool("reminder_create", "Set a reminder.", false, required("text", "at"), "text" to str("What to remind"), "at" to str("When, $ISO")),
        tool("reminders_list", "Pending reminders.", true, none()),
        tool("reminder_cancel", "Cancel a reminder.", false, required("id"), "id" to str("The reminder's id from reminders_list")),
        tool("email_draft", "Open a draft in the phone's email app.", false, none(), "to" to str("Recipient"), "subject" to str("Subject"), "body" to str("Body")),
        tool("apps_list", "Installed apps.", true, none(), "query" to str("Only apps matching this text")),
        tool("app_open", "Open an app.", false, required("app"), "app" to str("App name or package")),
        tool("device_status", "Battery, network and the app in front.", true, none()),
        tool("screen_read", "Read the screen as a numbered list of elements.", true, none()),
        tool(
            "screen_act", "Tap, type or scroll on an element from the last screen_read, or press back, home, recents or notifications.", false, required("action"),
            "action" to enum("tap", "type", "scroll_down", "scroll_up", "back", "home", "recents", "notifications"),
            "node" to int("The element's number from screen_read"), "label" to str("The element's label exactly as screen_read showed it"), "text" to str("The text to type"),
        ),
    )

    private fun tool(name: String, description: String, readOnly: Boolean, required: List<String>, vararg properties: Pair<String, JsonObject>): Tool = Tool(
        name = name,
        description = description,
        inputSchema = ToolSchema(
            properties = buildJsonObject { properties.forEach { (key, schema) -> put(key, schema) } },
            required = required.ifEmpty { null },
        ),
        annotations = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = false, openWorldHint = false),
    )

    private fun required(vararg keys: String): List<String> = keys.toList()
    private fun none(): List<String> = emptyList()
    private fun str(description: String): JsonObject = buildJsonObject { put("type", "string"); put("description", description) }
    private fun int(description: String): JsonObject = buildJsonObject { put("type", "integer"); put("description", description) }
    private fun enum(vararg values: String): JsonObject = buildJsonObject { put("type", "string"); putJsonArray("enum") { values.forEach { add(it) } } }
}
