package com.hiron.ren.mcp

import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.hiron.ren.core.ren
import com.hiron.ren.service.Notifications
import kotlinx.coroutines.launch

/**
 * Keeps the phone attached to Ren's hub while Remote tools is on, so the agents and ren desktop can use the phone's
 * tools with the app in the background. Its notification says where the link stands.
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

    companion object {
        const val NOTIFICATION_ID = 996
    }
}
