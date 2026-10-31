package com.hiron.emrys.voice

import com.hiron.emrys.core.emrys
import com.hiron.emrys.device.ScreenOperator
import com.hiron.emrys.device.ToolResult
import io.elevenlabs.ClientToolResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The phone tools as ElevenLabs client tools: they run straight away and hand their results to the agent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentToolsTest {
    @Test
    fun registersEveryPhoneTool() {
        assertEquals(RuntimeEnvironment.getApplication().emrys.tools.names, AgentTools.NAMES.toSet())
        assertEquals(AgentTools.NAMES.toSet(), AgentTools.build { _, _ -> ToolResult.ok(null) }.keys)
    }

    @Test
    fun everyToolRunsWithoutAGateAndMapsResultsAndParameters() = runBlocking {
        val ran = mutableListOf<Pair<String, JSONObject>>()
        val built = AgentTools.build { name, input -> ran += name to input; ToolResult.ok(JSONObject().put("count", 2)) }
        val ok = built.getValue("sms_list").execute(mapOf("contact" to "Ada", "limit" to java.math.BigDecimal("5")))
        assertEquals(ClientToolResult.success("""{"count":2}"""), ok)
        assertEquals("Ada", ran.single().second.getString("contact"))
        assertEquals(5, ran.single().second.getInt("limit"))

        built.getValue("sms_send").execute(mapOf("to" to "+18015550100", "body" to "On my way"))
        built.getValue("screen_act").execute(mapOf("action" to "tap", "node" to 1, "label" to "Place order"))
        assertEquals(listOf("sms_list", "sms_send", "screen_act"), ran.map { it.first })

        val failing = AgentTools.build { _, _ -> ToolResult.error("Emrys doesn't have permission for this on the phone (READ_SMS).") }
        val failed = requireNotNull(failing.getValue("sms_list").execute(emptyMap()))
        assertFalse(failed.success)
        assertEquals("Emrys doesn't have permission for this on the phone (READ_SMS).", failed.error)

        val broken = AgentTools.build { _, _ -> throw IllegalStateException("no SIM") }
        assertEquals(ClientToolResult.failure("This action failed on the phone: no SIM"), broken.getValue("phone_call").execute(emptyMap()))

        val nested = AgentTools.json(mapOf("a" to listOf(1.5, "x"), "b" to mapOf("c" to true)))
        assertEquals(JSONArray().put(1.5).put("x").toString(), nested.getJSONArray("a").toString())
        assertTrue(nested.getJSONObject("b").getBoolean("c"))
    }

    @Test
    fun theTappedLabelMustBeTheWholeLabelThatWasRead() {
        assertTrue(ScreenOperator.sameLabel("Place order · $23.10", " place order · $23.10 "))
        assertFalse(ScreenOperator.sameLabel("Place order · $23.10", "Place"))
        assertFalse(ScreenOperator.sameLabel("Send", "S"))
        assertFalse(ScreenOperator.sameLabel("Pay $23.10", "$23.10"))
        assertFalse(ScreenOperator.sameLabel("Send", null))
    }

    @Test
    fun alignmentBecomesTimedWords() {
        val alignment = mapOf(
            "event_id" to 19.0,
            "alignment" to mapOf(
                "chars" to listOf("H", "i", " ", "A", "d", "a"),
                "char_start_times_ms" to listOf(0.0, 100.0, 180.0, 200.0, 260.0, 330.0),
                "char_durations_ms" to listOf(100.0, 80.0, 20.0, 60.0, 70.0, 90.0),
            ),
        )
        assertEquals(
            listOf(AgentTools.Spoken("Hi", 0.0, 0.18), AgentTools.Spoken("Ada", 200.0, 0.22)),
            AgentTools.words(alignment).map { it.copy(seconds = Math.round(it.seconds * 100) / 100.0) },
        )
        assertTrue(AgentTools.words(mapOf("chars" to "nope")).isEmpty())
    }
}
