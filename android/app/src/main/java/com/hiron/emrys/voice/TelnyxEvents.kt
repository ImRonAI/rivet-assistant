package com.hiron.emrys.voice

import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.EmrysStore
import com.hiron.emrys.core.Reply
import com.hiron.emrys.core.ToolState
import com.hiron.emrys.core.ToolStep
import org.json.JSONObject

/**
 * Builds the chat from what Telnyx's client reports (`android/stage-ui/src/telnyx/main.ts`), in the order it arrives:
 * the user's spoken words (`user`), the assistant's reply as it streams (`reply`), the assistant's state (`state`:
 * `thinking`, `speaking`, `listening`, which ends its turn) and the phone tools it calls. It makes the same chat
 * changes as [GeminiEvents].
 */
object TelnyxEvents {
    /** [state] after [event]; in a typed conversation the user's words are already in the chat. */
    fun apply(state: EmrysState, event: JSONObject, voice: Boolean): EmrysState = when (event.optString("type")) {
        "user" -> if (voice) GeminiEvents.heard(state, event.optString("text")) else state
        "reply" -> GeminiEvents.streamed(state, event.optString("text"))
        "state" -> when (event.optString("state")) {
            "thinking" -> state.copy(activity = Activity.Thinking)
            "speaking" -> state.copy(activity = Activity.Idle)
            "listening" -> ended(state).let { if (voice) it.copy(activity = Activity.Listening) else it }
            else -> state
        }
        else -> state
    }

    /** The assistant's turn is over: what streamed is its reply. */
    private fun ended(state: EmrysState): EmrysState {
        val last = state.messages.lastOrNull()?.parts?.lastOrNull()
        return if (last is Reply && last.streaming) GeminiEvents.spoken(state, "") else state
    }

    /** The assistant called the phone tool [name]. */
    fun called(state: EmrysState, id: String, name: String): EmrysState {
        val activity = if (name in SearchTools.NAMES) Activity.Searching else Activity.Working
        return EmrysStore.turn(state.copy(activity = activity)) { m ->
            if (m.parts.any { it is ToolStep && it.id == id }) m else m.copy(parts = m.parts + ToolStep(id, name, "client", ToolState.Running))
        }
    }

    /** The phone answered the call [id]. */
    fun answered(state: EmrysState, id: String, ok: Boolean): EmrysState = state.copy(
        messages = state.messages.map { m ->
            m.copy(parts = m.parts.map { if (it is ToolStep && it.id == id) it.copy(state = if (ok) ToolState.Done else ToolState.Failed) else it })
        },
    )
}
