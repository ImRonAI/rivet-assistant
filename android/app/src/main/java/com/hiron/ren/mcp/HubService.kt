package com.hiron.ren.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.hiron.ren.core.ren
import com.hiron.ren.service.Notifications
import kotlinx.coroutines.launch

/**
 * Keeps the phone attached to Ren's hub while Remote tools is on, so the agents and ren desktop can use the phone's
 * tools with the app in the background. Its notification says where the link stands.
 *
 * Android 12 and later refuse a foreground service started from the background, so it is started only where Android
 * allows it: from Ren on screen ([start] in MainActivity), and after a reboot or an update of Ren ([Starter]). Ren's
 * process also starts in the background for its accessibility service and notification listener, and starting this
 * there would crash it before either connects.
 */
class HubService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, Notifications.hub(this, ren.hub.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        ren.hub.start()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            ren.hub.state.collect { Notifications.update(this@HubService, NOTIFICATION_ID, Notifications.hub(this@HubService, it)) }
        }
    }

    override fun onDestroy() {
        ren.hub.stop()
        super.onDestroy()
    }

    /** Brings the link back after a reboot (BOOT_COMPLETED) or an update of Ren (MY_PACKAGE_REPLACED). */
    class Starter : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            start(context)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 996

        /** Starts the link if Remote tools is on. */
        fun start(context: Context) {
            if (context.ren.settings.hubEnabled) ContextCompat.startForegroundService(context, Intent(context, HubService::class.java))
        }
    }
}
