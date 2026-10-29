package com.hiron.rivet.device

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the phone's notifications and replies to them through their own reply actions, which is how Rivet
 * answers texts in any messaging app (SMS, WhatsApp, Signal, ...) without holding that app's account.
 */
class RivetNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: RivetNotificationListener? = null

        val connected: Boolean get() = instance != null

        fun list(context: Context, app: String?): ToolResult {
            val listener = instance ?: return notConnected()
            val needle = app?.lowercase()
            val rows = JSONArray()
            listener.activeNotifications.orEmpty()
                .filter { it.packageName != context.packageName }
                .sortedByDescending { it.postTime }
                .forEach { sbn ->
                    val r = record(listener, sbn) ?: return@forEach
                    if (needle != null && !r.optString("app").lowercase().contains(needle) && !sbn.packageName.contains(needle)) {
                        return@forEach
                    }
                    if (rows.length() < 40) rows.put(r)
                }
            return ToolResult.ok(JSONObject().put("notifications", rows))
        }

        fun reply(context: Context, key: String, text: String): ToolResult {
            val listener = instance ?: return notConnected()
            val sbn = listener.activeNotifications.orEmpty().firstOrNull { it.key == key }
                ?: return ToolResult.error("That notification is gone (read or dismissed). List notifications again.")
            val action = replyAction(sbn.notification)
                ?: return ToolResult.error("${appLabel(context, sbn.packageName)} doesn't offer a reply on this notification.")
            val inputs = action.remoteInputs
            val results = Bundle().apply { inputs.filter { it.allowFreeFormInput }.forEach { putCharSequence(it.resultKey, text) } }
            val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, fill, results)
            action.actionIntent.send(context, 0, fill)
            return ToolResult.ok(
                JSONObject().put("replied", true).put("app", appLabel(context, sbn.packageName))
                    .put("to", sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()),
            )
        }

        private fun record(context: Context, sbn: StatusBarNotification): JSONObject? {
            val n = sbn.notification
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return null
            val messages = JSONArray()
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages?.takeLast(6)?.forEach { m ->
                messages.put(
                    JSONObject().put("from", m.person?.name?.toString() ?: "me").put("text", m.text?.toString().orEmpty())
                        .put("at", Times.format(m.timestamp)),
                )
            }
            return JSONObject()
                .put("key", sbn.key)
                .put("app", appLabel(context, sbn.packageName))
                .put("package", sbn.packageName)
                .put("title", title)
                .put("text", text.take(1000))
                .put("messages", messages)
                .put("category", n.category ?: JSONObject.NULL)
                .put("at", Times.format(sbn.postTime))
                .put("can_reply", replyAction(n) != null)
        }

        private fun replyAction(n: Notification): Notification.Action? =
            n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

        private fun appLabel(context: Context, pkg: String): String = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

        private fun notConnected() = ToolResult.error(
            "Rivet can't see notifications yet. Ask the user to open Rivet › Settings › Permissions › Notification access " +
                "and turn Rivet on.",
        )
    }
}
