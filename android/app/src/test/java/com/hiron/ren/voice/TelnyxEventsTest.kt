package com.hiron.ren.voice

import com.hiron.ren.core.Activity
import com.hiron.ren.core.ChatMessage
import com.hiron.ren.core.RenState
import com.hiron.ren.core.Reply
import com.hiron.ren.core.ToolState
import com.hiron.ren.core.ToolStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat built from what `android/stage-ui/src/telnyx/main.ts` reports from Telnyx's client. */
class TelnyxEventsTest {
    private fun replay(state: RenState, voice: Boolean, vararg events: String): RenState =
        events.map(::JSONObject).fold(state) { s, e -> TelnyxEvents.apply(s, e, voice) }

    @Test
    fun aTypedTurnCallsAPhoneToolAndReplies() {
        val asked = RenState(messages = listOf(ChatMessage("agent:1", true, "How's my battery?")))
        val called = TelnyxEvents.called(replay(asked, false, """{"type":"user","text":"How's my battery?"}""", """{"type":"state","state":"thinking"}"""), "call_1", "device_status")
        assertEquals(Activity.Working, called.activity)
        val state = replay(
            TelnyxEvents.answered(called, "call_1", ok = true),
            false,
            """{"type":"reply","text":"It's at "}""",
            """{"type":"reply","text":"80%."}""",
            """{"type":"state","state":"listening"}""",
        )
        assertEquals(2, state.messages.size)
        val (step, reply) = state.messages.last().parts
        step as ToolStep
        assertEquals("device_status", step.name)
        assertEquals("client", step.type)
        assertEquals(ToolState.Done, step.state)
        reply as Reply
        assertEquals("It's at 80%.", reply.text)
        assertFalse(reply.streaming)
        assertEquals(Activity.Done, state.activity)
    }

    @Test
    fun aVoiceTurnShowsWhatTheUserSaidAndListensAfter() {
        val state = replay(
            RenState(),
            true,
            """{"type":"state","state":"listening"}""",
            """{"type":"user","text":"Call Mom"}""",
            """{"type":"state","state":"speaking"}""",
            """{"type":"reply","text":"Calling her now."}""",
            """{"type":"state","state":"listening"}""",
        )
        assertEquals(listOf("Call Mom", "Calling her now."), state.messages.map { it.text })
        assertTrue(state.messages.first().fromUser)
        assertEquals(Activity.Listening, state.activity)
    }

    @Test
    fun aFailedToolShowsAsFailed() {
        val state = TelnyxEvents.answered(TelnyxEvents.called(RenState(), "call_2", "sms_send"), "call_2", ok = false)
        assertEquals(ToolState.Failed, (state.messages.single().parts.single() as ToolStep).state)
    }
}
