package com.hiron.emrys.voice

import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.Reply
import com.hiron.emrys.core.ToolState
import com.hiron.emrys.core.ToolStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat built from the events `deploy/vm/gemini/app.py` sends, in the shapes Strands 1.64.0 gives them. */
class GeminiEventsTest {
    private fun replay(vararg events: String): EmrysState = events.map(::JSONObject).fold(EmrysState(), GeminiEvents::apply)

    @Test
    fun aTextTurnStreamsCallsAPhoneToolAndReplies() {
        val state = replay(
            """{"data": "Checking "}""",
            """{"data": "your battery."}""",
            """{"message": {"role": "assistant", "content": [{"text": "Checking your battery."}, {"toolUse": {"toolUseId": "t1", "name": "device_status", "input": {}}}]}}""",
            """{"message": {"role": "user", "content": [{"toolResult": {"toolUseId": "t1", "status": "success", "content": [{"text": "{}"}]}}]}}""",
            """{"data": "It's at 80%."}""",
            """{"message": {"role": "assistant", "content": [{"text": "It's at 80%."}]}}""",
            """{"stop_reason": "end_turn", "interrupts": []}""",
        )
        val turn = state.messages.single()
        val (first, step, last) = turn.parts
        assertEquals("Checking your battery.", (first as Reply).text)
        assertFalse(first.streaming)
        step as ToolStep
        assertEquals("device_status", step.name)
        assertEquals("client", step.type)
        assertEquals(ToolState.Done, step.state)
        assertEquals("It's at 80%.", (last as Reply).text)
        assertEquals("Checking your battery.\n\nIt's at 80%.", turn.text)
        assertEquals(Activity.Done, state.activity)
    }

    @Test
    fun aFailedToolAndAHandoffQuestion() {
        val state = replay(
            """{"message": {"role": "assistant", "content": [{"toolUse": {"toolUseId": "t1", "name": "shell", "input": {}}}]}}""",
            """{"message": {"role": "user", "content": [{"toolResult": {"toolUseId": "t1", "status": "error", "content": []}}]}}""",
            """{"stop_reason": "interrupt", "interrupts": ["Which repository?"]}""",
        )
        val (step, ask) = state.messages.single().parts
        step as ToolStep
        assertEquals("strands", step.type)
        assertEquals(ToolState.Failed, step.state)
        assertEquals("Which repository?", (ask as Reply).text)
    }

    @Test
    fun aVoiceTurnFromTheBidiTranscripts() {
        val state = replay(
            """{"type": "bidi_transcript_block", "transcript": "What's on today?", "role": "user", "content_id": "u1"}""",
            """{"type": "bidi_tool_use_blocks", "tool_uses": [{"toolUseId": "t1", "name": "calendar_list", "input": {}}]}""",
            """{"message": {"role": "user", "content": [{"toolResult": {"toolUseId": "t1", "status": "success", "content": []}}]}}""",
            """{"type": "bidi_transcript_delta", "delta": "Just ", "role": "assistant", "content_id": "a1"}""",
            """{"type": "bidi_transcript_delta", "delta": "lunch at noon.", "role": "assistant", "content_id": "a1"}""",
            """{"type": "bidi_transcript_block", "transcript": "Just lunch at noon.", "role": "assistant", "content_id": "a1"}""",
        )
        val (user, turn) = state.messages
        assertTrue(user.fromUser)
        assertEquals("What's on today?", user.text)
        val (step, reply) = turn.parts
        assertEquals(ToolState.Done, (step as ToolStep).state)
        assertEquals("Just lunch at noon.", (reply as Reply).text)
        assertFalse(reply.streaming)
    }
}
