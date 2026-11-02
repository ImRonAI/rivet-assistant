package com.hiron.emrys.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DecisionLogTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L
    private var now = 100 * day
    private val eve = RuleTarget("sms_send", mapOf("to" to "+15550000000"))
    private val dana = RuleTarget("sms_send", mapOf("to" to "+18015550100"))

    private fun log() = DecisionLog(File(dir.root, "decisions.json")) { now }

    @Test
    fun threeDenialsAndNoApprovalMakeACandidate() {
        val log = log()
        repeat(3) { log.record(eve, "Texts to +15550000000", approved = false); now += day }
        repeat(2) { log.record(dana, "Texts to +18015550100", approved = false) }
        assertEquals(listOf(DenyCandidate(eve, "Texts to +15550000000", 3)), log.denyCandidates(RuleSet.EMPTY))
        // Kept on disk.
        assertEquals(1, log().denyCandidates(RuleSet.EMPTY).size)

        log.record(dana, "Texts to +18015550100", approved = false)
        log.record(dana, "Texts to +18015550100", approved = true)
        assertEquals(listOf(eve), log.denyCandidates(RuleSet.EMPTY).map { it.target })

        val ruled = RuleSet(1, listOf(StandingRule("k", eve, Decision.Deny, "Texts to +15550000000", 0.0)))
        assertTrue(log.denyCandidates(ruled).isEmpty())
    }

    @Test
    fun answersOlderThan30DaysAreDropped() {
        val log = log()
        repeat(3) { log.record(eve, "Texts to +15550000000", approved = false) }
        now += 31 * day
        assertTrue(log.denyCandidates(RuleSet.EMPTY).isEmpty())
        log.record(dana, "Texts to +18015550100", approved = false)
        assertTrue(File(dir.root, "decisions.json").readText().contains("+18015550100"))
        assertTrue(!File(dir.root, "decisions.json").readText().contains("+15550000000"))
    }
}
