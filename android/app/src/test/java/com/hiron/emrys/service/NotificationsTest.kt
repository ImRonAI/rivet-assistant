package com.hiron.emrys.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.hiron.emrys.core.ApprovalAnswer
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.core.PermAction
import com.hiron.emrys.testing.textApproval
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Approvals that leave when the app shows them, and group summaries only for two or more. */
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
    fun summaryIdsSitOutsideEveryChildRange() {
        val ranges = Notifications.CHILD_ID_RANGES
        ranges.forEachIndexed { i, a -> ranges.drop(i + 1).forEach { b -> assertTrue("$a overlaps $b", a.last < b.first || b.last < a.first) } }
        val fixed = Notifications.Group.entries.map { it.summaryId }
        assertEquals(fixed.size, fixed.toSet().size)
        fixed.forEach { id -> assertTrue("$id is inside a child range", ranges.none { id in it }) }
    }

    @Test
    fun aSummaryExistsOnlyForTwoOrMore() {
        Notifications.approval(app, textApproval("a1"))
        assertNull(active().firstOrNull { it.id == Notifications.APPROVAL_SUMMARY_ID })
        Notifications.approval(app, textApproval("a2", details = "Text Sam: “On my way”"))
        val summary = active().single { it.id == Notifications.APPROVAL_SUMMARY_ID }.notification
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        assertEquals("2 things need your OK", text(summary, Notification.EXTRA_TITLE))
        Notifications.clearApproval(app, "a2")
        assertNull(active().firstOrNull { it.id == Notifications.APPROVAL_SUMMARY_ID })
        assertEquals(1, inGroup(Notifications.Group.Approvals).size)
    }

    @Test
    fun anApprovalIsTitledWithWhatEmrysWantsToDo() {
        val request = textApproval("a1")
        Notifications.approval(app, request)
        val n = active().single { it.id == Notifications.approvalId("a1") }.notification
        assertEquals("Emrys needs your OK: Send messages", text(n, Notification.EXTRA_TITLE))
        assertEquals(request.details, text(n, Notification.EXTRA_TEXT))
    }

    @Test
    fun theRuntimeClearsApprovalsWhenTheAppShowsThemAndKeepsAnAlwaysAllow() = runBlocking {
        val runtime = EmrysRuntime(app)
        runtime.visible = false
        val request = textApproval("r1")
        coroutineScope {
            val waiting = async { runtime.approve(request) }
            withTimeout(5_000) { while (active().none { it.id == Notifications.approvalId("r1") }) yield() }
            assertEquals(listOf(request), runtime.state.value.approvals)

            runtime.visible = true
            assertTrue(active().none { it.id == Notifications.approvalId("r1") })
            runtime.answer("r1", ApprovalAnswer(approved = true, remember = true))
            assertEquals(ApprovalAnswer(approved = true, remember = true), waiting.await())
        }
        assertTrue(runtime.state.value.approvals.isEmpty())
        assertTrue(runtime.settings.granted(PermAction.SendMessages))
        assertEquals("Always allowed: Send messages", runtime.state.value.log.last().text)
    }
}
