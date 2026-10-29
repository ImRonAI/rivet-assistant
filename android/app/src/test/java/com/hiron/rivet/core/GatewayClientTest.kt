package com.hiron.rivet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs the client against a real WebSocket server (MockWebServer) standing in for the brain's gateway. */
class GatewayClientTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = LinkedBlockingQueue<ServerEvent>()
    private val connections = CopyOnWriteArrayList<Connection>()
    private lateinit var client: GatewayClient

    /** Messages one accepted socket received, plus a handle to talk back on it. */
    private class Brain : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val opened = LinkedBlockingQueue<WebSocket>()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.put(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.put(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Before
    fun setUp() {
        server.start()
        client = GatewayClient(OkHttpClient(), scope, { events.put(it) }, { connections += it }, backoffMillis = { 20L })
    }

    @After
    fun tearDown() {
        client.disconnect()
        scope.cancel()
        server.close()
    }

    private fun config() = GatewayConfig(server.url("/").toString(), "device-token-123456789012345", "phone-1")

    private fun LinkedBlockingQueue<String>.next(): JSONObject = JSONObject(poll(5, TimeUnit.SECONDS) ?: error("no message"))

    @Test
    fun authenticatesResumesAndDeliversEvents() {
        val brain = Brain()
        server.enqueue(MockResponse().withWebSocketUpgrade(brain))
        client.connect(config())
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/sessions/phone-1", request.path)
        assertEquals("Bearer device-token-123456789012345", request.getHeader("Authorization"))
        assertEquals("resume", brain.received.next().getString("type"))

        brain.opened.poll(5, TimeUnit.SECONDS)!!.send("""{"type":"text","delta":"hello","lane":"default"}""")
        assertEquals(ServerEvent.TextDelta("hello", "default"), events.poll(5, TimeUnit.SECONDS))
        assertTrue(connections.contains(Connection.Online))
    }

    @Test
    fun queuesWhileOfflineAndFlushesAfterResume() {
        client.send(Protocol.turn("queued", "t-1"))
        val brain = Brain()
        server.enqueue(MockResponse().withWebSocketUpgrade(brain))
        client.connect(config())
        assertEquals("resume", brain.received.next().getString("type"))
        assertEquals("t-1", brain.received.next().getString("turn_id"))
    }

    @Test
    fun reconnectsAfterTheBrainDropsAndResumesAgain() {
        val first = Brain()
        val second = Brain()
        server.enqueue(MockResponse().withWebSocketUpgrade(first))
        server.enqueue(MockResponse().withWebSocketUpgrade(second))
        client.connect(config())
        assertEquals("resume", first.received.next().getString("type"))
        first.opened.poll(5, TimeUnit.SECONDS)!!.close(1012, "restart")
        assertEquals("resume", second.received.next().getString("type"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRejectedTokenIsReported() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        client.connect(config())
        val event = events.poll(5, TimeUnit.SECONDS)
        assertEquals(ServerEvent.Error("The brain rejected this phone's device token"), event)
    }

    @Test
    fun socketUrlNormalisesSchemes() {
        assertEquals("https://b.dev/v1/sessions/s", GatewayConfig("wss://b.dev/", "t", "s").socketUrl())
        assertEquals("http://10.0.2.2:8080/v1/sessions/s", GatewayConfig("ws://10.0.2.2:8080", "t", "s").socketUrl())
        assertEquals("https://b.dev/v1/sessions/s", GatewayConfig("b.dev", "t", "s").socketUrl())
    }
}
