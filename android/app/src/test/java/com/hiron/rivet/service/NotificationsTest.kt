package com.hiron.rivet.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.hiron.rivet.core.CallLineText
import com.hiron.rivet.core.LiveCall
import com.hiron.rivet.core.LiveCallStatus
import com.hiron.rivet.core.NameLookup
import com.hiron.rivet.core.Protocol
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.testing.textApproval
import com.hiron.rivet.voice.AgentTools
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** One notification per call, approvals that leave when the app shows them, and group summaries only for two or more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun allow() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    private fun active(): List<StatusBarNotification> = manager.activeNotifications.toList()

    private fun inGroup(group: Notifications.Group) = active().filter { it.notification.group == group.key && it.id != group.summaryId }

    private fun text(n: Notification, key: String) = n.extras.getCharSequence(key)?.toString()

    @Test
    fun oneNotificationFollowsACallFromAnsweringToItsSummary() {
        val id = "c-0123456789ab"
        val nid = Notifications.callId(id)
        Notifications.callAnswering(app, id, CallNote("+18015550100"))
        assertEquals(listOf(nid), inGroup(Notifications.Group.Calls).map { it.id })
        assertEquals("Rivet is answering a call", text(active().single { it.id == nid }.notification, Notification.EXTRA_TITLE))

        val call = LiveCall(id, "+18015550100", "+18015550100", true, 0, LiveCallStatus.Answering, listOf(CallLineText("caller", "Hi, it's Ada")))
        Notifications.callLive(app, call)
        assertEquals(listOf(nid), inGroup(Notifications.Group.Calls).map { it.id })
        val live = active().single { it.id == nid }.notification
        assertTrue(live.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(live.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)

        Notifications.callLive(app, call.copy(message = "Lunch Friday?", urgent = true), alert = true)
        assertEquals(listOf(nid), inGroup(Notifications.Group.Calls).map { it.id })
        val urgent = active().single { it.id == nid }.notification
        assertTrue(text(urgent, Notification.EXTRA_BIG_TEXT).orEmpty().endsWith("Urgent message: Lunch Friday?"))
        assertTrue(urgent.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)

        Notifications.callEnded(app, call.copy(status = LiveCallStatus.Ended, caller = "Ada", summary = "Ada wants lunch.", outcome = "message_taken", message = "Lunch Friday?"))
        val ended = inGroup(Notifications.Group.Calls).single()
        assertEquals(nid, ended.id)
        assertEquals("Call from Ada", text(ended.notification, Notification.EXTRA_TITLE))
        assertTrue(text(ended.notification, Notification.EXTRA_BIG_TEXT).orEmpty().endsWith("\nMessage: Lunch Friday?"))
        assertFalse(ended.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNull(active().firstOrNull { it.id == Notifications.CALL_SUMMARY_ID })
    }

    @Test
    fun aSummaryExistsOnlyForTwoOrMore() {
        Notifications.approval(app, textApproval("a1"))
        assertNull(active().firstOrNull { it.id == Notifications.APPROVAL_SUMMARY_ID })
        Notifications.approval(app, textApproval("a2", to = "+15550000000"))
        val summary = active().single { it.id == Notifications.APPROVAL_SUMMARY_ID }.notification
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        assertEquals("2 things need your OK", text(summary, Notification.EXTRA_TITLE))
        Notifications.clearApproval(app, "a2")
        assertNull(active().firstOrNull { it.id == Notifications.APPROVAL_SUMMARY_ID })
        assertEquals(1, inGroup(Notifications.Group.Approvals).size)
    }

    @Test
    fun anApprovalIsTitledWithWhatRivetWantsAndWhoItIsFor() {
        val request = textApproval("a1", because = null)
        Notifications.approval(app, request, names = mapOf("+18015550100" to "Dana"))
        val n = active().single { it.id == Notifications.approvalId("a1") }.notification
        assertEquals("Text Dana: “Friday at 9 works!”", text(n, Notification.EXTRA_TITLE))
        assertEquals("Sends a message · you can't undo it", text(n, Notification.EXTRA_TEXT))
        assertEquals("To: Dana · +1 801-555-0100\nMessage: Friday at 9 works!", text(n, Notification.EXTRA_BIG_TEXT))
    }

    @Test
    fun theRuntimeNamesThePersonAndClearsApprovalsWhenTheAppShowsThem() = runBlocking {
        val runtime = RivetRuntime(app, names = NameLookup { if (it == "+18015550100") "Dana" else null })
        runtime.visible = false
        val request = textApproval("agent:1", lane = AgentTools.LANE)
        coroutineScope {
            val waiting = async { runtime.approveOnPhone(request, timeoutMs = 10_000) }
            withTimeout(5_000) { while (active().none { it.id == Notifications.approvalId("agent:1") }) yield() }
            val posted = active().single { it.id == Notifications.approvalId("agent:1") }.notification
            assertEquals("Text Dana: “Friday at 9 works!”", text(posted, Notification.EXTRA_TITLE))
            assertEquals(mapOf("+18015550100" to "Dana"), runtime.state.value.names)

            runtime.visible = true
            assertTrue(active().none { it.id == Notifications.approvalId("agent:1") })
            runtime.answer(AgentTools.LANE, mapOf("agent:1" to Protocol.Answer(true)))
            assertTrue(waiting.await().approved)
        }
    }
}
