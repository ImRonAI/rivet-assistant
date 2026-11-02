package com.hiron.emrys.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotDirectorTest {

    @Test
    fun argumentsAreJsonEncodedNeverSpliced() {
        val hostile = "\");alert(1);//"
        val script = RobotCommand("state", listOf(hostile)).script()
        val prefix = "window.emrys && window.emrys.state("
        assertTrue(script.startsWith(prefix) && script.endsWith(");"))
        // The whole argument is one JSON string literal that decodes back to the input.
        val literal = script.removePrefix(prefix).removeSuffix(");")
        assertEquals(hostile, org.json.JSONArray("[$literal]").getString(0))
    }

    @Test
    fun mixedArgumentsEncode() {
        val script = RobotCommand("mouth", listOf("hello", 0.5)).script()
        assertEquals("""window.emrys && window.emrys.mouth("hello",0.5);""", script)
    }

    @Test
    fun snapshotReplaysTheState() {
        val commands = RobotDirector.diff(null, EmrysState(activity = Activity.Searching))
        assertEquals(listOf("state"), commands.map { it.method })
        assertEquals("searching", commands.single().args.single())
    }

    @Test
    fun unchangedStateSendsNothing() {
        val state = EmrysState(activity = Activity.Thinking)
        assertTrue(RobotDirector.diff(state, state.copy(log = listOf(LogEntry(1, "x", "y")))).isEmpty())
    }

    @Test
    fun theRobotTakesThePhoneWhileEmrysAnswersACall() {
        val idle = EmrysState(activity = Activity.Idle)
        val call = LiveCall("c-1", "Dana", "+18015550123", true, 0, LiveCallStatus.Answering)
        val onCall = idle.copy(liveCall = call)
        assertEquals(listOf("calling"), RobotDirector.diff(idle, onCall).map { it.args.single() })
        assertTrue(RobotDirector.diff(onCall, onCall.copy(activity = Activity.Thinking)).isEmpty())
        val ended = onCall.copy(liveCall = call.copy(status = LiveCallStatus.Ended))
        assertEquals(listOf("idle"), RobotDirector.diff(onCall, ended).map { it.args.single() })
    }

    @Test
    fun theStageHearsWhoseTurnItIsAndTheAgentsLevel() {
        assertEquals("""window.emrys && window.emrys.voice("speaking");""", RobotDirector.voice(VoiceTurn.Speaking).script())
        assertEquals("""window.emrys && window.emrys.voice("listening");""", RobotDirector.voice(VoiceTurn.Listening).script())
        assertEquals("""window.emrys && window.emrys.voice("none");""", RobotDirector.voice(VoiceTurn.None).script())
        assertEquals("window.emrys && window.emrys.level(0.42);", RobotDirector.level(0.42f).script())
    }
}
