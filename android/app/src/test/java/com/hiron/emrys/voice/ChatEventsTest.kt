package com.hiron.emrys.voice

import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.Reply
import com.hiron.emrys.core.ToolState
import com.hiron.emrys.core.ToolStep
import com.hiron.emrys.ui.ChatPage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat built from real sessions with the owner's agents: every server message the SDK handed `onMessage`, as
 * recorded on the emulator (src/test/resources/chat, one `{t, source, message}` per line), replayed in order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatEventsTest {
    private fun replay(file: String, voice: Boolean): EmrysState {
        val lines = checkNotNull(javaClass.getResource("/chat/$file")).readText().lines().filter { it.isNotBlank() }
        return lines.map(::JSONObject).filter { it.getString("source") == "ai" }.fold(EmrysState()) { state, line ->
            ChatEvents.apply(state, JSONObject(line.getString("message")), line.getLong("t"), voice)
        }
    }

    @Test
    fun aPhoneToolAndTheReplyAfterIt() {
        val state = replay("text-device-status.jsonl", voice = false)
        val turn = state.messages.single()
        assertFalse(turn.fromUser)
        val (step, reply) = turn.parts
        step as ToolStep
        assertEquals("toolu_vrtx_01Nj1ufoqqQhzdBLBQop6DMt", step.id)
        assertEquals("device_status", step.name)
        assertEquals("client", step.type)
        assertEquals(ToolState.Done, step.state)
        reply as Reply
        assertEquals("Your phone is at 100%, and it's still plugged in.", reply.text)
        assertFalse(reply.streaming)
        assertEquals(reply.text, turn.text)
        assertEquals(Activity.Done, state.activity)
    }

    @Test
    fun reasoningAndMcpCallsInTheOrderTheyHappened() {
        val state = replay("text-reasoning-mcp.jsonl", voice = false)
        val turn = state.messages.single()
        // The session ended while the agent was still thinking: its last segment is open.
        val reasoning = checkNotNull(turn.reasoning)
        assertTrue(reasoning.streaming)
        assertTrue(reasoning.spentMs > 0)
        assertTrue(reasoning.text.contains("\n\n"))

        assertEquals(listOf("tool", "reply", "tool", "tool"), turn.parts.map { if (it is ToolStep) "tool" else "reply" })
        val steps = turn.parts.filterIsInstance<ToolStep>()
        assertEquals(listOf("oubliai-telnyx_search", "oubliai-telnyx_search", "oubliai-telnyx_get_schema"), steps.map { it.name })
        // Each agent request met its MCP call, though the two name the call by different ids.
        assertEquals(
            listOf("tlcal_3601m6c38pv6frrrdzv2xeh73byp", "tlcal_7101m6c392eces58e5f4qkw590n8", "tlcal_2501m6c3a4mgfpsb8j7zb8v8n1dk"),
            steps.map { it.mcpId },
        )
        assertTrue(steps.all { it.type == "mcp" && it.state == ToolState.Done && it.search == null })
        val reply = turn.parts[1] as Reply
        assertTrue(reply.text.startsWith("The best match is a lighter version"))
        assertFalse(reply.streaming)
        assertEquals(Activity.Thinking, state.activity)
    }

    @Test
    fun aVoiceSessionShowsWhatWasSaidWithoutAudioTags() {
        val state = replay("voice-mcp.jsonl", voice = true)
        assertEquals(listOf(false, true, false, true, false, true, false), state.messages.map { it.fromUser })
        val first = state.messages.first()
        assertEquals(listOf("reply", "tool", "reply"), first.parts.map { if (it is ToolStep) "tool" else "reply" })
        assertEquals("What's up,", (first.parts[0] as Reply).text)
        val step = first.parts[1] as ToolStep
        assertEquals("emrys-computer_list_directory", step.name)
        assertEquals("tlcal_4201m6c3q0fme7n80qnxghr5ja9f", step.mcpId)
        assertEquals(ToolState.Done, step.state)
        assertEquals(
            "Looking at your workspace, there is only one folder named jobs. Let me know if you would like me to check inside it or do anything else.",
            (first.parts[2] as Reply).text,
        )
        assertTrue(state.messages.none { m -> m.parts.any { it is Reply && (it.streaming || it.text.contains('[')) } })
    }

    @Test
    fun thePageGetsEveryStepWithItsLabel() {
        val chat = JSONObject(ChatPage.payload(replay("text-reasoning-mcp.jsonl", voice = false)))
        val turn = chat.getJSONArray("messages").getJSONObject(0)
        assertEquals("assistant", turn.getString("from"))
        assertTrue(turn.getJSONObject("reasoning").getBoolean("streaming"))
        val parts = turn.getJSONArray("parts").let { a -> List(a.length()) { a.getJSONObject(it) } }
        assertEquals(listOf("tool", "text", "tool", "tool"), parts.map { it.getString("type") })
        val tools = parts.filter { it.getString("type") == "tool" }
        assertEquals(listOf("Search", "Search", "Get schema"), tools.map { it.getString("label") })
        assertTrue(tools.all { it.getString("kind") == "tool" && it.getString("state") == "done" })
        // The reply's text is out and the agent is thinking again, so the page shows no separate "Thinking" line.
        assertFalse(chat.getBoolean("working"))

        val device = JSONObject(ChatPage.payload(replay("text-device-status.jsonl", voice = false)))
        val step = device.getJSONArray("messages").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("Device status", step.getString("label"))
    }
}
