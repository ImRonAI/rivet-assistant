package com.hiron.emrys.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleScopeTest {
    @Test
    fun sharedNormalizerVectors() {
        // brain/tests/test_rules.py test_shared_normalizer_vectors
        assertEquals("+18015550100", RuleScope.phone("+1 (801) 555-0100"))
        assertEquals("+18015550100", RuleScope.phone("+1.801.555.0100\t"))
        assertEquals("", RuleScope.phone(""))
        assertEquals("Send", RuleScope.text("  Send  "))
    }

    @Test
    fun targetsOnlyForTheToolsThePhoneAsksAbout() {
        assertEquals(RuleTarget("sms_send", mapOf("to" to "+18015550100")), RuleScope.target("sms_send", JSONObject().put("to", "+1 801-555-0100").put("body", "x")))
        assertEquals(RuleTarget("phone_call", mapOf("number" to "911")), RuleScope.target("phone_call", JSONObject().put("number", "911")))
        assertEquals(RuleTarget("calendar_create", emptyMap()), RuleScope.target("calendar_create", JSONObject().put("title", "x")))
        assertEquals(RuleTarget("screen_act", mapOf("label" to "Send")), RuleScope.target("screen_act", JSONObject().put("action", "tap").put("label", " Send ")))
        assertNull(RuleScope.target("screen_act", JSONObject().put("action", "type").put("label", "Send")))
        assertNull(RuleScope.target("screen_act", JSONObject().put("action", "tap")))
        assertNull(RuleScope.target("sms_send", JSONObject().put("to", 18015550100L)))
        assertNull(RuleScope.target("sms_send", JSONObject().put("to", JSONObject.NULL)))
        assertNull(RuleScope.target("sms_send", JSONObject().put("to", " ( ) ")))
        assertNull(RuleScope.target("notification_reply", JSONObject().put("key", "k")))
    }
}
