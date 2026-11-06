package com.hiron.emrys.mcp

import com.hiron.emrys.core.PermAction
import com.hiron.emrys.voice.AgentTools
import com.hiron.emrys.voice.SearchTools
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolsTest {
    /** The inputs `DeviceTools` reads for each tool, copied from `DeviceTools.kt`. */
    private val inputs: Map<String, Set<String>> = mapOf(
        "sms_send" to setOf("to", "body"),
        "sms_list" to setOf("contact", "limit"),
        "contacts_search" to setOf("query", "limit"),
        "calls_recent" to setOf("limit"),
        "phone_call" to setOf("number"),
        "calendar_list" to setOf("days", "query"),
        "calendar_create" to setOf("title", "start", "end", "location", "description"),
        "notifications_list" to setOf("app"),
        "notification_reply" to setOf("key", "text"),
        "reminder_create" to setOf("text", "at"),
        "reminders_list" to emptySet(),
        "reminder_cancel" to setOf("id"),
        "email_draft" to setOf("to", "subject", "body"),
        "apps_list" to setOf("query"),
        "app_open" to setOf("app"),
        "device_status" to emptySet(),
        "screen_read" to emptySet(),
        "screen_act" to setOf("action", "node", "label", "text"),
    )

    private val readOnly = setOf(
        "sms_list", "contacts_search", "calls_recent", "calendar_list", "notifications_list",
        "reminders_list", "apps_list", "device_status", "screen_read",
    )

    @Test
    fun namesMatchTheClientTools() {
        assertEquals(AgentTools.NAMES, PhoneTools.all.map { it.name })
    }

    @Test
    fun everyParameterIsDeclared() {
        PhoneTools.all.forEach { tool ->
            assertEquals(tool.name, inputs.getValue(tool.name), tool.inputSchema.properties.orEmpty().keys)
        }
    }

    @Test
    fun requiredIsASubsetOfProperties() {
        PhoneTools.all.forEach { tool ->
            val keys = tool.inputSchema.properties.orEmpty().keys
            tool.inputSchema.required.orEmpty().forEach { assertTrue("${tool.name}.$it", it in keys) }
        }
    }

    @Test
    fun readOnlyHintsMatch() {
        PhoneTools.all.forEach { tool ->
            assertEquals(tool.name, tool.name in readOnly, tool.annotations?.readOnlyHint)
        }
    }

    @Test
    fun openWorldAndDestructiveHintsMatch() {
        val openWorld = setOf("sms_send", "phone_call", "notification_reply", "screen_act")
        val destructive = setOf("reminder_cancel", "screen_act")
        PhoneTools.all.forEach { tool ->
            assertEquals(tool.name, tool.name in openWorld, tool.annotations?.openWorldHint)
            assertEquals(tool.name, tool.name in destructive, tool.annotations?.destructiveHint)
        }
    }

    @Test
    fun approvalOffersEveryPermission() {
        val type = PhoneTools.approval.inputSchema.properties.orEmpty().getValue("action_type")
        assertEquals(PermAction.entries.map { it.wire }, type.jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("action_type", "details"), PhoneTools.approval.inputSchema.required)
    }

    @Test
    fun searchesAreTheSearchToolsWithTheInputsTheyRead() {
        assertEquals(SearchTools.NAMES.keys.toList(), PhoneTools.searches.map { it.name })
        val inputs = mapOf(
            "web_search" to setOf("q", "count", "freshness"),
            "news_search" to setOf("q", "count", "freshness"),
            "image_search" to setOf("q", "count"),
            "video_search" to setOf("q", "count", "freshness"),
            "place_search" to setOf("q", "location", "count"),
            "answers" to setOf("q"),
            "llm_context" to setOf("q", "count", "freshness", "maximum_number_of_tokens"),
        )
        PhoneTools.searches.forEach { tool ->
            assertEquals(tool.name, inputs.getValue(tool.name), tool.inputSchema.properties.orEmpty().keys)
            assertEquals(tool.name, true, tool.annotations?.readOnlyHint)
        }
    }

    @Test
    fun screenActionsAreTheOnesScreenOperatorTakes() {
        val action = PhoneTools.all.single { it.name == "screen_act" }.inputSchema.properties.orEmpty().getValue("action")
        val values = action.jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("tap", "type", "scroll_down", "scroll_up", "back", "home", "recents", "notifications"), values)
    }
}
