package com.hiron.emrys.voice

import android.speech.SpeechRecognizer
import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.ChatMessage
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.testing.textApproval
import com.hiron.emrys.ui.AssistPhase
import com.hiron.emrys.ui.tail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistModelTest {
    private val asked = "What's on my calendar tomorrow?"
    private val mine = AssistLocal(messageId = "m3", asked = asked)

    /** An earlier exchange in the chat, and this overlay's request just sent to the text agent as message m3. */
    private fun sent(): EmrysState = EmrysState(
        activity = Activity.Thinking,
        messages = listOf(ChatMessage("m1", true, "Hi"), ChatMessage("m2", false, "Hello! What can I do?"), ChatMessage("m3", true, asked)),
    )

    private fun EmrysState.replied(text: String) = copy(messages = messages + ChatMessage("m4", false, text), activity = Activity.Done)

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

        assertEquals("WORKING", AssistModel.ui(mine, sent().copy(activity = Activity.Working)).status)
    }

    @Test
    fun theAgentsReplyFinishesIt() {
        val done = AssistModel.ui(mine, sent().replied("Tomorrow you have a dentist cleaning at 9 AM."))
        assertEquals(AssistPhase.Done, done.phase)
        assertEquals("Tomorrow you have a dentist cleaning at 9 AM.", done.reply)
        assertEquals("DONE", done.status)
    }

    @Test
    fun anApprovalWaitsOnTheUser() {
        val ui = AssistModel.ui(mine, sent().copy(activity = Activity.Waiting, approvals = listOf(textApproval())))
        assertEquals(AssistPhase.Waiting, ui.phase)
        assertEquals(1, ui.waiting)
        assertEquals("NEEDS YOU", ui.status)
    }

    @Test
    fun anAgentThatCouldNotStartSaysWhy() {
        val failed = AssistModel.ui(mine, sent().copy(activity = Activity.Failed, lastError = EmrysAgent.COULD_NOT_START))
        assertEquals(AssistPhase.Failed, failed.phase)
        assertEquals(EmrysAgent.COULD_NOT_START, failed.note)
        assertEquals("", failed.reply)
        assertEquals("DIDN'T FINISH", failed.status)
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
    fun aRequestWithNoMessageYetIsStillWorking() {
        val ui = AssistModel.ui(AssistLocal(messageId = "m5", asked = "hi"), EmrysState(activity = Activity.Thinking, messages = listOf(ChatMessage("x", false, "old"))))
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
