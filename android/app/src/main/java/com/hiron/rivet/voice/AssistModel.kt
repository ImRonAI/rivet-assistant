package com.hiron.rivet.voice

import android.speech.SpeechRecognizer
import com.hiron.rivet.core.Activity
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.ui.AssistPhase
import com.hiron.rivet.ui.AssistUi

/**
 * What the assistant overlay knows that [RivetState] does not: the microphone, the phone's lock, and which
 * request is this overlay's own.
 */
data class AssistLocal(
    val listening: Boolean = false,
    /** The words heard so far. */
    val partial: String = "",
    /** The turn this overlay sent, once it has sent one. */
    val turnId: String? = null,
    val asked: String = "",
    /** A nudge to try again, with nothing wrong. */
    val hint: String? = null,
    /** Why the overlay could not listen or send. */
    val problem: String? = null,
    val needsMic: Boolean = false,
    val locked: Boolean = false,
    val typing: Boolean = false,
    /** Microphone level, 0..1. */
    val level: Float = 0f,
)

/** The overlay's pure logic: what the recognizer reports, and what the panel shows for it and for Rivet's state. */
object AssistModel {
    const val DIDNT_CATCH = "I didn't catch that. Tap the mic and try again."
    const val NO_RECOGNIZER = "This phone has no speech recognizer Rivet can use. Type instead."
    const val SET_UP_FIRST = "Rivet isn't connected to its brain yet. Open Rivet and finish setup in Settings."
    const val MIC_NOTE = "Rivet needs the microphone to hear you here. Allow it once in the app, or type instead."
    const val LOCKED_NOTE = "Unlock your phone to talk to Rivet. It can read and send your messages, so it waits until the phone is yours."

    fun ui(local: AssistLocal, state: RivetState): AssistUi {
        val base = AssistUi(AssistPhase.Ready, typing = local.typing, status = "READY")
        return when {
            local.locked -> AssistUi(AssistPhase.Locked, status = "LOCKED", note = LOCKED_NOTE)
            local.needsMic -> base.copy(phase = AssistPhase.NeedsMic, status = "MIC OFF", note = MIC_NOTE)
            local.listening -> base.copy(phase = AssistPhase.Listening, heard = local.partial, status = "LISTENING", level = local.level)
            local.problem != null -> base.copy(phase = AssistPhase.Failed, heard = local.asked.ifBlank { local.partial }, status = "DIDN'T FINISH", note = local.problem)
            local.turnId == null -> base.copy(note = local.hint)
            else -> turn(local.turnId, local.asked, state, base)
        }
    }

    private fun turn(turnId: String, asked: String, state: RivetState, base: AssistUi): AssistUi {
        val at = state.messages.indexOfFirst { it.id == "$turnId:user" }
        val reply = if (at < 0) null else state.messages.drop(at + 1).firstOrNull { !it.fromUser }
        val waiting = state.approvals.count { it.turnId == turnId }
        val finished = state.activeTurn != turnId
        val shown = base.copy(heard = asked, reply = reply?.text.orEmpty(), waiting = waiting)
        return when {
            reply?.failed == true -> shown.copy(phase = AssistPhase.Failed, reply = "", status = "DIDN'T FINISH", note = reply.text)
            finished && state.activity == Activity.Failed ->
                shown.copy(phase = AssistPhase.Failed, status = "DIDN'T FINISH", note = "I couldn't finish that: ${state.lastError ?: "the brain stopped"}")
            waiting > 0 -> shown.copy(phase = AssistPhase.Waiting, status = "WAITING")
            reply?.streaming == true -> shown.copy(phase = AssistPhase.Replying, status = "REPLYING")
            finished -> shown.copy(phase = AssistPhase.Done, status = "DONE")
            else -> shown.copy(phase = AssistPhase.Working, status = working(state))
        }
    }

    private fun working(state: RivetState): String = when (state.connection) {
        Connection.Offline -> "OFFLINE · QUEUED"
        Connection.Connecting -> "CONNECTING"
        Connection.Online -> when (state.activity) {
            Activity.Searching -> "SEARCHING"
            Activity.Working -> "WORKING"
            Activity.Texting -> "TEXTING"
            Activity.Emailing -> "EMAILING"
            Activity.Calling -> "CALLING"
            else -> "THINKING"
        }
    }

    /** Keeps the last words heard when a partial result comes back empty. */
    fun partial(local: AssistLocal, results: List<String>?): AssistLocal =
        results?.firstOrNull { it.isNotBlank() }?.let { local.copy(partial = it.trim()) } ?: local

    /** The request to send: the final result, or the last partial one if the recognizer ended without one. */
    fun said(local: AssistLocal, results: List<String>?): String =
        results?.firstOrNull { it.isNotBlank() }?.trim() ?: local.partial.trim()

    /** What the overlay shows after a [SpeechRecognizer] error code. */
    fun misheard(local: AssistLocal, error: Int): AssistLocal {
        val after = AssistLocal(typing = local.typing)
        return when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> after.copy(needsMic = true)
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> after.copy(hint = DIDNT_CATCH)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                after.copy(problem = "Speech recognition can't reach its server. Check your connection, or type instead.")
            SpeechRecognizer.ERROR_AUDIO -> after.copy(problem = "The microphone isn't available right now. Type instead.")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> after.copy(problem = "The speech recognizer is busy. Try again in a moment.")
            else -> after.copy(problem = "Speech recognition stopped (error $error). Try again, or type instead.")
        }
    }

    /** Maps the recognizer's RMS dB (about -2 in silence to 10 when loud) to 0..1. */
    fun level(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
}
