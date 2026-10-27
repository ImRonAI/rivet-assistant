package com.hiron.rivet.core

import com.hiron.rivet.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The app's own connection code against the deployed brain (RIVET_LIVE=1): connect with the baked-in URL and
 * token, send a turn, and fold the real event stream into the state the screens render. Uses its own session.
 */
class LiveBrainTest {
    @Test
    fun theAppTalksToTheDeployedBrain() {
        assumeTrue(System.getProperty("rivet.live") == "1" && BuildConfig.DEFAULT_DEVICE_TOKEN.isNotBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = LinkedBlockingQueue<ServerEvent>()
        val connections = LinkedBlockingQueue<Connection>()
        val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
        val client = GatewayClient(http, scope, { events.put(it) }, { connections.put(it) })
        val session = "app-check-" + System.currentTimeMillis()
        client.connect(GatewayConfig(BuildConfig.DEFAULT_BRAIN_URL, BuildConfig.DEFAULT_DEVICE_TOKEN, session))
        try {
            while (connections.poll(30, TimeUnit.SECONDS) != Connection.Online) Unit
            var state = RivetStore.userSent(RivetState(connection = Connection.Online), "t-check", "ping")
            client.send(Protocol.turn("Reply with exactly these words: Rivet is online.", "t-check"))
            val seen = mutableListOf<String>()
            while (true) {
                val event = events.poll(120, TimeUnit.SECONDS) ?: error("no reply from the brain; saw $seen")
                seen += event::class.simpleName.orEmpty()
                state = RivetStore.reduce(state, event, System.currentTimeMillis())
                if (event is ServerEvent.TurnFailed) error("turn failed: ${event.error}")
                if (event is ServerEvent.TurnCompleted && event.lane == "default") break
            }
            println("events: $seen")
            println("reply: ${state.messages.last().text}")
            assertTrue("SessionReady" in seen && "TurnAccepted" in seen && "TurnStarted" in seen)
            assertTrue(state.messages.last().text.contains("Rivet is online", ignoreCase = true))
            assertEquals(Activity.Done, state.activity)
        } finally {
            client.disconnect()
            scope.cancel()
        }
    }
}
