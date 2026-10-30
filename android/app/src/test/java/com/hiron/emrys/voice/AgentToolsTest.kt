package com.hiron.emrys.voice

import com.hiron.emrys.core.AlertBlock
import com.hiron.emrys.core.ApprovalRequest
import com.hiron.emrys.core.Decision
import com.hiron.emrys.core.GatewayConfig
import com.hiron.emrys.core.NoAllow
import com.hiron.emrys.core.Protocol
import com.hiron.emrys.core.RuleSet
import com.hiron.emrys.core.RuleTarget
import com.hiron.emrys.core.StandingRule
import com.hiron.emrys.core.Tier
import com.hiron.emrys.device.ScreenOperator
import com.hiron.emrys.device.ToolResult
import io.elevenlabs.ClientToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val brain = File("../../brain/src/emrys_brain/device_tools.py").readText()

    private fun brainWords(name: String): List<String> {
        val block = Regex("""^$name = (?:frozenset\(\{|\()(.*?)[})]""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(brain)?.groupValues?.get(1) ?: error("no $name in device_tools.py")
        return Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    private class Calls {
        val ran = mutableListOf<Pair<String, JSONObject>>()
        val asked = mutableListOf<ApprovalRequest>()
        val rules = mutableListOf<Pair<Decision, RuleTarget>>()
        val notes = mutableListOf<String>()
    }

    private val yes: suspend (ApprovalRequest) -> Protocol.Answer = { Protocol.Answer(true) }
    private val no: suspend (ApprovalRequest) -> Protocol.Answer = { Protocol.Answer(false) }

    private fun tools(
        answer: suspend (ApprovalRequest) -> Protocol.Answer,
        result: ToolResult = ToolResult.ok("done"),
        rules: RuleSet = RuleSet.EMPTY,
        taint: AgentTaint = AgentTaint(),
    ): Pair<Calls, Map<String, io.elevenlabs.ClientTool>> {
        val calls = Calls()
        val built = AgentTools.build(
            run = { name, input -> calls.ran += name to input; result },
            approve = { calls.asked += it; answer(it) },
            rules = { rules },
            setRule = { decision, target -> calls.rules += decision to target },
            taint = taint,
            noted = { calls.notes += it },
        )
        return calls to built
    }

    private val dana = RuleTarget("sms_send", mapOf("to" to "+18015550100"))
    private val text = mapOf("to" to "+1 (801) 555-0100", "body" to "On my way")

    private fun rule(decision: Decision) = RuleSet(1, listOf(StandingRule("sms_send?to=%2B18015550100", dana, decision, "Texts to +18015550100", 0.0)))

    @Test
    fun aStandingDenyRefusesWithoutAsking() = runBlocking {
        val (calls, built) = tools({ fail("asked"); Protocol.Answer(false) }, rules = rule(Decision.Deny))
        assertEquals(ClientToolResult.failure(AgentTools.standingDeny("Texts to +18015550100")), built.getValue("sms_send").execute(text))
        assertTrue(calls.ran.isEmpty())
        assertEquals(listOf("Declined by your rule: Texts to +18015550100"), calls.notes)
        assertTrue(AgentTools.standingDeny("x").startsWith("The user set Emrys to always decline this (x), so it was not done."))
    }

    @Test
    fun aStandingAllowRunsUntilTheTurnReadsSomethingUntrusted() = runBlocking {
        val taint = AgentTaint()
        val (calls, built) = tools(no, rules = rule(Decision.Allow), taint = taint)
        taint.asked("text Dana I'm on my way")
        assertEquals(ClientToolResult.success("done"), built.getValue("sms_send").execute(text))
        assertTrue(calls.asked.isEmpty())
        assertEquals("Allowed by your rule: Texts to +18015550100", calls.notes.single())

        built.getValue("sms_list").execute(emptyMap())
        assertTrue(taint.tainted)
        assertEquals(ClientToolResult.failure(AgentTools.DECLINED), built.getValue("sms_send").execute(text))
        val asked = calls.asked.single()
        assertTrue(asked.untrustedTurn)
        assertEquals(NoAllow.Untrusted, asked.offer?.noAllow)
        assertFalse(asked.offer?.allow ?: true)

        taint.asked("and text her again")
        assertEquals(ClientToolResult.success("done"), built.getValue("sms_send").execute(text))
        assertEquals(1, calls.asked.size)
    }

    @Test
    fun aTickedAnswerAsksTheBrainForTheRule() = runBlocking {
        val (allowing, allow) = tools({ Protocol.Answer(true, Decision.Allow) })
        assertEquals(ClientToolResult.success("done"), allow.getValue("sms_send").execute(text))
        assertEquals(listOf(Decision.Allow to dana), allowing.rules)

        val (denying, deny) = tools({ Protocol.Answer(false, Decision.Deny) })
        assertEquals(ClientToolResult.failure(AgentTools.standingDeny("Texts to +18015550100")), deny.getValue("sms_send").execute(text))
        assertEquals(listOf(Decision.Deny to dana), denying.rules)
        assertTrue(denying.ran.isEmpty())
    }

    @Test
    fun aPaymentTapIsNeverOfferedAsAnAllow() {
        val pay = requireNotNull(AgentAlerts.request("screen_act", JSONObject().put("action", "tap").put("node", 1).put("label", "Pay now"), false, null))
        assertEquals(Tier.Purchase, pay.tier)
        assertEquals("Tap “Pay now” in the app on screen", pay.headline)
        val offer = requireNotNull(pay.offer)
        assertFalse(offer.allow)
        assertEquals(NoAllow.Purchase, offer.noAllow)
        assertEquals("Tapping “Pay now” in apps", offer.label)
        val send = requireNotNull(AgentAlerts.request("screen_act", JSONObject().put("action", "tap").put("node", 1).put("label", "Send"), false, null))
        assertEquals(NoAllow.Kind, send.offer?.noAllow)
    }

    @Test
    fun alertsSayWhatTheBrainSays() {
        val sms = requireNotNull(AgentAlerts.request("sms_send", JSONObject(text), false, "text Dana I'm on my way"))
        assertEquals("Text +1 (801) 555-0100: “On my way”", sms.headline)
        assertEquals("You asked: “text Dana I'm on my way”", sms.because)
        assertEquals("Texts to +18015550100", sms.offer?.label)
        val call = requireNotNull(AgentAlerts.request("phone_call", JSONObject().put("number", "+18015550100"), false, null))
        assertEquals("Call +18015550100", call.headline)
        assertEquals(null, call.because)
        val event = requireNotNull(AgentAlerts.request("calendar_create", JSONObject().put("title", "Dentist").put("start", "2026-10-27T09:00"), false, null))
        assertEquals("Add “Dentist” to your calendar", event.headline)
        assertEquals(AlertBlock.Field("When", "Tue Oct 27, 9:00 AM"), event.blocks.first())
        assertEquals("Adding events to your calendar", event.offer?.label)
        val reply = requireNotNull(AgentAlerts.request("notification_reply", JSONObject().put("key", "k").put("text", "ok"), false, null))
        assertEquals("Reply to a message: “ok”", reply.headline)
        assertNull(reply.offer)
        assertEquals("a".repeat(80) + "…", AgentAlerts.clip("a".repeat(100)))
        assertEquals(("word ".repeat(16)).trimEnd() + "…", AgentAlerts.clip("word ".repeat(30)))
        // The brain's copy, from brain/src/emrys_brain/alerts.py, so both paths read the same.
        val alerts = File("../../brain/src/emrys_brain/alerts.py").readText()
        for (line in listOf(
            "Your phone places the call now. You do the talking.", "Sent from the app the message came from.",
            "Emrys checks the button still says this before it taps.", "Emrys can't tell what this does, so it asks first.",
            "in the app on screen", "to your calendar",
        )) {
            assertTrue(line, line in alerts)
        }
    }

    @Test
    fun eventTimesReadAsTheBrainReadsThem() {
        // brain/tests/test_alerts.py test_shared_time_vectors
        val vectors = JSONArray(File("../../brain/tests/fixtures/alert_times.json").readText())
        assertTrue(vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            assertEquals(v.toString(), v.getString("text"), AgentAlerts.eventTime(v.getString("start"), v.getString("end")))
        }
        val event = AgentAlerts.alert("calendar_create", JSONObject().put("title", "x").put("start", "2026-10-27T09:00").put("end", "2026-10-27T10:00"))
        assertEquals(AlertBlock.Field("When", "Tue Oct 27, 9:00 AM to 10:00 AM"), event.second.first())
        assertEquals("Call True", AgentAlerts.alert("phone_call", JSONObject().put("number", true)).first)
    }

    @Test
    fun registersEveryBrainPhoneToolButTheAnsweringNumber() {
        val specs = Regex("""DeviceToolSpec\(\s*"([a-z_]+)"""").findAll(brain).map { it.groupValues[1] }.toList()
        assertEquals(18, specs.size)
        assertEquals(specs.toSet(), AgentTools.NAMES.toSet())
        val (_, built) = tools(yes)
        assertEquals(AgentTools.NAMES.toSet(), built.keys)
        assertFalse("call_answering_number" in built)
    }

    @Test
    fun mapsResultsAndParameters() = runBlocking {
        val (calls, built) = tools(yes, ToolResult.ok(JSONObject().put("count", 2)))
        val ok = built.getValue("sms_list").execute(mapOf("contact" to "Ada", "limit" to java.math.BigDecimal("5")))
        assertEquals(ClientToolResult.success("""{"count":2}"""), ok)
        val input = calls.ran.single().second
        assertEquals("Ada", input.getString("contact"))
        assertEquals(5, input.getInt("limit"))

        val (_, failing) = tools(yes, ToolResult.error("Emrys doesn't have permission for this on the phone (READ_SMS)."))
        val failed = failing.getValue("sms_list").execute(emptyMap())
        assertFalse(failed!!.success)
        assertEquals("Emrys doesn't have permission for this on the phone (READ_SMS).", failed.error)

        val nested = AgentTools.json(mapOf("a" to listOf(1.5, "x"), "b" to mapOf("c" to true)))
        assertEquals(JSONArray().put(1.5).put("x").toString(), nested.getJSONArray("a").toString())
        assertTrue(nested.getJSONObject("b").getBoolean("c"))
    }

    @Test
    fun approvalGatedToolsRefuseOnDenyOrError() = runBlocking {
        val gated = mapOf(
            "sms_send" to mapOf("to" to "+18015550100", "body" to "On my way"),
            "phone_call" to mapOf("number" to "+18015550100"),
            "notification_reply" to mapOf("key" to "k", "text" to "ok"),
            "calendar_create" to mapOf("title" to "Dentist", "start" to "2026-10-27T09:00"),
            "screen_act" to mapOf("action" to "tap", "node" to 1, "label" to "Place order"),
        )
        val (denied, deny) = tools(no)
        for ((name, input) in gated) {
            assertEquals(name, ClientToolResult.failure(AgentTools.DECLINED), deny.getValue(name).execute(input))
        }
        assertEquals(gated.keys.toList(), denied.asked.map { it.tool })
        assertTrue(denied.ran.isEmpty())
        assertTrue(denied.asked.all { it.lane == AgentTools.LANE && !it.untrustedTurn })
        assertEquals("Text +18015550100: “On my way”", denied.asked.first().headline)
        assertEquals(Tier.Send, denied.asked.first().tier)
        assertTrue(denied.asked.last().high)

        val (broken, failing) = tools({ throw IllegalStateException("sheet gone") })
        val result = failing.getValue("sms_send").execute(gated.getValue("sms_send"))!!
        assertFalse(result.success)
        assertTrue(broken.ran.isEmpty())

        val (approved, allow) = tools(yes)
        assertEquals(ClientToolResult.success("done"), allow.getValue("sms_send").execute(gated.getValue("sms_send")))
        assertEquals(listOf("sms_send"), approved.ran.map { it.first })
    }

    @Test
    fun readToolsNavigationAndTypingRunWithoutAsking() = runBlocking {
        val (calls, built) = tools({ fail("asked"); Protocol.Answer(false) })
        built.getValue("sms_list").execute(emptyMap())
        built.getValue("app_open").execute(mapOf("app" to "Maps"))
        built.getValue("screen_act").execute(mapOf("action" to "scroll_down"))
        built.getValue("screen_act").execute(mapOf("action" to "type", "node" to 2, "label" to "Search", "text" to "pizza"))
        assertEquals(4, calls.ran.size)
        assertTrue(calls.asked.isEmpty())
    }

    @Test
    fun everyToolHasTheBrainsTier() {
        val tiers = Regex("""DeviceToolSpec\(\s*"([a-z_]+)".*?ToolPolicy\("([a-z]+)"(.*?)\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(brain).map { it.groupValues }.toList()
        assertEquals(AgentTools.NAMES.toSet(), tiers.map { it[1] }.toSet())
        for ((_, name, tier, rest) in tiers) {
            if ("assess=screen_act_tier" in rest) continue
            assertEquals(name, tier, AgentTools.tier(name, JSONObject()).wire)
        }
    }

    @Test
    fun theTappedLabelMustBeTheWholeLabelThatWasJudged() {
        assertTrue(ScreenOperator.sameLabel("Place order · $23.10", " place order · $23.10 "))
        assertFalse(ScreenOperator.sameLabel("Place order · $23.10", "Place"))
        assertFalse(ScreenOperator.sameLabel("Send", "S"))
        assertFalse(ScreenOperator.sameLabel("Pay $23.10", "$23.10"))
        assertFalse(ScreenOperator.sameLabel("Send", null))
        assertEquals(Tier.Read, AgentTools.screenActTier(JSONObject().put("action", "tap").put("label", "Place")))
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
        for ((input, tier) in cases) assertEquals(input.toString(), tier, AgentTools.screenActTier(input).wire)
        assertNull(AgentAlerts.request("screen_act", JSONObject().put("action", "tap").put("label", "Inbox"), false, null))
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
