package com.hiron.rivet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTest {
    @Test
    fun tiersReadAsWhatTheActionDoes() {
        assertEquals(
            listOf(
                "Only looks · changes nothing", "Sends a message · you can't undo it", "Changes something on your phone or account",
                "Deletes something · you can't undo it", "Spends money · you can't undo it", "Something Rivet hasn't done before",
                "Something Rivet hasn't done before",
            ),
            listOf("read", "send", "write", "destructive", "purchase", "unknown", "something_new").map { Plain.risk(it, "sms_send") },
        )
        assertEquals("Places a call · you can't undo it", Plain.risk("send", "phone_call"))
        assertEquals(
            listOf(Tone.Cyan, Tone.Amber, Tone.Amber, Tone.Red, Tone.Red, Tone.Red),
            listOf("read", "send", "write", "destructive", "purchase", "unknown").map(Plain::toneFor),
        )
    }

    @Test
    fun toolIdsBecomeSentences() {
        assertEquals("Send a text", Plain.action("sms_send"))
        assertEquals("Sent a text", Plain.done("sms_send"))
        assertEquals("Use your phone service", Plain.action("telnyx_execute"))
        assertEquals("Checked the calendar", Plain.done("calendar_list"))
        assertEquals("Todo write", Plain.action("todo_write"))
    }

    @Test
    fun logLinesNameWhatRivetDid() {
        assertEquals("Checked the calendar", Plain.log("tool", "calendar_list"))
        assertEquals("Checked the phone", Plain.log("device", "device_status: done"))
        assertEquals("Placed a call · No permission to call", Plain.log("device", "phone_call: No permission to call"))
        assertEquals("Rivet's model is busy. Trying again in 8s.", Plain.log("backoff", "Model busy; retrying in 8s"))
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
        assertEquals("search_memory", Plain.log("tool", "search_memory"))
    }

    @Test
    fun kindsHaveWordsAndTones() {
        assertEquals("DID" to Tone.Cyan, Plain.kind("tool"))
        assertEquals("YOU ASKED" to Tone.Accent, Plain.kind("turn"))
        assertEquals("TRYING AGAIN" to Tone.Amber, Plain.kind("backoff"))
        assertEquals("PROBLEM" to Tone.Red, Plain.kind("error"))
        assertEquals("CALL" to Tone.Green, Plain.kind("call"))
        assertEquals("ODD" to Tone.Neutral, Plain.kind("odd"))
    }
}
