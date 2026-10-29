package com.hiron.rivet.voice

import com.hiron.rivet.core.Protocol
import com.hiron.rivet.core.rivet
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The agents' approvals go through the same sheet state as the brain's, but are answered on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentApprovalTest {
    private val runtime get() = RuntimeEnvironment.getApplication().rivet

    private fun request() = AgentTools.request("sms_send", JSONObject().put("to", "+18015550100").put("body", "hi"))!!

    private suspend fun answered(approved: Boolean): Boolean = kotlinx.coroutines.coroutineScope {
        val request = request()
        val waiting = async { runtime.approveOnPhone(request, timeoutMs = 10_000) }
        withTimeout(5_000) { while (runtime.state.value.approvals.none { it.id == request.id }) yield() }
        runtime.answer(AgentTools.LANE, mapOf(request.id to Protocol.Answer(approved)))
        waiting.await()
    }

    @Test
    fun approveRunsAndDenyRefuses() = runBlocking {
        assertTrue(answered(approved = true))
        assertFalse(answered(approved = false))
        assertTrue(runtime.state.value.approvals.isEmpty())
    }

    @Test
    fun noAnswerInTimeIsANo() = runBlocking {
        val request = request()
        assertFalse(runtime.approveOnPhone(request, timeoutMs = 50))
        assertTrue(runtime.state.value.approvals.isEmpty())
        assertEquals("1 denied", runtime.state.value.log.last().text)
    }
}
