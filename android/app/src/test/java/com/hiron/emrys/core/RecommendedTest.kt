package com.hiron.emrys.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedTest {
    private class Prefs : RecommendedPrefs {
        override var autoConnect = false
        override var speakInOverlay = true
        override var recommendedUndo: RecommendedUndo? = null
    }

    private val eve = DenyCandidate(RuleTarget("sms_send", mapOf("to" to "+15550000000")), "Texts to +15550000000", 3)

    private fun current(prefs: Prefs, candidates: List<DenyCandidate> = emptyList(), missing: List<PermissionGroup> = emptyList(), assistant: Boolean = true) =
        Current(prefs.autoConnect, prefs.speakInOverlay, missing, assistant, candidates)

    @Test
    fun onlyWhatWouldChangeIsListed() {
        val prefs = Prefs()
        val all = Recommended.list(current(prefs, listOf(eve), PermissionGroups.ALL.take(1), assistant = false))
        assertEquals(listOf(SettingId.AutoConnect, SettingId.QuietOverlay), all.filterIsInstance<Recommendation.Setting>().map { it.setting })
        assertEquals(
            listOf(Recommendation.AskPermissions(PermissionGroups.ALL.take(1)), Recommendation.OpenAssistantSettings, Recommendation.StopAsking(eve)),
            all.drop(2),
        )
        assertEquals("Stop asking about Texts to +15550000000", all.last().title)
        assertEquals("You said no 3 times in the last 30 days. Emrys will always decline it.", all.last().change)
        assertEquals("Android asks you once for: Texts", all[2].change)

        val done = Prefs().apply { autoConnect = true; speakInOverlay = false }
        assertTrue(Recommended.list(current(done)).isEmpty())
    }

    @Test
    fun applyThenUndoPutsEverythingBack() {
        val prefs = Prefs()
        val items = Recommended.list(current(prefs, listOf(eve)))
        Recommended.apply(items, prefs)
        assertEquals(listOf(true, false), listOf(prefs.autoConnect, prefs.speakInOverlay))
        val undo = prefs.recommendedUndo
        assertEquals(setOf(eve.target), undo?.ruleTargets)
        assertEquals(listOf("Connect automatically", "Quiet assistant overlay", "1 rule"), undo?.titles())

        assertEquals(undo, Recommended.undo(prefs))
        assertEquals(listOf(false, true), listOf(prefs.autoConnect, prefs.speakInOverlay))
        assertNull(prefs.recommendedUndo)
        assertNull(Recommended.undo(prefs))
    }

    @Test
    fun onlyAppliedItemsAreRecorded() {
        val prefs = Prefs()
        val items = Recommended.list(current(prefs)).filter { (it as? Recommendation.Setting)?.setting == SettingId.AutoConnect }
        Recommended.apply(items, prefs)
        assertEquals(RecommendedUndo(autoConnect = false), prefs.recommendedUndo)
        assertEquals(true, prefs.speakInOverlay)
        val nothing = Prefs()
        Recommended.apply(listOf(Recommendation.OpenAssistantSettings), nothing)
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
