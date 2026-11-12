package com.hiron.ren.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotDirectorTest {

    @Test
    fun argumentsAreJsonEncodedNeverSpliced() {
        val hostile = "\");alert(1);//"
        val script = RobotCommand("state", listOf(hostile)).script()
        val prefix = "window.ren && window.ren.state("
        assertTrue(script.startsWith(prefix) && script.endsWith(");"))
        // The whole argument is one JSON string literal that decodes back to the input.
        val literal = script.removePrefix(prefix).removeSuffix(");")
        assertEquals(hostile, org.json.JSONArray("[$literal]").getString(0))
    }

    @Test
    fun mixedArgumentsEncode() {
        val script = RobotCommand("mouth", listOf("hello", 0.5)).script()
        assertEquals("""window.ren && window.ren.mouth("hello",0.5);""", script)
    }

    @Test
    fun snapshotReplaysTheState() {
        val commands = RobotDirector.diff(null, RenState(activity = Activity.Searching))
        assertEquals(listOf("state"), commands.map { it.method })
        assertEquals("searching", commands.single().args.single())
    }

    @Test
    fun unchangedStateSendsNothing() {
        val state = RenState(activity = Activity.Thinking)
        assertTrue(RobotDirector.diff(state, state.copy(log = listOf(LogEntry(1, "x", "y")))).isEmpty())
    }

    @Test
    fun theStageHearsWhoseTurnItIsAndTheAgentsLevel() {
        assertEquals("""window.ren && window.ren.voice("speaking");""", RobotDirector.voice(VoiceTurn.Speaking).script())
        assertEquals("""window.ren && window.ren.voice("listening");""", RobotDirector.voice(VoiceTurn.Listening).script())
        assertEquals("""window.ren && window.ren.voice("none");""", RobotDirector.voice(VoiceTurn.None).script())
        assertEquals("window.ren && window.ren.level(0.42);", RobotDirector.level(0.42f).script())
    }
}
