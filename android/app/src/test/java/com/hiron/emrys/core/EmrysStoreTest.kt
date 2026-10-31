package com.hiron.emrys.core

import com.hiron.emrys.testing.textApproval
import org.junit.Assert.assertEquals
import org.junit.Test

class EmrysStoreTest {
    @Test
    fun answersReadPlainlyInTheLog() {
        val request = textApproval()
        assertEquals("Approved: ${request.details}", EmrysStore.answer(request, ApprovalAnswer(true)))
        assertEquals("Always allowed: Send messages", EmrysStore.answer(request, ApprovalAnswer(true, remember = true)))
        assertEquals("Declined: ${request.details}", EmrysStore.answer(request, ApprovalAnswer(false)))
    }

    @Test
    fun theLogKeepsTheLatest200() {
        val s = (1..205).fold(EmrysState()) { acc, i -> EmrysStore.note(acc, i.toLong(), "device", "step $i") }
        assertEquals(200, s.log.size)
        assertEquals(LogEntry(6, "device", "step 6"), s.log.first())
        assertEquals(LogEntry(205, "device", "step 205"), s.log.last())
    }
}
