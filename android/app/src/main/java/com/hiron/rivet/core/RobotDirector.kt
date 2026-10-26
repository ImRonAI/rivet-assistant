package com.hiron.rivet.core

import org.json.JSONArray
import org.json.JSONObject

/** One call into the robot page's `window.rivet` API. */
data class RobotCommand(val method: String, val args: List<Any?>) {
    /** JavaScript that performs the call; arguments are JSON-encoded, never spliced as code. */
    fun script(): String {
        val encoded = args.joinToString(",") { arg ->
            when (arg) {
                null -> "null"
                is String -> JSONObject.quote(arg)
                is Number, is Boolean -> arg.toString()
                is JSONObject, is JSONArray -> arg.toString()
                else -> JSONObject.quote(arg.toString())
            }
        }
        return "window.rivet && window.rivet.$method($encoded);"
    }
}

/** Turns state changes and device calls into robot commands, so the robot mirrors what Rivet is doing. */
object RobotDirector {
    private val STATE = mapOf(
        Activity.Idle to "idle", Activity.Listening to "listening", Activity.Thinking to "thinking",
        Activity.Searching to "searching", Activity.Working to "working", Activity.Texting to "texting",
        Activity.Emailing to "emailing", Activity.Calling to "calling", Activity.Waiting to "waiting",
        Activity.Done to "done", Activity.Failed to "failed",
    )

    fun diff(before: RivetState?, after: RivetState): List<RobotCommand> {
        val commands = mutableListOf<RobotCommand>()
        if (before?.trace != after.trace && after.trace.isNotEmpty()) {
            val steps = JSONArray()
            after.trace.forEach { steps.put(JSONObject().put("tag", it.tag).put("text", it.text).put("tone", it.tone)) }
            commands += RobotCommand("trace", listOf(steps))
        }
        if (before?.activity != after.activity) {
            commands += RobotCommand("state", listOf(STATE.getValue(after.activity)))
        }
        return commands
    }

    /** What the phone card or email card should show while a device tool runs. */
    fun forDeviceCall(tool: String, input: JSONObject): List<RobotCommand> = when (tool) {
        "sms_send" -> listOf(
            RobotCommand("thread", listOf(
                JSONArray().put(JSONObject().put("me", true).put("text", input.optString("body"))),
                input.optString("to"),
            )),
            RobotCommand("state", listOf("texting")),
        )
        "notification_reply" -> listOf(
            RobotCommand("thread", listOf(
                JSONArray().put(JSONObject().put("me", true).put("text", input.optString("text"))),
                input.optString("app", "Reply"),
            )),
            RobotCommand("state", listOf("texting")),
        )
        "email_draft" -> listOf(
            RobotCommand("email", listOf(
                JSONObject()
                    .put("to", input.optString("to"))
                    .put("subject", input.optString("subject"))
                    .put("lines", JSONArray(input.optString("body").chunked(44).take(3))),
            )),
            RobotCommand("state", listOf("emailing")),
        )
        "phone_call" -> listOf(RobotCommand("state", listOf("calling")))
        else -> emptyList()
    }
}
