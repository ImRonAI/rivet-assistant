package com.hiron.rivet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class GatewayConfig(val url: String, val token: String, val sessionId: String) {
    val complete: Boolean get() = url.isNotBlank() && token.isNotBlank() && sessionId.isNotBlank()

    /** `https://host` (or `wss://host`) to the session's socket URL; OkHttp upgrades http(s) URLs itself. */
    fun socketUrl(): String = "${baseUrl()}/v1/sessions/$sessionId"

    /** The brain's `https://host`, however the URL was typed. */
    fun baseUrl(): String {
        val base = url.trim().trimEnd('/')
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
        return if (base.startsWith("http://") || base.startsWith("https://")) base else "https://$base"
    }
}

/**
 * The phone's connection to its session on the brain.
 *
 * Sends `resume` on every (re)connect so the brain re-sends pending approvals and buffered events, reconnects with
 * exponential backoff while [connect] is in effect, and queues messages sent while offline.
 */
class GatewayClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val onEvent: (ServerEvent) -> Unit,
    private val onConnection: (Connection) -> Unit,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> minOf(30_000L, 1_000L shl minOf(attempt, 5)) },
) {
    private val lock = Any()
    private val outbox = ArrayDeque<String>()
    private var socket: WebSocket? = null
    private var online = false
    private var wanted = false
    private var attempt = 0
    private var reconnect: Job? = null
    private var config: GatewayConfig? = null

    fun connect(config: GatewayConfig) {
        synchronized(lock) {
            this.config = config
            wanted = true
            attempt = 0
            reconnect?.cancel()
            socket?.cancel()
            socket = null
            online = false
        }
        open()
    }

    fun disconnect() {
        synchronized(lock) {
            wanted = false
            reconnect?.cancel()
            socket?.close(1000, "bye")
            socket = null
            online = false
        }
        onConnection(Connection.Offline)
    }

    /** Sends now when online, otherwise when the connection comes back. */
    fun send(message: String) {
        synchronized(lock) {
            val s = socket
            if (online && s != null && s.send(message)) return
            outbox.addLast(message)
        }
    }

    private fun open() {
        val cfg = synchronized(lock) { if (!wanted) null else config } ?: return
        onConnection(Connection.Connecting)
        val request = Request.Builder()
            .url(cfg.socketUrl())
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        val ws = http.newWebSocket(request, Listener())
        synchronized(lock) { socket = ws }
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (!wanted) return
            val wait = backoffMillis(attempt++)
            reconnect?.cancel()
            reconnect = scope.launch {
                delay(wait)
                open()
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val pending: List<String>
            synchronized(lock) {
                if (socket !== webSocket) return
                online = true
                attempt = 0
                webSocket.send(Protocol.resume())
                pending = outbox.toList()
                outbox.clear()
            }
            pending.forEach { if (!webSocket.send(it)) send(it) }
            onConnection(Connection.Online)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { socket === webSocket }) onEvent(Protocol.parse(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, response?.code)

        private fun lost(webSocket: WebSocket, httpCode: Int?) {
            val retry = synchronized(lock) {
                if (socket !== webSocket) return
                socket = null
                online = false
                wanted
            }
            if (httpCode == 401 || httpCode == 403) onEvent(ServerEvent.Error("The brain rejected this phone's device token"))
            onConnection(if (retry) Connection.Connecting else Connection.Offline)
            if (retry) scheduleReconnect()
        }
    }
}
