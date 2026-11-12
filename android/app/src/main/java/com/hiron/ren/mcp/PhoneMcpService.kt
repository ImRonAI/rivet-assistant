package com.hiron.ren.mcp

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.hiron.ren.core.ren
import com.hiron.ren.service.Notifications
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the phone's MCP server up while the phone is on the tailnet: it starts on the Tailscale address when the
 * VPN comes up, stops when it goes, and shows where it is listening in its notification.
 */
class PhoneMcpService : LifecycleService() {
    private val lock = Mutex()
    private var running: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private lateinit var server: Server
    private lateinit var connectivity: ConnectivityManager

    private val vpn = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = restart()
        override fun onLost(network: Network) = restart()
    }

    override fun onCreate() {
        super.onCreate()
        server = ren.phoneServer.build()
        connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), vpn,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, Notifications.server(this, address = null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        restart()
        return START_STICKY
    }

    /** Stops the server, then starts it on the current Tailscale address when there is one. */
    private fun restart() {
        lifecycleScope.launch(Dispatchers.IO) {
            lock.withLock {
                running?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
                running = null
                val address = tailnetAddress()?.hostAddress
                if (address != null) {
                    running = phoneMcpApplication(server, host = address, token = ren.settings.phoneMcpToken).start(wait = false)
                }
                Notifications.update(this@PhoneMcpService, NOTIFICATION_ID, Notifications.server(this@PhoneMcpService, address))
            }
        }
    }

    override fun onDestroy() {
        connectivity.unregisterNetworkCallback(vpn)
        running?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        running = null
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 996
    }
}
