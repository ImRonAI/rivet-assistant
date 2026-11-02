package com.hiron.emrys.core

import org.json.JSONObject

/** One call into the robot page's `window.emrys` API. */
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
        return "window.emrys && window.emrys.$method($encoded);"
    }
}

/** Whose turn it is in an ElevenLabs voice session, for the stage's voice layer; [None] when no voice session runs. */
enum class VoiceTurn { Listening, Speaking, None }

/** Turns state changes into robot commands, so the robot mirrors what Emrys is doing. */
object RobotDirector {
    fun diff(before: EmrysState?, after: EmrysState): List<RobotCommand> =
        if (before == null || stance(before) != stance(after)) listOf(RobotCommand("state", listOf(stance(after)))) else emptyList()

    /** Tells the stage whose turn it is in a voice session (`window.emrys.voice`). */
    fun voice(turn: VoiceTurn): RobotCommand {
        val wire = when (turn) {
            VoiceTurn.Listening -> "listening"
            VoiceTurn.Speaking -> "speaking"
            VoiceTurn.None -> "none"
        }
        return RobotCommand("voice", listOf(wire))
    }

    /** Passes the agent's audio level, 0..1, as the ElevenLabs SDK reports it (`window.emrys.level`). */
    fun level(level: Float): RobotCommand = RobotCommand("level", listOf(level))

    /** What the robot is doing: on the phone while Emrys's answering line has a caller, else the activity's move. */
    private fun stance(state: EmrysState): String {
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
