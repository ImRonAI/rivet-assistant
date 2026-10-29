package com.hiron.rivet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalPreviewTest {
    @Test
    fun aTextShowsWhoAndTheWholeMessage() {
        val fields = ApprovalPreview.fields("""{"to": "+18015550123", "body": "Friday at 9 works! See you then."}""")
        assertEquals(listOf("To" to "+1 801-555-0123", "Message" to "Friday at 9 works! See you then."), fields)
    }

    @Test
    fun flagsReadAsYesOrNo() {
        val fields = ApprovalPreview.fields("""{"number": "+17608769757", "owner_name": "Tim", "reassign": true}""")
        assertEquals(listOf("Number" to "+1 760-876-9757", "Your name" to "Tim", "Move the number" to "Yes"), fields)
    }

    @Test
    fun nonJsonIsShownAsIs() {
        assertNull(ApprovalPreview.fields("call_tool('ListPhoneNumbers', {})"))
    }
}
