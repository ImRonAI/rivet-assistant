package com.hiron.emrys.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallForwardingTest {

    @Test
    fun standardCarriersGetGsmCodesInInternationalForm() {
        assertEquals("**004*+17608769757#", CallForwarding.enableCode("+1 (760) 876-9757", ForwardMode.Unanswered, CarrierStyle.Gsm))
        assertEquals("**21*+17608769757#", CallForwarding.enableCode("+17608769757", ForwardMode.Always, CarrierStyle.Gsm))
        assertEquals("##002#", CallForwarding.disableCode(CarrierStyle.Gsm))
    }

    @Test
    fun verizonGetsItsFeatureCodesWithTenDigitNumbers() {
        assertEquals("*717608769757", CallForwarding.enableCode("+17608769757", ForwardMode.Unanswered, CarrierStyle.Verizon))
        assertEquals("*727608769757", CallForwarding.enableCode("+17608769757", ForwardMode.Always, CarrierStyle.Verizon))
        assertEquals("*73", CallForwarding.disableCode(CarrierStyle.Verizon))
    }

    @Test
    fun noCodeWithoutARealNumber() {
        assertNull(CallForwarding.enableCode("", ForwardMode.Unanswered, CarrierStyle.Gsm))
        assertNull(CallForwarding.enableCode("911", ForwardMode.Always, CarrierStyle.Verizon))
    }

    @Test
    fun detectsVerizonByNetworkOrName() {
        assertEquals(CarrierStyle.Verizon, CallForwarding.detect("311480", "Verizon"))
        assertEquals(CarrierStyle.Verizon, CallForwarding.detect("311485", null))
        assertEquals(CarrierStyle.Verizon, CallForwarding.detect("", "Verizon Wireless"))
        assertEquals(CarrierStyle.Gsm, CallForwarding.detect("310260", "T-Mobile"))
        assertEquals(CarrierStyle.Gsm, CallForwarding.detect("310410", "AT&T"))
        assertEquals(CarrierStyle.Gsm, CallForwarding.detect(null, null))
    }
}
