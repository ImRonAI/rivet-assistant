package com.hiron.rivet.voice

import com.hiron.rivet.core.GatewayConfig
import com.hiron.rivet.device.ToolResult
import io.elevenlabs.ClientTool
import io.elevenlabs.ClientToolResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentToolsTest {
    // The brain's phone tools, read from its source so both sides cannot drift apart.
    private val brain = File("../../brain/src/rivet_brain/device_tools.py").readText()

    private val ran = mutableListOf<Pair<String, JSONObject>>()

    private fun tools(result: ToolResult = ToolResult.ok("done")): Map<String, ClientTool> =
        AgentTools.build { name, input -> ran += name to input; result }

    @Test
    fun registersEveryBrainPhoneToolButTheAnsweringNumber() {
        val specs = Regex("""DeviceToolSpec\(\s*"([a-z_]+)"""").findAll(brain).map { it.groupValues[1] }.toList()
        assertEquals(18, specs.size)
        assertEquals(specs.toSet(), AgentTools.NAMES.toSet())
        val built = tools()
        assertEquals(AgentTools.NAMES.toSet(), built.keys)
        assertFalse("call_answering_number" in built)
    }

    @Test
    fun everyCallRunsOnThePhoneAsItIsMade() = runBlocking {
        val sent = tools().getValue("sms_send").execute(mapOf("to" to "+18015550100", "body" to "On my way"))
        assertEquals(ClientToolResult.success("done"), sent)
        assertEquals("sms_send", ran.single().first)
        assertEquals("On my way", ran.single().second.getString("body"))
    }

    @Test
    fun mapsResultsAndParameters() = runBlocking {
        val ok = tools(ToolResult.ok(JSONObject().put("count", 2))).getValue("sms_list").execute(mapOf("contact" to "Ada", "limit" to java.math.BigDecimal("5")))
        assertEquals(ClientToolResult.success("""{"count":2}"""), ok)
        val input = ran.single().second
        assertEquals("Ada", input.getString("contact"))
        assertEquals(5, input.getInt("limit"))

        val failed = tools(ToolResult.error("Rivet doesn't have permission for this on the phone (READ_SMS).")).getValue("sms_list").execute(emptyMap())
        assertFalse(failed!!.success)
        assertEquals("Rivet doesn't have permission for this on the phone (READ_SMS).", failed.error)

        val thrown = AgentTools.build { _, _ -> error("no radio") }.getValue("phone_call").execute(emptyMap())
        assertEquals("This action failed on the phone: no radio", thrown!!.error)

        val nested = AgentTools.json(mapOf("a" to listOf(1.5, "x"), "b" to mapOf("c" to true)))
        assertEquals(JSONArray().put(1.5).put("x").toString(), nested.getJSONArray("a").toString())
        assertTrue(nested.getJSONObject("b").getBoolean("c"))
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

    @Test
    fun fetchesTheCredentialFromTheBrain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"mode":"voice","agent_id":"agent_v","conversation_token":"lk-1"}"""))
            server.enqueue(MockResponse().setBody("""{"mode":"text","agent_id":"agent_t","signed_url":"wss://x/y?s=1"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"ElevenLabs agents are not set up on the brain"}"""))
            val config = GatewayConfig(server.url("/").toString(), "device-token", "phone-1")
            val http = OkHttpClient()

            assertEquals(AgentCredential(AgentMode.Voice, "lk-1"), fetchAgentCredential(http, config, AgentMode.Voice))
            val first = server.takeRequest()
            assertEquals("/v1/agent/session?mode=voice", first.path)
            assertEquals("Bearer device-token", first.getHeader("Authorization"))

            assertEquals(AgentCredential(AgentMode.Text, "wss://x/y?s=1"), fetchAgentCredential(http, config, AgentMode.Text))
            assertEquals("/v1/agent/session?mode=text", server.takeRequest().path)

            val error = runCatching { fetchAgentCredential(http, config, AgentMode.Voice) }.exceptionOrNull()
            assertEquals(AgentStartError.NotSetUp, error)

            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            assertEquals(AgentStartError.Unauthorized, runCatching { fetchAgentCredential(http, config, AgentMode.Text) }.exceptionOrNull())
            server.enqueue(MockResponse().setBody("""{"mode":"text","agent_id":"agent_t"}"""))
            assertEquals(AgentStartError.NotSetUp, runCatching { fetchAgentCredential(http, config, AgentMode.Text) }.exceptionOrNull())
            val unset = GatewayConfig("", "", "")
            assertEquals(AgentStartError.NotConfigured, runCatching { fetchAgentCredential(http, unset, AgentMode.Text) }.exceptionOrNull())
        }
    }
}
