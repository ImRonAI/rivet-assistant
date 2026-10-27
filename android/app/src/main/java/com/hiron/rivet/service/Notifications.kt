package com.hiron.rivet.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hiron.rivet.MainActivity
import com.hiron.rivet.R
import com.hiron.rivet.core.ApprovalRequest
import com.hiron.rivet.core.CallText
import com.hiron.rivet.core.LiveCall
import com.hiron.rivet.core.LiveCallStatus
import com.hiron.rivet.core.ServerEvent

/** Rivet's notification channels and the notifications it posts. */
object Notifications {
    const val SERVICE = "rivet.service"
    const val APPROVALS = "rivet.approvals"
    const val REMINDERS = "rivet.reminders"
    const val ACTIONS = "rivet.actions"
    const val CALLS = "rivet.calls"
    const val SERVICE_ID = 1
    private const val APPROVAL_BASE = 1000
    private const val ACTION_BASE = 5000
    private const val REMINDER_BASE = 9000
    private const val CALL_ANSWERING_ID = 14000
    private const val CALL_LIVE_ID = 14001
    private const val CALL_MESSAGE_BASE = 10000
    private const val CALL_SUMMARY_BASE = 15000
    private const val CALL_LIVE_TIMEOUT = 35 * 60_000L
    private const val TRANSFERRING = "Transferring the call to you…"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(SERVICE, "Rivet connection", NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rivet asks before it sends, buys, deletes or changes anything"
                },
                NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(ACTIONS, "Ready to open", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Drafts and apps Rivet has ready for you"
                },
                NotificationChannel(CALLS, "Calls Rivet answered", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Calls Rivet's answering line picked up and the messages callers left"
                },
            ),
        )
    }

    fun service(context: Context, status: String) =
        NotificationCompat.Builder(context, SERVICE)
            .setSmallIcon(R.drawable.ic_rivet)
            .setContentTitle("Rivet")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .build()

    /**
     * One notification per approval. Lower tiers get Approve/Deny actions that need the phone unlocked;
     * high tiers (purchases, deletes, anything unclassified) only open the app, where biometrics are required.
     */
    fun approval(context: Context, request: ApprovalRequest, reviewOnly: Boolean = request.high) {
        val builder = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_rivet)
            .setContentTitle(if (request.high) "Rivet needs your OK (high risk)" else "Rivet needs your OK")
            .setContentText(request.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.summary + "\n" + request.preview.take(300)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        if (!reviewOnly) {
            builder.addAction(answer(context, request, approved = true))
            builder.addAction(answer(context, request, approved = false))
        } else {
            builder.addAction(NotificationCompat.Action.Builder(0, "Review", openApp(context)).build())
        }
        post(context, APPROVAL_BASE + request.id.hashCode().mod(4000), builder)
    }

    fun clearApproval(context: Context, id: String) =
        NotificationManagerCompat.from(context).cancel(APPROVAL_BASE + id.hashCode().mod(4000))

    fun reminder(context: Context, id: String, text: String) = post(
        context, REMINDER_BASE + id.hashCode().mod(1000),
        NotificationCompat.Builder(context, REMINDERS)
            .setSmallIcon(R.drawable.ic_rivet)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .setAutoCancel(true),
    )

    fun callAnswering(context: Context, from: String) = post(
        context, CALL_ANSWERING_ID,
        NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(R.drawable.ic_rivet)
            .setContentTitle("Rivet is answering a call")
            .setContentText("From ${from.ifBlank { "an unknown number" }}")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setTimeoutAfter(10 * 60_000L)
            .setContentIntent(openApp(context))
            .setAutoCancel(true),
    )

    fun callMessage(context: Context, event: ServerEvent.CallMessage) {
        NotificationManagerCompat.from(context).cancel(CALL_ANSWERING_ID)
        post(
            context, CALL_MESSAGE_BASE + (event.callbackNumber + event.message).hashCode().mod(4000),
            NotificationCompat.Builder(context, CALLS)
                .setSmallIcon(R.drawable.ic_rivet)
                .setContentTitle((if (event.urgent) "Urgent message from " else "Message from ") + CallText.caller(event))
                .setContentText(event.message.ifBlank { "(no message)" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(CallText.describe(event)))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(if (event.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openApp(context))
                .setAutoCancel(true),
        )
    }

    /**
     * The ongoing notification while Rivet is on a call: the latest lines and, while it is answering, "Take over"
     * (which needs the phone unlocked). It replaces the "answering" one and stays quiet, since that one alerted.
     */
    fun callLive(context: Context, call: LiveCall, notice: String? = null) {
        if (call.status == LiveCallStatus.Ended) return
        NotificationManagerCompat.from(context).cancel(CALL_ANSWERING_ID)
        val answering = call.status == LiveCallStatus.Answering
        val status = notice ?: if (answering) null else TRANSFERRING
        val recent = call.lines.takeLast(4).map(CallText::line)
        val body = (recent + listOfNotNull(status)).joinToString("\n").ifBlank { "Listening…" }
        val builder = NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(R.drawable.ic_rivet)
            .setContentTitle("Rivet is answering ${call.caller}")
            .setContentText(status ?: recent.lastOrNull() ?: "Listening…")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(call.startedAt)
            .setUsesChronometer(true)
            .setTimeoutAfter(CALL_LIVE_TIMEOUT)
            .setContentIntent(openApp(context))
        if (answering) builder.addAction(takeOver(context, call.callId))
        post(context, CALL_LIVE_ID, builder)
    }

    /** Replaces the live notification with the call's summary. */
    fun callEnded(context: Context, call: LiveCall) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(CALL_LIVE_ID)
        manager.cancel(CALL_ANSWERING_ID)
        post(
            context, CALL_SUMMARY_BASE + call.callId.hashCode().mod(1000),
            NotificationCompat.Builder(context, CALLS)
                .setSmallIcon(R.drawable.ic_rivet)
                .setContentTitle((if (call.takenOver) "You took over the call from " else "Call from ") + call.caller)
                .setContentText(call.summary.ifBlank { "Rivet has no summary of this call." })
                .setStyle(NotificationCompat.BigTextStyle().bigText(CallText.summary(call)))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setSilent(true)
                .setContentIntent(openApp(context))
                .setAutoCancel(true),
        )
    }

    fun action(context: Context, title: String, text: String, intent: Intent) {
        val id = ACTION_BASE + (title + text).hashCode().mod(4000)
        post(
            context, id,
            NotificationCompat.Builder(context, ACTIONS)
                .setSmallIcon(R.drawable.ic_rivet)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true),
        )
    }

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

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
