package com.hiron.rivet.core

import com.hiron.rivet.device.ForwardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedTest {
    private class Prefs : RecommendedPrefs {
        override var autoConnect = false
        override var speakInOverlay = true
        override var offerReplies = false
        override var answeringMode = ForwardMode.Always
        override var recommendedUndo: RecommendedUndo? = null
    }

    private val eve = DenyCandidate(RuleTarget("sms_send", mapOf("to" to "+15550000000")), "Texts to +15550000000", 3)

    private fun current(prefs: Prefs, candidates: List<DenyCandidate> = emptyList(), missing: List<PermissionGroup> = emptyList(), assistant: Boolean = true) =
        Current(prefs.autoConnect, prefs.speakInOverlay, prefs.offerReplies, prefs.answeringMode, true, missing, assistant, candidates)

    @Test
    fun onlyWhatWouldChangeIsListed() {
        val prefs = Prefs()
        val all = Recommended.list(current(prefs, listOf(eve), PermissionGroups.ALL.take(1), assistant = false))
        assertEquals(
            listOf(RecId.AutoConnect, RecId.QuietOverlay, RecId.OfferReplies, RecId.UnansweredCalls, RecId.Permissions, RecId.Assistant, RecId.StopAsking),
            all.map { it.id },
        )
        assertEquals("Stop asking about Texts to +15550000000", all.last().title)
        assertEquals("You said no 3 times in the last 30 days. Rivet will always decline it.", all.last().change)
        assertEquals("Android asks you once for: Texts", all[4].change)
        assertTrue(all[3].change.endsWith("until you turn call answering on again in Settings › Call answering."))

        val done = Prefs().apply { autoConnect = true; speakInOverlay = false; offerReplies = true; answeringMode = ForwardMode.Unanswered }
        assertTrue(Recommended.list(current(done)).isEmpty())
    }

    @Test
    fun applyThenUndoPutsEverythingBack() {
        val prefs = Prefs()
        val items = Recommended.list(current(prefs, listOf(eve)))
        val undo = Recommended.apply(items, prefs, now = 7)
        assertEquals(listOf(true, false, true, ForwardMode.Unanswered), listOf(prefs.autoConnect, prefs.speakInOverlay, prefs.offerReplies, prefs.answeringMode))
        assertEquals(setOf(eve.target), undo.ruleTargets)
        assertEquals(undo, prefs.recommendedUndo)
        assertEquals(
            listOf("Connect automatically", "Quiet assistant overlay", "Offer replies to new messages", "Answer only calls you miss", "1 rule"),
            undo.titles(),
        )

        assertEquals(undo, Recommended.undo(prefs))
        assertEquals(listOf(false, true, false, ForwardMode.Always), listOf(prefs.autoConnect, prefs.speakInOverlay, prefs.offerReplies, prefs.answeringMode))
        assertNull(prefs.recommendedUndo)
        assertNull(Recommended.undo(prefs))
    }

    @Test
    fun onlyAppliedItemsAreRecorded() {
        val prefs = Prefs()
        val items = Recommended.list(current(prefs)).filter { it.id == RecId.OfferReplies }
        val undo = Recommended.apply(items, prefs, now = 1)
        assertEquals(RecommendedUndo(offerReplies = false, at = 1), undo)
        assertEquals(false, prefs.autoConnect)
        val nothing = Prefs()
        Recommended.apply(listOf(Recommendation.OpenAssistantSettings), nothing, now = 1)
        assertNull(nothing.recommendedUndo)
    }

    @Test
    fun ruleTargetsRoundTrip() {
        val odd = RuleTarget("screen_act", mapOf("label" to "Pay = now\u0000 & “more”"))
        assertEquals(odd, RuleTargetCodec.decode(RuleTargetCodec.encode(odd)))
        assertEquals(RuleTarget("calendar_create", emptyMap()), RuleTargetCodec.decode(RuleTargetCodec.encode(RuleTarget("calendar_create", emptyMap()))))
        assertNull(RuleTargetCodec.decode(""))
    }
}
