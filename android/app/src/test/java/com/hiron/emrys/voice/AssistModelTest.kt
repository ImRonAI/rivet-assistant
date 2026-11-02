package com.hiron.emrys.voice

import android.speech.SpeechRecognizer
import com.hiron.emrys.testing.textApproval
import com.hiron.emrys.core.ChatMessage
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.EmrysStore
import com.hiron.emrys.core.ServerEvent
import com.hiron.emrys.ui.AssistPhase
import com.hiron.emrys.ui.tail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistModelTest {
    private val asked = "What's on my calendar tomorrow?"
    private val mine = AssistLocal(turnId = "t2", asked = asked)

    /** Online, with an earlier exchange in the chat, and this overlay's request just sent as turn t2. */
    private fun sent(): EmrysState {
        var s = EmrysStore.connection(EmrysState(), Connection.Online)
        s = EmrysStore.userSent(s, "t1", "Hi")
        s = EmrysStore.reduce(s, ServerEvent.TurnCompleted("t1", "default", "Hello! What can I do?", 0, null), 1)
        return EmrysStore.userSent(s, "t2", asked)
    }

    private fun EmrysState.then(vararg events: ServerEvent) = events.fold(this) { s, e -> EmrysStore.reduce(s, e, 2) }

    private fun approval(turn: String) = textApproval("a-$turn", turn = turn)

    @Test
    fun listeningShowsTheWordsAsTheyAreHeard() {
        var local = AssistLocal(listening = true)
        local = AssistModel.partial(local, listOf("what's on my"))
        local = AssistModel.partial(local, listOf(""))
        local = local.copy(level = AssistModel.level(4f))
        val ui = AssistModel.ui(local, sent())
        assertEquals(AssistPhase.Listening, ui.phase)
        assertEquals("what's on my", ui.heard)
        assertEquals("LISTENING", ui.status)
        assertEquals(0.5f, ui.level, 0.001f)
        assertEquals("", ui.reply)
    }

    @Test
    fun theFinalResultIsSentOrTheLastPartialWhenThereIsNone() {
        val heard = AssistLocal(listening = true, partial = "call mom")
        assertEquals("call mom back", AssistModel.said(heard, listOf("  ", "call mom back ")))
        assertEquals("call mom", AssistModel.said(heard, null))
        assertEquals("", AssistModel.said(AssistLocal(), emptyList()))
    }

    @Test
    fun workingUntilTheFirstWordsOfTheReplyAndSaysWhatEmrysIsDoing() {
        val thinking = AssistModel.ui(mine, sent())
        assertEquals(AssistPhase.Working, thinking.phase)
        assertEquals(asked, thinking.heard)
        assertEquals("THINKING", thinking.status)
        // The reply to the earlier turn is not this overlay's.
        assertEquals("", thinking.reply)

        val searching = AssistModel.ui(mine, sent().then(ServerEvent.Searching(listOf("calendar"), "default")))
        assertEquals("SEARCHING", searching.status)
        assertEquals("OFFLINE · QUEUED", AssistModel.ui(mine, EmrysStore.connection(sent(), Connection.Offline)).status)
        assertEquals("CONNECTING", AssistModel.ui(mine, EmrysStore.connection(sent(), Connection.Connecting)).status)
    }

    @Test
    fun theReplyStreamsThenFinishes() {
        val streaming = sent().then(ServerEvent.TextDelta("Tomorrow you have ", "default"))
        val replying = AssistModel.ui(mine, streaming)
        assertEquals(AssistPhase.Replying, replying.phase)
        assertEquals("Tomorrow you have ", replying.reply)

        val done = AssistModel.ui(
            mine,
            streaming.then(ServerEvent.TurnCompleted("t2", "default", "Tomorrow you have a dentist cleaning at 9 AM.", 0, null)),
        )
        assertEquals(AssistPhase.Done, done.phase)
        assertEquals("Tomorrow you have a dentist cleaning at 9 AM.", done.reply)
        assertEquals("DONE", done.status)
    }

    @Test
    fun anApprovalForThisRequestWaitsOnTheUser() {
        val other = AssistModel.ui(mine, sent().then(ServerEvent.ApprovalsRequested("t9", "task-1", listOf(approval("t9")), false)))
        assertEquals(AssistPhase.Working, other.phase)

        val ui = AssistModel.ui(mine, sent().then(ServerEvent.ApprovalsRequested("t2", "default", listOf(approval("t2")), false)))
        assertEquals(AssistPhase.Waiting, ui.phase)
        assertEquals(1, ui.waiting)
        assertEquals("NEEDS YOU", ui.status)
    }

    @Test
    fun aFailedTurnSaysWhy() {
        val before = AssistModel.ui(mine, sent().then(ServerEvent.TurnFailed("t2", "default", "the brain is offline")))
        assertEquals(AssistPhase.Failed, before.phase)
        assertEquals("I couldn't finish that: the brain is offline", before.note)
        assertEquals("", before.reply)
        assertEquals("DIDN'T FINISH", before.status)

        val midway = AssistModel.ui(
            mine,
            sent().then(ServerEvent.TextDelta("Checking your", "default"), ServerEvent.TurnFailed("t2", "default", "model overloaded")),
        )
        assertEquals(AssistPhase.Failed, midway.phase)
        assertEquals("Checking your", midway.reply)
        assertEquals("I couldn't finish that: model overloaded", midway.note)
    }

    @Test
    fun recognizerErrorsBecomeAMicKeyANudgeOrAProblem() {
        val typing = AssistLocal(listening = true, typing = true)
        assertTrue(AssistModel.misheard(typing, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).needsMic)
        assertTrue(AssistModel.misheard(typing, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).typing)
        assertEquals(AssistPhase.NeedsMic, AssistModel.ui(AssistModel.misheard(typing, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS), EmrysState()).phase)

        val missed = AssistModel.ui(AssistModel.misheard(AssistLocal(listening = true), SpeechRecognizer.ERROR_NO_MATCH), EmrysState())
        assertEquals(AssistPhase.Ready, missed.phase)
        assertEquals(AssistModel.DIDNT_CATCH, missed.note)

        val offline = AssistModel.ui(AssistModel.misheard(AssistLocal(listening = true), SpeechRecognizer.ERROR_NETWORK), EmrysState())
        assertEquals(AssistPhase.Failed, offline.phase)
        assertTrue(offline.note!!.contains("connection"))
        assertTrue(AssistModel.misheard(AssistLocal(), 42).problem!!.contains("error 42"))
    }

    @Test
    fun aLockedPhoneOnlyOffersToUnlock() {
        val ui = AssistModel.ui(AssistLocal(locked = true, listening = true, partial = "read my texts"), sent())
        assertEquals(AssistPhase.Locked, ui.phase)
        assertEquals("", ui.heard)
        assertEquals(AssistModel.LOCKED_NOTE, ui.note)
    }

    @Test
    fun setupProblemsKeepTheRequestInView() {
        val ui = AssistModel.ui(AssistLocal(asked = "Text Dana", problem = AssistModel.SET_UP_FIRST), EmrysState())
        assertEquals(AssistPhase.Failed, ui.phase)
        assertEquals("Text Dana", ui.heard)
        assertEquals(AssistModel.SET_UP_FIRST, ui.note)
        assertEquals("DIDN'T FINISH", ui.status)
    }

    @Test
    fun aRequestWithNoMessageYetIsStillWorking() {
        val ui = AssistModel.ui(AssistLocal(turnId = "t5", asked = "hi"), EmrysState(activeTurn = "t5", messages = listOf(ChatMessage("x", false, "old"))))
        assertEquals(AssistPhase.Working, ui.phase)
        assertNull(ui.note)
    }

    @Test
    fun levelAndTailStayInBounds() {
        assertEquals(0f, AssistModel.level(-10f))
        assertEquals(1f, AssistModel.level(30f))
        assertEquals("short", tail("short"))
        val long = (1..60).joinToString(" ") { "word$it" }
        val shown = tail(long)
        assertTrue(shown.startsWith("…"))
        assertTrue(shown.endsWith("word60"))
        assertTrue(shown.length <= 141)
        assertTrue(shown.drop(1).startsWith("word"))
    }
}
