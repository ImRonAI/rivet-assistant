package com.hiron.emrys.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuleBookTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun rule(key: String, decision: Decision = Decision.Deny) =
        StandingRule(key, RuleTarget("sms_send", mapOf("to" to key)), decision, "Texts to $key", 1.5)

    @Test
    fun keepsTheNewestSetAndSurvivesARestart() {
        val file = File(dir.root, "rules.json")
        val book = RuleBook(file)
        book.load()
        assertEquals(RuleSet.EMPTY, book.state.value)
        val two = RuleSet(2, listOf(rule("+1"), rule("+2", Decision.Allow)))
        book.replace(two)
        book.replace(RuleSet(1, listOf(rule("+3"))))
        assertEquals(two, book.state.value)

        val again = RuleBook(file).apply { load() }
        assertEquals(two, again.state.value)
        assertEquals(false, File(dir.root, "rules.json.tmp").exists())
    }

    @Test
    fun theAnswerToResumeReplacesAnyRevision() {
        val book = RuleBook(File(dir.root, "rules.json"))
        book.replace(RuleSet(9, listOf(rule("+1"))))
        book.replace(RuleSet(0, emptyList()), authoritative = true)
        assertEquals(RuleSet(0, emptyList()), book.state.value)
    }

    @Test
    fun anUnreadableFileStartsEmpty() {
        val file = File(dir.root, "rules.json").apply { writeText("{nope") }
        assertEquals(RuleSet.EMPTY, RuleBook(file).apply { load() }.state.value)
    }
}
