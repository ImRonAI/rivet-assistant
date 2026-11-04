package com.hiron.emrys.mcp

import com.hiron.emrys.voice.AgentTools
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
    fun screenActionsAreTheOnesScreenOperatorTakes() {
        val action = PhoneTools.all.single { it.name == "screen_act" }.inputSchema.properties.orEmpty().getValue("action")
        val values = action.jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("tap", "type", "scroll_down", "scroll_up", "back", "home", "recents", "notifications"), values)
    }
}
