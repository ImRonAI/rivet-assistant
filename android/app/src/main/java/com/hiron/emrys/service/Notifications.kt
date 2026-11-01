package com.hiron.emrys.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hiron.emrys.MainActivity
import com.hiron.emrys.R
import com.hiron.emrys.core.ApprovalRequest

/**
 * Emrys's notification channels and the notifications it posts. Each kind is one group whose summary exists only
 * while the group has two or more notifications.
 */
object Notifications {
    const val APPROVALS = "emrys.approvals"
    const val REMINDERS = "emrys.reminders"
    const val ACTIONS = "emrys.actions"
    const val APPROVAL_SUMMARY_ID = 999
    const val ACTION_SUMMARY_ID = 998
    const val REMINDER_SUMMARY_ID = 997
    private val APPROVAL_IDS = 1000..4999
    private val ACTION_IDS = 5000..8999
    private val REMINDER_IDS = 9000..9999

    /** The id ranges single notifications hash into; the group summary ids sit outside all of them. */
    internal val CHILD_ID_RANGES: List<IntRange> = listOf(APPROVAL_IDS, ACTION_IDS, REMINDER_IDS)

    /** A group of notifications and its summary. */
    enum class Group(val key: String, val summaryId: Int, val channel: String) {
        Approvals("emrys.group.approvals", APPROVAL_SUMMARY_ID, APPROVALS),
        Reminders("emrys.group.reminders", REMINDER_SUMMARY_ID, REMINDERS),
        Actions("emrys.group.actions", ACTION_SUMMARY_ID, ACTIONS);

        fun title(count: Int): String = when (this) {
            Approvals -> "$count things need your OK"
            Reminders -> "$count reminders"
            Actions -> "$count things ready to open"
        }
    }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Emrys asks before it sends, buys, deletes or changes anything"
                },
                NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(ACTIONS, "Ready to open", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Drafts and apps Emrys has ready for you"
                },
            ),
        )
    }

    /** An approval waiting while Emrys is in the background: tapping it opens the app, where the sheet asks. */
    fun approval(context: Context, request: ApprovalRequest) = post(
        context, approvalId(request.id),
        NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_emrys)
            .setColor(context.getColor(R.color.emrys_accent))
            .setContentTitle("Emrys needs your OK: ${request.action.label}")
            .setContentText(request.details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.details))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setAutoCancel(true),
        Group.Approvals,
    )

    fun clearApproval(context: Context, id: String) = clearApprovals(context, listOf(id))

    fun clearApprovals(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        ids.forEach { manager.cancel(approvalId(it)) }
        refresh(context, Group.Approvals)
    }

    fun approvalId(id: String): Int = slot(APPROVAL_IDS, id)

    fun reminder(context: Context, id: String, text: String) = post(
        context, slot(REMINDER_IDS, id),
        NotificationCompat.Builder(context, REMINDERS)
            .setSmallIcon(R.drawable.ic_emrys)
            .setColor(context.getColor(R.color.emrys_accent))
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .setAutoCancel(true),
        Group.Reminders,
    )

    fun action(context: Context, title: String, text: String, intent: Intent) {
        val id = slot(ACTION_IDS, title + text)
        post(
            context, id,
            NotificationCompat.Builder(context, ACTIONS)
                .setSmallIcon(R.drawable.ic_emrys)
                .setColor(context.getColor(R.color.emrys_accent))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true),
            Group.Actions,
        )
    }

    /** Posts, updates or cancels [group]'s summary: it exists only while the group has two or more notifications. */
    fun refresh(context: Context, group: Group) {
        val manager = NotificationManagerCompat.from(context)
        val children = runCatching { manager.activeNotifications }.getOrDefault(emptyList())
            .filter { it.notification.group == group.key && it.id != group.summaryId }
        if (children.size < 2) {
            manager.cancel(group.summaryId)
            return
        }
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(group.title(children.size))
        children.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) }.forEach { inbox.addLine(it) }
        val builder = NotificationCompat.Builder(context, group.channel)
            .setSmallIcon(R.drawable.ic_emrys)
            .setColor(context.getColor(R.color.emrys_accent))
            .setContentTitle(group.title(children.size))
            .setStyle(inbox)
            .setGroup(group.key)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        notify(context, group.summaryId, builder)
    }

    private fun slot(ids: IntRange, key: String): Int = ids.first + key.hashCode().mod(ids.last - ids.first + 1)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder, group: Group) {
        if (!notify(context, id, builder.setGroup(group.key))) return
        refresh(context, group)
    }

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return false
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
        return true
    }
}
