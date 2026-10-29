package com.hiron.rivet.core

import com.hiron.rivet.testing.FakeAndroidKeyStore
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Undoing recommended settings removes every rule they asked for, including ones the brain hasn't listed yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecommendedUndoTest {
    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<JSONObject>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val runtime = RivetRuntime(RuntimeEnvironment.getApplication(), names = NameLookup { null })
    private val listed = RuleTarget("sms_send", mapOf("to" to "+18015550100"))
    private val unlisted = RuleTarget("screen_act", mapOf("label" to "Send"))

    @After
    fun tearDown() {
        runtime.disconnect()
        sockets.forEach { it.close(1000, null) }
        runCatching { server.close() }
    }

    private fun snapshot() = RecommendedUndo(speakInOverlay = true, ruleTargets = setOf(listed, unlisted))

    private fun next(type: String): JSONObject {
        while (true) {
            val m = received.poll(10, TimeUnit.SECONDS) ?: error("brain never got $type")
            if (m.optString("type") == type) return m
        }
    }

    private fun until(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun offlineItChangesNothingWhileThereAreRulesToRemove() {
        runtime.settings.speakInOverlay = false
        runtime.settings.recommendedUndo = snapshot()
        assertFalse(runtime.undoRecommended())
        assertEquals(snapshot(), runtime.settings.recommendedUndo)
        assertFalse(runtime.settings.speakInOverlay)

        runtime.settings.recommendedUndo = RecommendedUndo(speakInOverlay = true)
        assertTrue(runtime.undoRecommended())
        assertTrue(runtime.settings.speakInOverlay)
        assertNull(runtime.settings.recommendedUndo)
    }

    @Test
    fun onlineItRevokesListedRulesByKeyAndTheRestByTarget() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets.put(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.put(JSONObject(text))
                }
            }),
        )
        server.start()
        runtime.settings.gatewayUrl = server.url("/").toString()
        runtime.settings.sessionId = "undo-test"
        FakeAndroidKeyStore.install()
        runtime.settings.deviceToken = "undo-test-device-token-0123"
        runtime.connect()
        val socket = sockets.poll(10, TimeUnit.SECONDS) ?: error("no connection")
        next("resume")
        socket.send(
            """{"type":"rules","revision":1,"rules":[{"key":"sms_send?to=%2B18015550100","tool":"sms_send",""" +
                """"scope":{"to":"+18015550100"},"decision":"deny","label":"Texts to +18015550100","created_at":1}]}""",
        )
        until { runtime.state.value.connection == Connection.Online && runtime.state.value.rules.match(listed) != null }

        runtime.settings.speakInOverlay = false
        runtime.settings.recommendedUndo = snapshot()
        assertTrue(runtime.undoRecommended())
        assertTrue(runtime.settings.speakInOverlay)
        assertNull(runtime.settings.recommendedUndo)
        val revokes = listOf(next("rules.revoke"), next("rules.revoke"))
        assertEquals(setOf("sms_send?to=%2B18015550100"), revokes.mapNotNull { it.optString("key").ifEmpty { null } }.toSet())
        val byTarget = revokes.single { !it.has("key") }
        assertEquals("screen_act", byTarget.getString("tool"))
        assertEquals("Send", byTarget.getJSONObject("scope").getString("label"))
    }
}
