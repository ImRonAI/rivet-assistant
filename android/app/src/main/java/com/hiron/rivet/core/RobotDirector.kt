package com.hiron.rivet.core

import org.json.JSONObject

/** One call into the robot page's `window.rivet` API. */
data class RobotCommand(val method: String, val args: List<Any>) {
    /** JavaScript that performs the call; arguments are JSON-encoded, never spliced as code. */
    fun script(): String {
        val encoded = args.joinToString(",") { arg ->
            when (arg) {
                is String -> JSONObject.quote(arg)
                is Number -> arg.toString()
                else -> JSONObject.quote(arg.toString())
            }
        }
        return "window.rivet && window.rivet.$method($encoded);"
    }
}

/** Turns state changes into robot commands, so the robot mirrors what Rivet is doing. */
object RobotDirector {
    fun diff(before: RivetState?, after: RivetState): List<RobotCommand> =
        if (before == null || stance(before) != stance(after)) listOf(RobotCommand("state", listOf(stance(after)))) else emptyList()

    /** What the robot is doing: on the phone while Rivet's answering line has a caller, else the activity's move. */
    private fun stance(state: RivetState): String {
        val onCall = state.liveCall?.status.let { it == LiveCallStatus.Answering || it == LiveCallStatus.Transferring }
        if (onCall) return "calling"
        return when (state.activity) {
            Activity.Idle -> "idle"
            Activity.Listening -> "listening"
            Activity.Thinking -> "thinking"
            Activity.Searching -> "searching"
            Activity.Working -> "working"
            Activity.Texting -> "texting"
            Activity.Emailing -> "emailing"
            Activity.Waiting -> "waiting"
            Activity.Done -> "done"
            Activity.Failed -> "failed"
        }
    }
}
