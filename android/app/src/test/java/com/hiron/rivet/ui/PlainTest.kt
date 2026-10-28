package com.hiron.rivet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTest {
    @Test
    fun tiersReadAsWhatTheActionDoes() {
        assertEquals(
            listOf("READS ONLY", "SENDS", "CHANGES", "DELETES", "SPENDS MONEY", "UNFAMILIAR", "UNFAMILIAR"),
            listOf("read", "send", "write", "destructive", "purchase", "unknown", "something_new").map(Plain::tier),
        )
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
    fun otherTextPassesThrough() {
        assertEquals("Dana from Wasatch Dental", Plain.log("call", "Dana from Wasatch Dental"))
        assertEquals("Working on it", Plain.log("turn", "Working on it"))
        assertEquals("search_memory", Plain.log("tool", "search_memory"))
    }

    @Test
    fun kindsHaveWordsAndTones() {
        assertEquals("STEP" to Tone.Cyan, Plain.kind("tool"))
        assertEquals("PROBLEM" to Tone.Red, Plain.kind("error"))
        assertEquals("CALL" to Tone.Green, Plain.kind("call"))
        assertEquals("ODD" to Tone.Neutral, Plain.kind("odd"))
    }
}
