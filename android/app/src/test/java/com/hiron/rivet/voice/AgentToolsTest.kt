package com.hiron.rivet.voice

import com.hiron.rivet.core.ApprovalRequest
import com.hiron.rivet.core.GatewayConfig
import com.hiron.rivet.device.ToolResult
import io.elevenlabs.ClientToolResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AgentToolsTest {
    // The brain's phone tools, read from its source so both sides cannot drift apart.
    private val brain = File("../../brain/src/rivet_brain/device_tools.py").readText()

    private fun brainWords(name: String): List<String> {
        val block = Regex("""^$name = (?:frozenset\(\{|\()(.*?)[})]""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(brain)?.groupValues?.get(1) ?: error("no $name in device_tools.py")
        return Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    private class Calls {
        val ran = mutableListOf<Pair<String, JSONObject>>()
        val asked = mutableListOf<ApprovalRequest>()
    }

    private fun tools(answer: suspend (ApprovalRequest) -> Boolean, result: ToolResult = ToolResult.ok("done")): Pair<Calls, Map<String, io.elevenlabs.ClientTool>> {
        val calls = Calls()
        val built = AgentTools.build(
            run = { name, input -> calls.ran += name to input; result },
            approve = { calls.asked += it; answer(it) },
        )
        return calls to built
    }

    @Test
    fun registersEveryBrainPhoneToolButTheAnsweringNumber() {
        val specs = Regex("""DeviceToolSpec\(\s*"([a-z_]+)"""").findAll(brain).map { it.groupValues[1] }.toList()
        assertEquals(18, specs.size)
        assertEquals(specs.toSet(), AgentTools.NAMES.toSet())
        val (_, built) = tools({ true })
        assertEquals(AgentTools.NAMES.toSet(), built.keys)
        assertFalse("call_answering_number" in built)
    }

    @Test
    fun mapsResultsAndParameters() = runBlocking {
        val (calls, built) = tools({ true }, ToolResult.ok(JSONObject().put("count", 2)))
        val ok = built.getValue("sms_list").execute(mapOf("contact" to "Ada", "limit" to java.math.BigDecimal("5")))
        assertEquals(ClientToolResult.success("""{"count":2}"""), ok)
        val input = calls.ran.single().second
        assertEquals("Ada", input.getString("contact"))
        assertEquals(5, input.getInt("limit"))

        val (_, failing) = tools({ true }, ToolResult.error("Rivet doesn't have permission for this on the phone (READ_SMS)."))
        val failed = failing.getValue("sms_list").execute(emptyMap())
        assertFalse(failed!!.success)
        assertEquals("Rivet doesn't have permission for this on the phone (READ_SMS).", failed.error)

        val nested = AgentTools.json(mapOf("a" to listOf(1.5, "x"), "b" to mapOf("c" to true)))
        assertEquals(JSONArray().put(1.5).put("x").toString(), nested.getJSONArray("a").toString())
        assertTrue(nested.getJSONObject("b").getBoolean("c"))
    }

    @Test
    fun approvalGatedToolsRefuseOnDenyTimeoutOrError() = runBlocking {
        val gated = mapOf(
            "sms_send" to mapOf("to" to "+18015550100", "body" to "On my way"),
            "phone_call" to mapOf("number" to "+18015550100"),
            "notification_reply" to mapOf("key" to "k", "text" to "ok"),
            "calendar_create" to mapOf("title" to "Dentist", "start" to "2026-10-27T09:00"),
            "screen_act" to mapOf("action" to "tap", "node" to 1, "label" to "Place order"),
        )
        // A deny and a timeout both reach the tool as "no" from RivetRuntime.approveOnPhone.
        val (denied, deny) = tools({ false })
        for ((name, input) in gated) {
            assertEquals(name, ClientToolResult.failure(AgentTools.DECLINED), deny.getValue(name).execute(input))
        }
        assertEquals(gated.keys.toList(), denied.asked.map { it.tool })
        assertTrue(denied.ran.isEmpty())
        assertTrue(denied.asked.all { it.lane == AgentTools.LANE && !it.canRemember })
        assertEquals("Text +18015550100", denied.asked.first().summary)
        assertEquals("send", denied.asked.first().tier)
        assertTrue(denied.asked.last().high)

        val (broken, failing) = tools({ throw IllegalStateException("sheet gone") })
        val result = failing.getValue("sms_send").execute(gated.getValue("sms_send"))!!
        assertFalse(result.success)
        assertTrue(broken.ran.isEmpty())

        val (approved, allow) = tools({ true })
        assertEquals(ClientToolResult.success("done"), allow.getValue("sms_send").execute(gated.getValue("sms_send")))
        assertEquals(listOf("sms_send"), approved.ran.map { it.first })
    }

    @Test
    fun readToolsNavigationAndTypingRunWithoutAsking() = runBlocking {
        val (calls, built) = tools({ fail("asked"); false })
        built.getValue("sms_list").execute(emptyMap())
        built.getValue("app_open").execute(mapOf("app" to "Maps"))
        built.getValue("screen_act").execute(mapOf("action" to "scroll_down"))
        built.getValue("screen_act").execute(mapOf("action" to "type", "node" to 2, "label" to "Search", "text" to "pizza"))
        assertEquals(4, calls.ran.size)
        assertTrue(calls.asked.isEmpty())
    }

    @Test
    fun screenActTierMatchesTheBrain() {
        assertEquals(brainWords("_PURCHASE_WORDS"), AgentTools.PURCHASE_WORDS)
        assertEquals(brainWords("_DESTRUCTIVE_WORDS"), AgentTools.DESTRUCTIVE_WORDS)
        assertEquals(brainWords("_SEND_WORDS"), AgentTools.SEND_WORDS)
        assertEquals(brainWords("_COMMIT_WORDS"), AgentTools.COMMIT_WORDS)
        assertEquals(brainWords("_NAVIGATION").toSet(), AgentTools.NAVIGATION)
        // brain/tests/test_device_tools.py test_the_app_operator_asks_at_commit_points
        val cases = listOf(
            JSONObject().put("action", "scroll_down").put("node", 3).put("label", "Inbox") to "read",
            JSONObject().put("action", "back") to "read",
            JSONObject().put("action", "type").put("node", 2).put("label", "Search").put("text", "pizza") to "read",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Inbox") to "read",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Cancel") to "read",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Send") to "send",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Reply all") to "send",
            JSONObject().put("action", "tap").put("node", 1).put("label", "OK") to "write",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Delete conversation") to "destructive",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Cancel subscription") to "destructive",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Place order · $23.10") to "purchase",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Pay") to "purchase",
            JSONObject().put("action", "tap").put("node", 1).put("label", "Paypal account") to "read",
            JSONObject().put("action", "tap").put("node", 1) to "unknown",
            JSONObject().put("action", "explode") to "unknown",
        )
        for ((input, tier) in cases) assertEquals(input.toString(), tier, AgentTools.screenActTier(input).first)
        assertNull(AgentTools.request("screen_act", JSONObject().put("action", "tap").put("label", "Inbox")))
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

            assertEquals(AgentCredential(AgentMode.Voice, "agent_v", "lk-1"), fetchAgentCredential(http, config, AgentMode.Voice))
            val first = server.takeRequest()
            assertEquals("/v1/agent/session?mode=voice", first.path)
            assertEquals("Bearer device-token", first.getHeader("Authorization"))

            assertEquals(AgentCredential(AgentMode.Text, "agent_t", "wss://x/y?s=1"), fetchAgentCredential(http, config, AgentMode.Text))
            assertEquals("/v1/agent/session?mode=text", server.takeRequest().path)

            val error = runCatching { fetchAgentCredential(http, config, AgentMode.Voice) }.exceptionOrNull()
            assertEquals("ElevenLabs agents are not set up on the brain", error?.message)
        }
    }
}
