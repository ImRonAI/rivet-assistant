package com.hiron.rivet.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotDirectorTest {

    @Test
    fun argumentsAreJsonEncodedNeverSpliced() {
        val hostile = "\");alert(1);//"
        val script = RobotCommand("say", listOf(hostile)).script()
        val prefix = "window.rivet && window.rivet.say("
        assertTrue(script.startsWith(prefix) && script.endsWith(");"))
        // The whole argument is one JSON string literal that decodes back to the input.
        val literal = script.removePrefix(prefix).removeSuffix(");")
        assertEquals(hostile, org.json.JSONArray("[$literal]").getString(0))
    }

    @Test
    fun mixedArgumentsEncode() {
        val script = RobotCommand("mouth", listOf("hello", 0.5, null, true)).script()
        assertEquals("""window.rivet && window.rivet.mouth("hello",0.5,null,true);""", script)
    }

    @Test
    fun snapshotReplaysTraceAndState() {
        val state = RivetState(activity = Activity.Searching, trace = listOf(TraceStep("search", "q", "cy")))
        val commands = RobotDirector.diff(null, state)
        assertEquals(listOf("trace", "state"), commands.map { it.method })
        assertEquals("searching", commands.last().args.single())
    }

    @Test
    fun unchangedStateSendsNothing() {
        val state = RivetState(activity = Activity.Thinking)
        assertTrue(RobotDirector.diff(state, state.copy(log = listOf(LogEntry(1, "x", "y")))).isEmpty())
    }

    @Test
    fun smsCallShowsTheThreadWithTheRecipient() {
        val commands = RobotDirector.forDeviceCall("sms_send", JSONObject().put("to", "Mom").put("body", "On my way"))
        assertEquals(listOf("thread", "state"), commands.map { it.method })
        assertTrue(commands.first().script().contains("On my way"))
        assertTrue(commands.first().script().contains("\"Mom\""))
    }

    @Test
    fun emailCallFillsTheEmailCard() {
        val commands = RobotDirector.forDeviceCall("email_draft", JSONObject().put("to", "a@b.co").put("subject", "Hi").put("body", "x".repeat(200)))
        val card = commands.first().args.single() as JSONObject
        assertEquals("a@b.co", card.getString("to"))
        assertEquals(3, card.getJSONArray("lines").length())
    }
}
