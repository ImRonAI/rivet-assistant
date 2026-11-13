package com.hiron.ren.mcp

import com.hiron.ren.core.PermAction
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The phone tools as MCP tools: the same 18 names and inputs [com.hiron.ren.device.DeviceTools] runs ([all]), then
 * `request_approval` ([approval]) and the search tools ([searches]), the rest of what the agents call on the phone.
 */
internal object PhoneTools {
    private const val ISO = "ISO-8601 in the phone's time zone, like 2026-10-27T09:00, or epoch milliseconds"

    /** Tools that reach people outside the phone. */
    private val OPEN_WORLD = setOf("sms_send", "phone_call", "notification_reply", "screen_act")

    /** Tools that can remove or overwrite something rather than only add. */
    private val DESTRUCTIVE = setOf("reminder_cancel", "screen_act")

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

    /** `request_approval`, with the agents' own client tool definition. */
    val approval: Tool = tool(
        "request_approval",
        "Ask the user to approve an action before you run it, when the action's permission is \"prompt\". The phone shows the user " +
            "an approval with the details and an \"Always allow\" choice. Returns approved (true or false) and remember_decision (true " +
            "when the user chose Always allow, which the phone saves as \"granted\").",
        true, required("action_type", "details"),
        "action_type" to enum(*PermAction.entries.map { it.wire }.toTypedArray()),
        "details" to str("Exactly what will happen, in plain words the user can check, e.g. \"Text Dana at +1 801-555-0100: 'Friday at 9 works.'\""),
    )

    private const val FRESHNESS = "Filter results by discovery time: pd (past day), pw (past week), pm (past month), py (past year)."
    private const val COUNT = "Number of results to return (1-20, default 10)."

    /** The seven search tools, with the inputs [com.hiron.ren.voice.SearchTools] reads. The results show in the chat. */
    val searches: List<Tool> = listOf(
        tool("web_search", "Search the web with Brave: titles, URLs and snippets, shown to the user in the chat.", true, required("q"),
            "q" to str("The search query string."), "count" to int(COUNT), "freshness" to fresh()),
        tool("news_search", "Search news articles with Brave, shown to the user in the chat.", true, required("q"),
            "q" to str("The news search query string."), "count" to int(COUNT), "freshness" to fresh()),
        tool("image_search", "Search images with Brave, shown to the user in the chat.", true, required("q"),
            "q" to str("The image search query string."), "count" to int(COUNT)),
        tool("video_search", "Search videos with Brave, shown to the user in the chat.", true, required("q"),
            "q" to str("The video search query string."), "count" to int(COUNT), "freshness" to fresh()),
        tool("place_search", "Search local businesses, places and points of interest with Brave, shown to the user in the chat. Give q, location or both.", true, none(),
            "q" to str("The place or local query string (e.g. coffee shop, restaurant)."), "location" to str("Location string (e.g. \"austin tx united states\")."),
            "count" to int(COUNT)),
        tool("answers", "Ask Brave Answers for a synthesized answer with citations; the sources are shown to the user in the chat.", true, required("q"),
            "q" to str("The question to answer.")),
        tool("llm_context", "Get clean LLM context from web pages. Best for retrieving detailed page content or background info for grounding.", true, required("q"),
            "q" to str("The search query string."), "count" to int("Number of pages to read (1-20, default 10)."), "freshness" to fresh(),
            "maximum_number_of_tokens" to int("Maximum number of tokens to return (1024-8192, default 2048).")),
    )

    private fun tool(name: String, description: String, readOnly: Boolean, required: List<String>, vararg properties: Pair<String, JsonObject>): Tool = Tool(
        name = name,
        description = description,
        inputSchema = ToolSchema(
            properties = buildJsonObject { properties.forEach { (key, schema) -> put(key, schema) } },
            required = required.ifEmpty { null },
        ),
        annotations = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = name in DESTRUCTIVE, openWorldHint = name in OPEN_WORLD),
    )

    private fun required(vararg keys: String): List<String> = keys.toList()
    private fun none(): List<String> = emptyList()
    private fun str(description: String): JsonObject = buildJsonObject { put("type", "string"); put("description", description) }
    private fun int(description: String): JsonObject = buildJsonObject { put("type", "integer"); put("description", description) }
    private fun enum(vararg values: String): JsonObject = buildJsonObject { put("type", "string"); putJsonArray("enum") { values.forEach { add(it) } } }
    private fun fresh(): JsonObject = buildJsonObject { put("type", "string"); put("description", FRESHNESS); putJsonArray("enum") { listOf("pd", "pw", "pm", "py").forEach { add(it) } } }
}
