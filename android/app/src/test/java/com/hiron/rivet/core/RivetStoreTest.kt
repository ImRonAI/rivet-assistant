package com.hiron.rivet.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RivetStoreTest {
    private fun approval(id: String, lane: String = "default", turn: String = "t1") = ApprovalRequest(
        id, lane, turn, "sms_send", "send", false, "Send a text", "", true, false, emptyList(), null,
    )

    @Test
    fun streamsTextIntoOneAssistantMessageAndClosesIt() {
        var s = RivetStore.userSent(RivetState(), "t1", "what's on today?")
        s = RivetStore.reduce(s, ServerEvent.TextDelta("You have ", "default"), 1)
        s = RivetStore.reduce(s, ServerEvent.TextDelta("two meetings.", "default"), 2)
        assertEquals(2, s.messages.size)
        assertTrue(s.messages.last().streaming)
        s = RivetStore.reduce(s, ServerEvent.TurnCompleted("t1", "default", "You have two meetings.", 0, null), 3)
        assertEquals("You have two meetings.", s.messages.last().text)
        assertFalse(s.messages.last().streaming)
        assertEquals(Activity.Done, s.activity)
        assertNull(s.activeTurn)
    }

    @Test
    fun taskLaneTextStaysOutOfTheChat() {
        var s = RivetStore.userSent(RivetState(), "t1", "hi")
        s = RivetStore.reduce(s, ServerEvent.TextDelta("task chatter", "task-9"), 1)
        s = RivetStore.reduce(s, ServerEvent.TurnCompleted("9", "task-9", "Done booking", 0, "book it"), 2)
        assertEquals(1, s.messages.size)
        assertEquals("t1", s.activeTurn)
        assertTrue(s.log.last().text.contains("Done booking"))
    }

    @Test
    fun approvalsAreReplacedOnResendAndClearedWhenAnswered() {
        var s = RivetStore.reduce(RivetState(), ServerEvent.ApprovalsRequested("t1", "default", listOf(approval("a")), false), 1)
        s = RivetStore.reduce(s, ServerEvent.ApprovalsRequested("t1", "default", listOf(approval("a")), true), 2)
        assertEquals(1, s.approvals.size)
        assertEquals(Activity.Waiting, s.activity)
        s = RivetStore.answered(s, setOf("a"))
        assertTrue(s.approvals.isEmpty())
    }

    @Test
    fun expiryDropsOnlyThatTurnsApprovals() {
        var s = RivetStore.reduce(
            RivetState(),
            ServerEvent.ApprovalsRequested("t1", "default", listOf(approval("a"), approval("b", lane = "task-1", turn = "x")), false),
            1,
        )
        s = RivetStore.reduce(s, ServerEvent.ApprovalExpired("t1", "default"), 2)
        assertEquals(listOf("b"), s.approvals.map { it.id })
    }

    @Test
    fun failureMarksTheTurnAndKeepsTheError() {
        var s = RivetStore.userSent(RivetState(), "t1", "hi")
        s = RivetStore.reduce(s, ServerEvent.TurnFailed("t1", "default", "model unavailable"), 1)
        assertEquals(Activity.Failed, s.activity)
        assertEquals("model unavailable", s.lastError)
        assertTrue(s.messages.last().failed)
        assertNull(s.activeTurn)
    }

    @Test
    fun toolsDriveActivityAndTraceIsBounded() {
        var s = RivetState()
        repeat(12) { s = RivetStore.reduce(s, ServerEvent.ToolStarted("$it", "sms_send", "default"), it.toLong()) }
        assertEquals(Activity.Texting, s.activity)
        assertEquals(7, s.trace.size)
        assertEquals(Activity.Emailing, RivetStore.activityFor("email_draft"))
        assertEquals(Activity.Calling, RivetStore.activityFor("phone_call"))
        assertEquals(Activity.Working, RivetStore.activityFor("telnyx_execute"))
    }

    @Test
    fun deviceCallsAreLogged() {
        val s = RivetStore.reduce(RivetState(), ServerEvent.DeviceCall("a", "calendar_list", JSONObject()), 5)
        assertEquals(LogEntry(5, "device", "calendar_list"), s.log.single())
    }
}
