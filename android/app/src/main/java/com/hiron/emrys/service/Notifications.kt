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
import com.hiron.emrys.core.AlertBlock
import com.hiron.emrys.core.ApprovalRequest
import com.hiron.emrys.core.CallText
import com.hiron.emrys.core.LiveCall
import com.hiron.emrys.core.LiveCallStatus
import com.hiron.emrys.core.ServerEvent
import com.hiron.emrys.ui.Plain
import com.hiron.emrys.ui.Rich

/** Who a call Emrys is answering is from and the message the caller left, before the brain reports it live. */
data class CallNote(val from: String, val message: String = "", val urgent: Boolean = false)

/**
 * Emrys's notification channels and the notifications it posts. Each kind is one group whose summary exists only
 * while the group has two or more notifications; each call is one notification that changes in place.
 */
object Notifications {
    const val SERVICE = "emrys.service"
    const val APPROVALS = "emrys.approvals"
    const val REMINDERS = "emrys.reminders"
    const val ACTIONS = "emrys.actions"
    const val CALLS = "emrys.calls"
    const val SERVICE_ID = 1
    const val APPROVAL_SUMMARY_ID = 999
    const val ACTION_SUMMARY_ID = 998
    const val REMINDER_SUMMARY_ID = 997
    const val CALL_SUMMARY_ID = 19999
    private val APPROVAL_IDS = 1000..4999
    private val ACTION_IDS = 5000..8999
    private val REMINDER_IDS = 9000..9999
    private val CALL_MESSAGE_IDS = 10000..13999
    private val CALL_IDS = 20000..23999

    /** The id ranges single notifications hash into; the service and group summary ids sit outside all of them. */
    internal val CHILD_ID_RANGES: List<IntRange> = listOf(APPROVAL_IDS, ACTION_IDS, REMINDER_IDS, CALL_MESSAGE_IDS, CALL_IDS)
    private const val CALL_ANSWERING_TIMEOUT = 10 * 60_000L
    private const val CALL_LIVE_TIMEOUT = 35 * 60_000L
    private const val TRANSFERRING = "Transferring the call to you…"

    /** A group of notifications and its summary. */
    enum class Group(val key: String, val summaryId: Int, val channel: String) {
        Approvals("emrys.group.approvals", APPROVAL_SUMMARY_ID, APPROVALS),
        Calls("emrys.group.calls", CALL_SUMMARY_ID, CALLS),
        Reminders("emrys.group.reminders", REMINDER_SUMMARY_ID, REMINDERS),
        Actions("emrys.group.actions", ACTION_SUMMARY_ID, ACTIONS);

        fun title(count: Int): String = when (this) {
            Approvals -> "$count things need your OK"
            Calls -> "$count calls Emrys answered"
            Reminders -> "$count reminders"
            Actions -> "$count things ready to open"
        }
    }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(SERVICE, "Emrys connection", NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Emrys asks before it sends, buys, deletes or changes anything"
                },
                NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(ACTIONS, "Ready to open", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Drafts and apps Emrys has ready for you"
                },
                NotificationChannel(CALLS, "Calls Emrys answered", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Calls Emrys's answering line picked up and the messages callers left"
                },
            ),
        )
    }

    fun service(context: Context, status: String) =
        NotificationCompat.Builder(context, SERVICE)
            .setSmallIcon(R.drawable.ic_emrys)
            .setContentTitle("Emrys")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .build()

    /**
     * One notification per approval, titled with what Emrys wants to do. Lower tiers get Approve/Deny actions that
     * need the phone unlocked; high tiers (purchases, deletes, anything unclassified) only open the app, where
     * biometrics are required. [names] maps numbers to contact names.
     */
    fun approval(context: Context, request: ApprovalRequest, reviewOnly: Boolean = request.high, names: Map<String, String> = emptyMap()) {
        val text = (if (request.high) "High risk · " else "") + (request.because ?: Plain.risk(request.tier, request.tool))
        val builder = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_emrys)
            .setContentTitle(Plain.people(request.headline, names))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(approvalBody(request, names)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        if (!reviewOnly) {
            builder.addAction(answer(context, request, approved = true))
            builder.addAction(answer(context, request, approved = false))
        } else {
            builder.addAction(NotificationCompat.Action.Builder(0, "Review", openApp(context)).build())
        }
        post(context, approvalId(request.id), builder, Group.Approvals)
    }

    /** The approval's body as plain lines: each block, then why Emrys is asking. */
    fun approvalBody(request: ApprovalRequest, names: Map<String, String> = emptyMap()): String {
        val lines = request.blocks.map { block ->
            when (block) {
                is AlertBlock.Field -> "${block.label}: ${Plain.people(block.value, names, withNumber = true)}"
                is AlertBlock.Quote -> "${block.label}: ${block.text}"
                is AlertBlock.Note -> Rich.plain(block.markdown)
            }
        }
        return (lines + listOfNotNull(request.because)).joinToString("\n")
    }

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
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .setAutoCancel(true),
        Group.Reminders,
    )

    /** The id of [callId]'s one notification, which moves from answering to live to its summary. */
    fun callId(callId: String): Int = slot(CALL_IDS, callId)

    /** The call has just been picked up; [alert] posts it so that it sounds again (an urgent message arrived). */
    fun callAnswering(context: Context, callId: String, note: CallNote, alert: Boolean = false) {
        val from = "From ${note.from.ifBlank { "an unknown number" }}"
        post(
            context, callId(callId),
            NotificationCompat.Builder(context, CALLS)
                .setSmallIcon(R.drawable.ic_emrys)
                .setContentTitle("Emrys is answering a call")
                .setContentText(from)
                .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(from) + messageLines(note.message, note.urgent)).joinToString("\n")))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOnlyAlertOnce(!alert)
                .setTimeoutAfter(CALL_ANSWERING_TIMEOUT)
                .setContentIntent(openApp(context))
                .setAutoCancel(true),
            Group.Calls,
        )
    }

    /** A message for a call this phone has no notification for: its own notification in the calls group. */
    fun callMessage(context: Context, event: ServerEvent.CallMessage) = post(
        context, slot(CALL_MESSAGE_IDS, event.callbackNumber + event.message),
        NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(R.drawable.ic_emrys)
            .setContentTitle((if (event.urgent) "Urgent message from " else "Message from ") + CallText.caller(event))
            .setContentText(event.message.ifBlank { "(no message)" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(CallText.describe(event)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (event.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context))
            .setAutoCancel(true),
        Group.Calls,
    )

    /**
     * The ongoing notification while Emrys is on a call: the latest lines, the caller's message once there is one
     * and, while it is answering, "Take over" (which needs the phone unlocked). Quiet, except once for an urgent
     * message ([alert]).
     */
    fun callLive(context: Context, call: LiveCall, notice: String? = null, alert: Boolean = false) {
        if (call.status == LiveCallStatus.Ended) return
        val answering = call.status == LiveCallStatus.Answering
        val status = notice ?: if (answering) null else TRANSFERRING
        val recent = call.lines.takeLast(4).map(CallText::line)
        val body = (recent + listOfNotNull(status) + messageLines(call.message, call.urgent)).joinToString("\n").ifBlank { "Listening…" }
        val builder = NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(R.drawable.ic_emrys)
            .setContentTitle("Emrys is answering ${call.caller}")
            .setContentText(status ?: recent.lastOrNull() ?: "Listening…")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .setWhen(call.startedAt)
            .setUsesChronometer(true)
            .setTimeoutAfter(CALL_LIVE_TIMEOUT)
            .setContentIntent(openApp(context))
        if (answering) builder.addAction(takeOver(context, call.callId))
        post(context, callId(call.callId), builder, Group.Calls)
    }

    /**
     * The call's summary, in place of whatever its notification showed. It only sounds when nothing for the call was
     * showing (`setOnlyAlertOnce`).
     */
    fun callEnded(context: Context, call: LiveCall) {
        val message = messageLines(call.message, call.urgent)
        post(
            context, callId(call.callId),
            NotificationCompat.Builder(context, CALLS)
                .setSmallIcon(R.drawable.ic_emrys)
                .setContentTitle((if (call.takenOver) "You took over the call from " else "Call from ") + call.caller)
                .setContentText(call.summary.ifBlank { "Emrys has no summary of this call." })
                .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(CallText.summary(call)) + message).joinToString("\n")))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context))
                .setAutoCancel(true),
            Group.Calls,
        )
    }

    fun action(context: Context, title: String, text: String, intent: Intent) {
        val id = slot(ACTION_IDS, title + text)
        post(
            context, id,
            NotificationCompat.Builder(context, ACTIONS)
                .setSmallIcon(R.drawable.ic_emrys)
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

    private fun messageLines(message: String, urgent: Boolean): List<String> =
        if (message.isBlank()) emptyList() else listOf((if (urgent) "Urgent message: " else "Message: ") + message)

    private fun answer(context: Context, request: ApprovalRequest, approved: Boolean): NotificationCompat.Action {
        val intent = Intent(context, ApprovalActionReceiver::class.java)
            .setAction(if (approved) ApprovalActionReceiver.APPROVE else ApprovalActionReceiver.DENY)
            .putExtra(ApprovalActionReceiver.ID, request.id)
            .putExtra(ApprovalActionReceiver.LANE, request.lane)
        val pending = PendingIntent.getBroadcast(
            context, (request.id + approved).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, if (approved) "Approve" else "Deny", pending)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun takeOver(context: Context, callId: String): NotificationCompat.Action {
        val intent = Intent(context, TakeOverReceiver::class.java)
            .setAction(TakeOverReceiver.TAKE_OVER)
            .putExtra(TakeOverReceiver.CALL_ID, callId)
        val pending = PendingIntent.getBroadcast(
            context, callId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, "Take over", pending)
            .setAuthenticationRequired(true)
            .build()
    }

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
