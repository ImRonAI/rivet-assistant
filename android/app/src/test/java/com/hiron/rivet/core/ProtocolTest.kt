package com.hiron.rivet.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun parsesApprovalRequestAsTheBrainSendsIt() {
        // Shape from brain/src/rivet_brain/approvals.py Assessment.prompt() (v2) inside supervisor._sheet().
        val raw = """
            {"type":"approval.request","session_id":"s","lane":"default","turn_id":"t-1","expires_at":1761500000.5,
             "approvals":[{"id":"rivet-approval:abc","name":"rivet-approval","because":"You asked: “Text Dana”","request":{"v":2,
             "tool":"sms_send","tier":"send","high":false,"headline":"Text +18015550100: “Friday at 9 works!”",
             "blocks":[{"kind":"field","label":"To","value":"+18015550100"},{"kind":"quote","label":"Message","text":"Friday at 9 works!"},
             {"kind":"note","markdown":"- Send a message"},{"kind":"future","x":1}],
             "preview":"{\"to\": \"+18015550100\"}",
             "offer":{"key":"sms_send?to=%2B18015550100","tool":"sms_send","scope":{"to":"+18015550100"},"label":"Texts to +18015550100","allow":true,"deny":true},
             "untrusted_turn":true}}]}
        """.trimIndent()
        val event = Protocol.parse(raw) as ServerEvent.ApprovalsRequested
        assertEquals("t-1", event.turnId)
        val a = event.approvals.single()
        assertEquals("rivet-approval:abc", a.id)
        assertEquals(Tier.Send, a.tier)
        assertFalse(a.high)
        assertTrue(a.untrustedTurn)
        assertEquals("Text +18015550100: “Friday at 9 works!”", a.headline)
        assertEquals("You asked: “Text Dana”", a.because)
        assertEquals(
            listOf(AlertBlock.Field("To", "+18015550100"), AlertBlock.Quote("Message", "Friday at 9 works!"), AlertBlock.Note("- Send a message")),
            a.blocks,
        )
        assertEquals(RuleOffer(RuleTarget("sms_send", mapOf("to" to "+18015550100")), "Texts to +18015550100", true, true, null), a.offer)
        assertEquals(1761500000.5, a.expiresAt ?: 0.0, 0.0)
    }

    @Test
    fun readsAnOlderBrainsPrompt() {
        val raw = """
            {"type":"approval.request","lane":"default","turn_id":"t-1","approvals":[{"id":"x","name":"rivet-approval","request":{"v":1,
             "tool":"telnyx_execute","tier":"purchase","high":true,"summary":"Run Telnyx operations","preview":"call_tool('CreateNumberOrder', {})",
             "remember":false,"operations":[{"name":"CreateNumberOrder","method":"POST","summary":"Create a number order"}]}}],"resent":true}
        """.trimIndent()
        val a = (Protocol.parse(raw) as ServerEvent.ApprovalsRequested).approvals.single()
        assertEquals("Run Telnyx operations", a.headline)
        assertEquals(Tier.Purchase, a.tier)
        assertTrue(a.high && a.blocks.isEmpty() && a.offer == null && a.because == null)
        assertEquals("POST", a.operations.single().method)
    }

    @Test
    fun unknownApprovalShapeFailsSafeToHighRisk() {
        val raw = """{"type":"approval.request","turn_id":"t","approvals":[{"id":"x","request":"plain text reason"}]}"""
        val a = (Protocol.parse(raw) as ServerEvent.ApprovalsRequested).approvals.single()
        assertTrue(a.high)
        assertEquals(Tier.Unknown, a.tier)
        assertEquals("plain text reason", a.headline)
        val odd = """{"type":"approval.request","turn_id":"t","approvals":[{"id":"x","request":{"tier":"spicy","headline":"h"}}]}"""
        assertEquals(Tier.Unknown, (Protocol.parse(odd) as ServerEvent.ApprovalsRequested).approvals.single().tier)
    }

    @Test
    fun parsesRuleEvents() {
        val rules = Protocol.parse(
            """{"type":"rules","revision":3,"rules":[{"key":"sms_send?to=%2B1","tool":"sms_send","scope":{"to":"+1"},"decision":"deny",
            "label":"Texts to +1","created_at":5.5},{"key":"k","tool":"x","scope":{"a":1},"decision":"deny","label":"","created_at":0},
            {"key":"k2","tool":"y","scope":{},"decision":"maybe","label":"","created_at":0}]}""",
        ) as ServerEvent.Rules
        assertEquals(RuleSet(3, listOf(StandingRule("sms_send?to=%2B1", RuleTarget("sms_send", mapOf("to" to "+1")), Decision.Deny, "Texts to +1", 5.5))), rules.set)
        assertEquals(
            ServerEvent.RulesRefused(RuleRequest.Set, "This kind of action can't be always allowed."),
            Protocol.parse("""{"type":"rules.refused","request":"set","error":"This kind of action can't be always allowed."}"""),
        )
        assertEquals(
            ServerEvent.RulesApplied(Decision.Allow, "k", "Texts to +1", "sms_send"),
            Protocol.parse("""{"type":"rules.applied","decision":"allow","key":"k","label":"Texts to +1","tool":"sms_send"}"""),
        )
        val set = JSONObject(Protocol.rulesSet(Decision.Deny, RuleTarget("screen_act", mapOf("label" to "Send"))))
        assertEquals("rules.set", set.getString("type"))
        assertEquals("deny", set.getString("decision"))
        assertEquals("Send", set.getJSONObject("scope").getString("label"))
        assertEquals("k", JSONObject(Protocol.rulesRevoke("k")).getString("key"))
        assertEquals("rules.list", JSONObject(Protocol.rulesList()).getString("type"))
    }

    @Test
    fun aStandingRuleMustMatchItsAnswer() {
        assertThrows(IllegalArgumentException::class.java) { Protocol.Answer(true, Decision.Deny) }
        assertThrows(IllegalArgumentException::class.java) { Protocol.Answer(false, Decision.Allow) }
        Protocol.Answer(true, Decision.Allow)
        Protocol.Answer(false, Decision.Deny)
    }

    @Test
    fun parsesStreamingAndDeviceEvents() {
        assertEquals(ServerEvent.TextDelta("Hi", "default"), Protocol.parse("""{"type":"text","delta":"Hi"}"""))
        assertEquals(
            ServerEvent.Searching(listOf("weather slc"), "task-1"),
            Protocol.parse("""{"type":"searching","queries":["weather slc"],"lane":"task-1"}"""),
        )
        val call = Protocol.parse("""{"type":"device.call","action_id":"tu-1","tool":"sms_send","input":{"to":"+1801","body":"hey"}}""")
            as ServerEvent.DeviceCall
        assertEquals("tu-1", call.actionId)
        assertEquals("hey", call.input.getString("body"))
        assertEquals(ServerEvent.Unknown("future.thing"), Protocol.parse("""{"type":"future.thing"}"""))
        assertTrue(Protocol.parse("not json") is ServerEvent.Error)
    }

    @Test
    fun parsesCallEventsAsTheGatewaySendsThem() {
        assertEquals(
            ServerEvent.CallAnswering("+18015550100", true, "c-0123456789ab"),
            Protocol.parse(
                """{"type":"call.answering","call_id":"c-0123456789ab","from":"+18015550100","verified":true,"session_id":"s","lane":"default"}""",
            ),
        )
        val message = Protocol.parse(
            """{"type":"call.message","call_id":"c-0123456789ab","caller_name":"Ada","callback_number":"+18015550100","message":"Lunch Friday?",
               "urgent":true,"hung_up_early":false,"at":1761500000.0}""",
        )
        assertEquals(ServerEvent.CallMessage("Ada", "+18015550100", "Lunch Friday?", true, false, "c-0123456789ab"), message)
    }

    @Test
    fun parsesLiveCallEventsAsTheGatewaySendsThem() {
        // Shapes from brain/src/rivet_brain/live_calls.py (LiveCall.live_event, _ended) and LiveCalls.take_over.
        val live = Protocol.parse(
            """{"type":"call.live","call_id":"c-0123456789ab","from":"+18015550100","verified":true,"started_at":1761500000.5,
               "status":"answering","lines":[{"speaker":"rivet","text":"Hi, you've reached Tim."},{"speaker":"caller","text":""},
               {"speaker":"caller","text":"It's Ada."}],"snapshot":false}""",
        )
        assertEquals(
            ServerEvent.CallLive(
                "c-0123456789ab", "+18015550100", true, 1761500000.5, "answering",
                listOf(CallLineText("rivet", "Hi, you've reached Tim."), CallLineText("caller", "It's Ada.")), false,
            ),
            live,
        )
        assertEquals(
            ServerEvent.CallTakeOverFailed("c-0123456789ab", "That call has already ended."),
            Protocol.parse("""{"type":"call.take_over.failed","call_id":"c-0123456789ab","error":"That call has already ended."}"""),
        )
        val ended = Protocol.parse(
            """{"type":"call.ended","call_id":"c-0123456789ab","from":"+18015550100","caller_name":"Ada",
               "started_at":1761500000.5,"ended_at":1761500125.5,"duration":125,"summary":"Ada wants lunch on Friday.",
               "outcome":"message_taken","taken_over":true}""",
        )
        assertEquals(
            ServerEvent.CallEnded(
                "c-0123456789ab", "+18015550100", "Ada", 1761500000.5, 1761500125.5, 125, "Ada wants lunch on Friday.",
                "message_taken", true,
            ),
            ended,
        )
        val unseen = Protocol.parse(
            """{"type":"call.ended","call_id":"","from":"","caller_name":"","started_at":null,"ended_at":1761500125.5,
               "duration":null,"summary":"","outcome":"unknown","taken_over":false}""",
        ) as ServerEvent.CallEnded
        assertEquals(null, unseen.startedAt)
        assertEquals(null, unseen.durationSeconds)
    }

    @Test
    fun buildsTakeOverTheGatewayAccepts() {
        val body = JSONObject(Protocol.takeOver("c-0123456789ab", "+18015550199"))
        assertEquals("call.take_over", body.getString("type"))
        assertEquals("c-0123456789ab", body.getString("call_id"))
        assertEquals("+18015550199", body.getString("to"))
        assertFalse(JSONObject(Protocol.takeOver("c-0123456789ab", null)).has("to"))
    }

    @Test
    fun buildsApprovalResponseTheSupervisorAccepts() {
        val body = JSONObject(
            Protocol.approvalResponse("default", mapOf("a" to Protocol.Answer(true, Decision.Allow), "b" to Protocol.Answer(false))),
        )
        assertEquals("approval.response", body.getString("type"))
        assertEquals("default", body.getString("lane"))
        val answers = body.getJSONObject("answers")
        assertTrue(answers.getJSONObject("a").getBoolean("approved"))
        assertEquals("allow", answers.getJSONObject("a").getString("standing"))
        assertFalse(answers.getJSONObject("b").getBoolean("approved"))
        assertFalse(answers.getJSONObject("b").has("standing"))
        assertFalse(answers.getJSONObject("a").has("remember_minutes"))
    }

    @Test
    fun buildsTurnTaskAndDeviceResult() {
        val turn = JSONObject(Protocol.turn("hi", "t1"))
        assertEquals(listOf("turn", "hi", "t1"), listOf(turn.getString("type"), turn.getString("text"), turn.getString("turn_id")))
        val result = JSONObject(Protocol.deviceResult("tu", JSONObject().put("ok", true)))
        assertEquals("tu", result.getString("action_id"))
        assertTrue(result.getJSONObject("result").getBoolean("ok"))
        assertEquals("task", JSONObject(Protocol.task("g", "id")).getString("type"))
        assertFalse(JSONObject(Protocol.task("g", "id")).has("reason"))
        assertEquals("A new WhatsApp message arrived.", JSONObject(Protocol.task("g", "id", "A new WhatsApp message arrived.")).getString("reason"))
    }

    @Test
    fun parsesSearchResultsAndModelEvents() {
        val raw = JSONObject().put("type", "search.results").put("lane", "default").put("tool_id", "tu1")
            .put("results", com.hiron.rivet.testing.SearchFixtures.places).toString()
        val event = Protocol.parse(raw) as ServerEvent.SearchResults
        assertEquals("tu1", event.toolId)
        assertEquals("default", event.lane)
        assertEquals(SearchKind.Places, event.results.kind)
        assertTrue(Protocol.parse("""{"type":"search.results","tool_id":"x","results":{"kind":"nope"}}""") is ServerEvent.Unknown)

        assertEquals(
            ServerEvent.Model(BrainModel.Groq, setOf(BrainModel.Perplexity, BrainModel.Groq)),
            Protocol.parse("""{"type":"model","model":"groq","available":["perplexity","groq","mistral"]}"""),
        )
        assertEquals(ServerEvent.Model(null, emptySet()), Protocol.parse("""{"type":"model","model":null,"available":[]}"""))
        assertEquals(
            ServerEvent.ModelRefused("Rivet is busy. Change the model when it finishes."),
            Protocol.parse("""{"type":"model.refused","error":"Rivet is busy. Change the model when it finishes."}"""),
        )
    }

    @Test
    fun buildsTurnsWithAttachmentsAndModelSet() {
        val video = ContextItem(SearchKind.Videos, "How to pick", "https://www.youtube.com/watch?v=abc", "Ten minutes", "https://www.youtube.com/watch?v=abc", null)
        val image = ContextItem(SearchKind.Images, "Lugs", "https://photos.example.com/2", null, null, "https://img/full2")
        val turn = JSONObject(Protocol.turn("", "t9", listOf(video, image)))
        assertEquals("", turn.getString("text"))
        val attached = turn.getJSONArray("attachments")
        assertEquals("videos", attached.getJSONObject(0).getString("kind"))
        assertEquals("https://www.youtube.com/watch?v=abc", attached.getJSONObject(0).getString("watch_url"))
        assertEquals("Ten minutes", attached.getJSONObject(0).getString("description"))
        assertFalse(attached.getJSONObject(0).has("image_url"))
        assertEquals("https://img/full2", attached.getJSONObject(1).getString("image_url"))
        assertFalse(attached.getJSONObject(1).has("description"))
        assertFalse(JSONObject(Protocol.turn("hi", "t1")).has("attachments"))
        val set = JSONObject(Protocol.modelSet(BrainModel.Groq))
        assertEquals("model.set" to "groq", set.getString("type") to set.getString("model"))
    }
}
