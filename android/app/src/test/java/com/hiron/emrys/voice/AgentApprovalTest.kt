package com.hiron.emrys.voice

import com.hiron.emrys.core.ApprovalAnswer
import com.hiron.emrys.core.EmrysStore
import com.hiron.emrys.core.PermAction
import com.hiron.emrys.core.emrys
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

/** `request_approval`: the agent waits on the approval sheet and gets `{"approved", "remember_decision"}` back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentApprovalTest {
    private val runtime get() = RuntimeEnvironment.getApplication().emrys
    private val tool get() = AgentTools.approval(runtime::approve)
    private val details = "Text Dana at +1 801-555-0100: “hi”"

    private suspend fun answered(answer: ApprovalAnswer): JSONObject = coroutineScope {
        val waiting = async { tool.execute(mapOf("action_type" to "send_messages", "details" to details)) }
        withTimeout(5_000) { while (runtime.state.value.approvals.isEmpty()) yield() }
        val request = runtime.state.value.approvals.single()
        assertEquals(PermAction.SendMessages, request.action)
        assertEquals(details, request.details)
        runtime.answer(request.id, answer)
        val result = requireNotNull(waiting.await())
        assertTrue(result.success)
        JSONObject(result.result)
    }

    @Test
    fun approveDenyAndAlwaysAllowReachTheAgent() = runBlocking {
        val approved = answered(ApprovalAnswer(true))
        assertTrue(approved.getBoolean("approved"))
        assertFalse(approved.getBoolean("remember_decision"))
        assertEquals("Approved: $details", runtime.state.value.log.last().text)

        assertFalse(answered(ApprovalAnswer(false)).getBoolean("approved"))
        assertEquals("Declined: $details", runtime.state.value.log.last().text)
        assertFalse(runtime.settings.granted(PermAction.SendMessages))

        assertTrue(answered(ApprovalAnswer(true, remember = true)).getBoolean("remember_decision"))
        assertTrue(runtime.settings.granted(PermAction.SendMessages))
        assertEquals("granted", runtime.settings.permissionVariables()["perm_send_messages"])
        assertTrue(runtime.state.value.approvals.isEmpty())
    }

    @Test
    fun anUnknownActionTypeIsRefusedWithoutAsking() = runBlocking {
        val result = requireNotNull(tool.execute(mapOf("action_type" to "launch_rockets", "details" to "x")))
        assertFalse(result.success)
        assertTrue(result.error.orEmpty().startsWith("Unknown action_type \"launch_rockets\""))
        assertTrue(runtime.state.value.approvals.isEmpty())
    }

    @Test
    fun aRequestTheAgentStopsWaitingForLeavesTheSheet() = runBlocking {
        assertNull(withTimeoutOrNull(50) { tool.execute(mapOf("action_type" to "purchases", "details" to "Buy it")) })
        assertTrue(runtime.state.value.approvals.isEmpty())
        assertEquals(EmrysStore.EXPIRED, runtime.state.value.log.last().text)
    }
}
