package com.hiron.rivet.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun parsesApprovalRequestAsTheBrainSendsIt() {
        // Shape from brain/src/rivet_brain/approvals.py Assessment.prompt() inside supervisor._sheet().
        val raw = """
            {"type":"approval.request","session_id":"s","lane":"default","turn_id":"t-1","expires_at":1761500000.5,
             "approvals":[{"id":"rivet-approval:abc","name":"rivet-approval","request":{"v":1,"tool":"telnyx_execute",
             "tier":"purchase","high":true,"summary":"Run Telnyx operations","preview":"call_tool('CreateNumberOrder', {})",
             "remember":false,"operations":[{"name":"CreateNumberOrder","method":"POST","summary":"Create a number order"}],
             "untrusted_turn":true}}]}
        """.trimIndent()
        val event = Protocol.parse(raw) as ServerEvent.ApprovalsRequested
        assertEquals("t-1", event.turnId)
        val a = event.approvals.single()
        assertEquals("rivet-approval:abc", a.id)
        assertEquals("telnyx_execute", a.tool)
        assertEquals("purchase", a.tier)
        assertTrue(a.high)
        assertTrue(a.untrustedTurn)
        assertFalse(a.canRemember)
        assertEquals("POST", a.operations.single().method)
        assertEquals(1761500000.5, a.expiresAt!!, 0.0)
    }

    @Test
    fun unknownApprovalShapeFailsSafeToHighRisk() {
        val raw = """{"type":"approval.request","turn_id":"t","approvals":[{"id":"x","request":"plain text reason"}]}"""
        val a = (Protocol.parse(raw) as ServerEvent.ApprovalsRequested).approvals.single()
        assertTrue(a.high)
        assertEquals("plain text reason", a.summary)
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
            ServerEvent.CallAnswering("+18015550100", true),
            Protocol.parse("""{"type":"call.answering","from":"+18015550100","verified":true,"session_id":"s","lane":"default"}"""),
        )
        val message = Protocol.parse(
            """{"type":"call.message","caller_name":"Ada","callback_number":"+18015550100","message":"Lunch Friday?",
               "urgent":true,"hung_up_early":false,"at":1761500000.0}""",
        )
        assertEquals(ServerEvent.CallMessage("Ada", "+18015550100", "Lunch Friday?", true, false), message)
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
            Protocol.approvalResponse("default", mapOf("a" to Protocol.Answer(true, 30), "b" to Protocol.Answer(false))),
        )
        assertEquals("approval.response", body.getString("type"))
        assertEquals("default", body.getString("lane"))
        val answers = body.getJSONObject("answers")
        assertTrue(answers.getJSONObject("a").getBoolean("approved"))
        assertEquals(30, answers.getJSONObject("a").getInt("remember_minutes"))
        assertFalse(answers.getJSONObject("b").getBoolean("approved"))
        assertFalse(answers.getJSONObject("b").has("remember_minutes"))
    }

    @Test
    fun buildsTurnTaskAndDeviceResult() {
        val turn = JSONObject(Protocol.turn("hi", "t1"))
        assertEquals(listOf("turn", "hi", "t1"), listOf(turn.getString("type"), turn.getString("text"), turn.getString("turn_id")))
        val result = JSONObject(Protocol.deviceResult("tu", JSONObject().put("ok", true)))
        assertEquals("tu", result.getString("action_id"))
        assertTrue(result.getJSONObject("result").getBoolean("ok"))
        assertEquals("task", JSONObject(Protocol.task("g", "id")).getString("type"))
    }
}
