package com.hiron.emrys.voice

import com.hiron.emrys.core.Decision
import com.hiron.emrys.core.Protocol
import com.hiron.emrys.core.EmrysStore
import com.hiron.emrys.core.emrys
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val runtime get() = RuntimeEnvironment.getApplication().emrys

    private fun request() = requireNotNull(AgentAlerts.request("sms_send", JSONObject().put("to", "+18015550100").put("body", "hi"), false, null))

    private suspend fun answered(answer: Protocol.Answer): Protocol.Answer = kotlinx.coroutines.coroutineScope {
        val request = request()
        val waiting = async { runtime.approveOnPhone(request) }
        withTimeout(5_000) { while (runtime.state.value.approvals.none { it.id == request.id }) yield() }
        runtime.answer(AgentTools.LANE, mapOf(request.id to answer))
        waiting.await()
    }

    @Test
    fun approveRunsAndDenyRefuses() = runBlocking {
        assertTrue(answered(Protocol.Answer(true)).approved)
        assertEquals("Approved: Text +18015550100: “hi”", runtime.state.value.log.last().text)
        assertFalse(answered(Protocol.Answer(false)).approved)
        assertEquals("Declined: Text +18015550100: “hi”", runtime.state.value.log.last().text)
        assertEquals(Protocol.Answer(false, Decision.Deny), answered(Protocol.Answer(false, Decision.Deny)))
        assertEquals("Always declined: Texts to +18015550100", runtime.state.value.log.last().text)
        assertTrue(runtime.state.value.approvals.isEmpty())
    }

    @Test
    fun aRequestTheAgentStopsWaitingForLeavesTheSheet() = runBlocking {
        val request = request()
        assertNull(withTimeoutOrNull(50) { runtime.approveOnPhone(request) })
        assertTrue(runtime.state.value.approvals.isEmpty())
        assertEquals(EmrysStore.EXPIRED, runtime.state.value.log.last().text)
    }
}
