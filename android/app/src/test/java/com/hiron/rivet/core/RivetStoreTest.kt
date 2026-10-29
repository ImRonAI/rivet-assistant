package com.hiron.rivet.core

import com.hiron.rivet.testing.textApproval
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RivetStoreTest {
    private fun approval(id: String, lane: String = "default", turn: String = "t1") = textApproval(id, lane, turn)

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
    fun answeredCallsAndMessagesAreLogged() {
        var s = RivetStore.reduce(RivetState(), ServerEvent.CallAnswering("+18015550100", true), 1)
        s = RivetStore.reduce(s, ServerEvent.CallMessage("Ada", "+18015550100", "Lunch Friday?", true, false), 2)
        assertEquals(listOf("call", "call"), s.log.map { it.kind })
        assertEquals("Rivet is answering a call from +18015550100", s.log[0].text)
        assertEquals("Message from Ada · +18015550100 (urgent): Lunch Friday?", s.log[1].text)
        val anonymous = CallText.describe(ServerEvent.CallMessage("", "", "", false, true))
        assertEquals("Message from Unknown caller (hung up early): (no message)", anonymous)
    }

    private fun live(lines: List<CallLineText>, status: String = "answering", snapshot: Boolean = false, id: String = "c-1") =
        ServerEvent.CallLive(id, "+18015550100", true, 1761500000.5, status, lines, snapshot)

    private fun said(speaker: String, text: String) = CallLineText(speaker, text)

    @Test
    fun aLiveCallCollectsItsTranscriptAndIsCapped() {
        var s = RivetStore.reduce(RivetState(), live(emptyList(), snapshot = true), 1)
        val call = s.liveCall!!
        assertEquals("c-1", call.callId)
        assertEquals("+18015550100", call.caller)
        assertEquals(1761500000500L, call.startedAt)
        assertEquals(LiveCallStatus.Answering, call.status)
        s = RivetStore.reduce(s, live(listOf(said("rivet", "Hi, you've reached Tim."))), 2)
        s = RivetStore.reduce(s, live(listOf(said("caller", "It's Ada."))), 3)
        assertEquals(listOf("Rivet: Hi, you've reached Tim.", "Caller: It's Ada."), s.liveCall!!.lines.map(CallText::line))
        s = RivetStore.reduce(s, ServerEvent.CallMessage("Ada", "+18015550100", "Lunch?", false, false), 4)
        assertEquals("Ada", s.liveCall!!.caller)
        assertEquals("+18015550100", s.liveCall.number)

        repeat(60) { s = RivetStore.reduce(s, live(listOf(said("caller", "line $it"))), 5) }
        assertEquals(RivetStore.CALL_LINE_LIMIT, s.liveCall.lines.size)
        assertEquals("line 59", s.liveCall.lines.last().text)
        s = RivetStore.reduce(s, live(listOf(said("rivet", "a"), said("caller", "b")), snapshot = true), 6)
        assertEquals(listOf("a", "b"), s.liveCall!!.lines.map { it.text })
        assertTrue(s.log.none { it.text.contains("line") })
    }

    @Test
    fun takeOverShowsTransferringAndAFailedOneComesBack() {
        var s = RivetStore.reduce(RivetState(), live(emptyList(), snapshot = true), 1)
        s = RivetStore.reduce(s, live(emptyList(), status = "transferring"), 2)
        assertEquals(LiveCallStatus.Transferring, s.liveCall!!.status)
        // A refusal of a second tap leaves the transfer in progress; only call.live changes the status.
        s = RivetStore.reduce(s, ServerEvent.CallTakeOverFailed("c-1", "Already transferring"), 3)
        assertEquals(LiveCallStatus.Transferring, s.liveCall!!.status)
        assertEquals("Already transferring", s.lastError)
        s = RivetStore.reduce(s, live(emptyList()), 4)
        assertEquals(LiveCallStatus.Answering, s.liveCall!!.status)
        assertEquals(LogEntry(3, "call", "Couldn't take over the call: Already transferring"), s.log.last())
    }

    @Test
    fun theEndSettlesTheCallAndLogsItsSummary() {
        var s = RivetStore.reduce(RivetState(), live(listOf(said("caller", "It's Ada.")), snapshot = true), 1)
        val ended = ServerEvent.CallEnded(
            "c-1", "+18015550100", "Ada", 1761500000.5, 1761500125.5, 125, "Ada wants lunch on Friday.", "message_taken", false,
        )
        s = RivetStore.reduce(s, ended, 2)
        val call = s.liveCall!!
        assertEquals(LiveCallStatus.Ended, call.status)
        assertEquals(listOf("It's Ada."), call.lines.map { it.text })
        assertEquals(LogEntry(2, "call", "Call from Ada · +18015550100, 2m 05s: Ada wants lunch on Friday. Message taken."), s.log.last())
        s = RivetStore.reduce(s, live(listOf(said("caller", "late"))), 3)
        assertEquals(LiveCallStatus.Ended, s.liveCall!!.status)
        assertEquals(1, s.liveCall.lines.size)
        assertNull(RivetStore.dismissCall(s).liveCall)

        val unseen = RivetStore.reduce(RivetState(), ended.copy(callId = "c-2", takenOver = true, durationSeconds = null), 9)
        assertEquals(LiveCallStatus.Ended, unseen.liveCall!!.status)
        assertEquals("Call from Ada · +18015550100: Ada wants lunch on Friday. You took it over.", unseen.log.last().text)
        val next = RivetStore.reduce(s, live(emptyList(), snapshot = true, id = "c-3"), 10)
        assertEquals(LiveCallStatus.Answering, next.liveCall!!.status)
        assertEquals(next, RivetStore.dismissCall(next))
    }

    @Test
    fun takeOverNeedsALiveCallAConnectionAndTheUsersOwnNumber() {
        val online = RivetStore.connection(RivetState(), Connection.Online)
        val s = RivetStore.reduce(online, live(emptyList(), snapshot = true), 1)
        assertNull(RivetStore.takeOverRefusal(s, "c-1", "+18015550199", forwardsAllCalls = false))
        assertEquals("That call has already ended.", RivetStore.takeOverRefusal(s, "c-9", "+18015550199", false))
        assertTrue(RivetStore.takeOverRefusal(s, "c-1", "801-555-0199", false)!!.contains("Settings › Call answering"))
        assertTrue(RivetStore.takeOverRefusal(s, "c-1", "+18015550199", true)!!.contains("straight back"))
        val offline = RivetStore.connection(s, Connection.Offline)
        assertTrue(RivetStore.takeOverRefusal(offline, "c-1", "+18015550199", false)!!.contains("offline"))
        val transferring = RivetStore.reduce(s, live(emptyList(), status = "transferring"), 2)
        assertTrue(RivetStore.takeOverRefusal(transferring, "c-1", "+18015550199", false)!!.contains("already transferring"))
        assertTrue(isE164("+18015550199"))
        assertFalse(isE164("+0801555"))
        assertFalse(isE164("18015550199"))
    }

    @Test
    fun deviceCallsAreLogged() {
        val s = RivetStore.reduce(RivetState(), ServerEvent.DeviceCall("a", "calendar_list", JSONObject()), 5)
        assertEquals(LogEntry(5, "device", "calendar_list"), s.log.single())
    }

    @Test
    fun approvalsAndRulesReadPlainlyInTheLog() {
        val text = approval("a1")
        var s = RivetStore.reduce(RivetState(), ServerEvent.ApprovalsRequested("t1", "default", listOf(text, approval("a2")), false), 1)
        assertEquals("Asked: Text +18015550100: “Friday at 9 works!”; Text +18015550100: “Friday at 9 works!”", s.log.last().text)
        val resent = RivetStore.reduce(s, ServerEvent.ApprovalsRequested("t1", "default", listOf(text), true), 2)
        assertEquals(s.log.size, resent.log.size)
        s = RivetStore.reduce(s, ServerEvent.ApprovalExpired("t1", "default"), 3)
        assertEquals("Nobody answered in time, so Rivet didn't do it.", s.log.last().text)
        s = RivetStore.reduce(s, ServerEvent.RulesApplied(Decision.Deny, "k", "Texts to +1", "sms_send"), 4)
        assertEquals("Declined by your rule: Texts to +1", s.log.last().text)
        assertEquals("Always allowed: Texts to +18015550100", RivetStore.answer(text, Protocol.Answer(true, Decision.Allow)))
        assertEquals("Always declined: Texts to +18015550100", RivetStore.answer(text, Protocol.Answer(false, Decision.Deny)))
        assertEquals("Approved: Text +18015550100: “Friday at 9 works!”", RivetStore.answer(text, Protocol.Answer(true)))
        assertEquals("Declined: Text +18015550100: “Friday at 9 works!”", RivetStore.answer(text, Protocol.Answer(false)))

        s = s.copy(removingRules = setOf("k"))
        s = RivetStore.reduce(s, ServerEvent.RulesRefused(RuleRequest.Revoke, "No such rule."), 5)
        assertEquals("No such rule.", s.rulesError)
        assertTrue(s.removingRules.isEmpty())
        s = RivetStore.reduce(s, ServerEvent.Rules(RuleSet.EMPTY), 6)
        assertNull(s.rulesError)
    }

    @Test
    fun aMessageJoinsTheCallItWasTakenOn() {
        val live = ServerEvent.CallLive("c-1", "+18015550100", true, null, "answering", emptyList(), true)
        var s = RivetStore.reduce(RivetState(), live, 1)
        s = RivetStore.reduce(s, ServerEvent.CallMessage("Ada", "+18015550100", "Lunch?", true, false, "c-1"), 2)
        assertEquals("Lunch?", s.liveCall?.message)
        assertEquals(true, s.liveCall?.urgent)
        assertEquals("Ada", s.liveCall?.caller)
        val other = RivetStore.reduce(s, ServerEvent.CallMessage("Bo", "+1", "Other", false, false, "c-2"), 3)
        assertEquals("Lunch?", other.liveCall?.message)
        val ended = RivetStore.reduce(s, ServerEvent.CallEnded("c-1", "+18015550100", "Ada", null, null, 30, "Lunch.", "message_taken", false), 4)
        assertEquals("Lunch?", ended.liveCall?.message)
    }

    @Test
    fun aBrainSearchShowsPlaceholdersThenItsCardsInOrder() {
        var s = RivetStore.userSent(RivetState(), "t1", "coffee near the capitol")
        s = RivetStore.reduce(s, ServerEvent.ToolStarted("tu1", "search_places", "default"), 1)
        assertEquals(SearchKind.Places, s.messages.last().searching)
        assertEquals(Activity.Searching, s.activity)
        s = RivetStore.reduce(s, ServerEvent.ToolsDone(listOf("tu1" to "success"), "default"), 2)
        assertEquals(SearchKind.Places, s.messages.last().searching)
        s = RivetStore.reduce(s, ServerEvent.SearchResults("tu1", com.hiron.rivet.testing.SearchFixtures.placeResults, "default"), 3)
        assertEquals(com.hiron.rivet.testing.SearchFixtures.placeResults, s.messages[1].search)
        s = RivetStore.reduce(s, ServerEvent.TextDelta("Summit Coffee (1) is closest.", "default"), 4)
        s = RivetStore.reduce(s, ServerEvent.TurnCompleted("t1", "default", "Summit Coffee (1) is closest.", 0, null), 5)
        assertEquals(listOf(true, false, false), s.messages.map { it.fromUser })
        assertEquals("Summit Coffee (1) is closest.", s.messages.last().text)
        assertEquals(3, s.messages.size)
    }

    @Test
    fun searchesOnOtherLanesAndFailedOnesLeaveNoCards() {
        var s = RivetStore.reduce(RivetState(), ServerEvent.ToolStarted("tu1", "search_web", "task-1"), 1)
        s = RivetStore.reduce(s, ServerEvent.SearchResults("tu1", com.hiron.rivet.testing.SearchFixtures.webResults, "task-1"), 2)
        assertTrue(s.messages.isEmpty())
        s = RivetStore.reduce(s, ServerEvent.ToolStarted("tu2", "search_web", "default"), 3)
        s = RivetStore.reduce(s, ServerEvent.ToolsDone(listOf("tu2" to "error"), "default"), 4)
        assertTrue(s.messages.isEmpty())
        s = RivetStore.reduce(s, ServerEvent.ToolStarted("tu3", "search_news", "default"), 5)
        s = RivetStore.reduce(s, ServerEvent.TurnCompleted("t1", "default", "", 0, null), 6)
        assertTrue(s.messages.none { it.searching != null })
    }

    @Test
    fun chipsGoWithTheTurnAndClearOnceItIsAccepted() {
        val chip = ContextItem(SearchKind.Web, "Guide", "https://guide.example.com", null, null, null)
        val later = ContextItem(SearchKind.News, "Race", "https://news.example.com", null, null, null)
        var s = RivetState(chips = listOf(chip))
        s = RivetStore.userSent(s, "t1", "", s.chips)
        s = s.copy(chips = s.chips + later)
        assertEquals(listOf(chip), s.messages.single().attachments)
        s = RivetStore.reduce(s, ServerEvent.TurnAccepted("t1"), 1)
        assertEquals(listOf(later), s.chips)
    }

    @Test
    fun theModelEventReplacesARefusal() {
        var s = RivetStore.reduce(RivetState(), ServerEvent.ModelRefused("Groq isn't set up on Rivet's brain."), 1)
        assertEquals("Groq isn't set up on Rivet's brain.", s.modelError)
        s = RivetStore.reduce(s, ServerEvent.Model(BrainModel.Perplexity, setOf(BrainModel.Perplexity)), 2)
        assertNull(s.modelError)
        assertEquals(BrainModel.Perplexity, s.brainModel)
    }
}
