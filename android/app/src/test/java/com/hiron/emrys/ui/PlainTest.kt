package com.hiron.emrys.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTest {
    @Test
    fun toolIdsBecomeSentences() {
        assertEquals("Send a text", Plain.action("sms_send"))
        assertEquals("Sent a text", Plain.done("sms_send"))
        assertEquals("Checked the calendar", Plain.done("calendar_list"))
        assertEquals("Todo write", Plain.action("todo_write"))
    }

    @Test
    fun logLinesNameWhatEmrysDid() {
        assertEquals("Checked the calendar", Plain.log("device", "calendar_list"))
        assertEquals("Checked the phone", Plain.log("device", "device_status: done"))
        assertEquals("Place a call · No permission to call", Plain.log("device", "phone_call: No permission to call"))
    }

    @Test
    fun numbersAreGrouped() {
        assertEquals("+1 801-555-0123", Plain.phone("+18015550123"))
        assertEquals("Dana", Plain.phone("Dana"))
        assertEquals("Text +1 801-555-0123 about Friday", Plain.numbers("Text +18015550123 about Friday"))
    }

    @Test
    fun otherTextPassesThrough() {
        assertEquals("Dana from Wasatch Dental", Plain.log("call", "Dana from Wasatch Dental"))
        assertEquals("Working on it", Plain.log("turn", "Working on it"))
        assertEquals("search_memory", Plain.log("device", "search_memory"))
    }

    @Test
    fun kindsHaveWordsAndTones() {
        assertEquals("Phone" to Tone.Cyan, Plain.kind("device"))
        assertEquals("Approval" to Tone.Amber, Plain.kind("approval"))
        assertEquals("Problem" to Tone.Red, Plain.kind("error"))
        assertEquals("Call" to Tone.Green, Plain.kind("call"))
        assertEquals("Odd" to Tone.Neutral, Plain.kind("odd"))
    }
}
