package com.hiron.rivet.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.rivet

/**
 * Keeps Rivet's brain connection alive while the app is in the background, so texts, device calls and
 * approvals keep flowing. Started when the user connects; stopped when they disconnect.
 */
class RivetService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            rivet.disconnect()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifications.SERVICE_ID, Notifications.service(this, label(rivet.state.value.connection)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        rivet.connect()
        return START_STICKY
    }

    companion object {
        private const val STOP = "com.hiron.rivet.STOP"

        /**
         * Starts the service, or, where Android forbids a foreground start (the process was started in the
         * background, e.g. to bind the notification listener), connects for as long as the process lives.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RivetService::class.java))
            } catch (e: IllegalStateException) {
                context.rivet.connect()
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RivetService::class.java).setAction(STOP))
        }

        /** Refreshes the ongoing notification's text (no-op when the service isn't running). */
        fun status(context: Context, connection: Connection) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.activeNotifications.none { it.id == Notifications.SERVICE_ID }) return
            manager.notify(Notifications.SERVICE_ID, Notifications.service(context, label(connection)))
        }

        private fun label(connection: Connection) = when (connection) {
            Connection.Online -> "Connected and listening for tasks"
            Connection.Connecting -> "Connecting to the brain…"
            Connection.Offline -> "Offline"
        }
    }
}

/** Approve/Deny from an approval notification (Android requires the phone to be unlocked first). */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ID) ?: return
        val lane = intent.getStringExtra(LANE) ?: "default"
        context.rivet.answerOne(lane, id, approved = intent.action == APPROVE)
    }

    companion object {
        const val APPROVE = "com.hiron.rivet.APPROVE"
        const val DENY = "com.hiron.rivet.DENY"
        const val ID = "id"
        const val LANE = "lane"
    }
}

/** Reconnects after a reboot when the user turned on "Connect automatically". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val runtime = context.rivet
        if (runtime.settings.autoConnect && runtime.settings.gateway().complete) RivetService.start(context)
    }
}
