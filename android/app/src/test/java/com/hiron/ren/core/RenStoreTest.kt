package com.hiron.ren.core

import com.hiron.ren.testing.textApproval
import org.junit.Assert.assertEquals
import org.junit.Test

class RenStoreTest {
    @Test
    fun answersReadPlainlyInTheLog() {
        val request = textApproval()
        assertEquals("Approved: ${request.details}", RenStore.answer(request, ApprovalAnswer(true)))
        assertEquals("Always allowed: Send messages", RenStore.answer(request, ApprovalAnswer(true, remember = true)))
        assertEquals("Declined: ${request.details}", RenStore.answer(request, ApprovalAnswer(false)))
    }

    @Test
    fun theLogKeepsTheLatest200() {
        val s = (1..205).fold(RenState()) { acc, i -> RenStore.note(acc, i.toLong(), "device", "step $i") }
        assertEquals(200, s.log.size)
        assertEquals(LogEntry(6, "device", "step 6"), s.log.first())
        assertEquals(LogEntry(205, "device", "step 205"), s.log.last())
    }
}
