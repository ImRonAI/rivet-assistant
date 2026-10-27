package com.hiron.rivet.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hiron.rivet.MainActivity
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.rivet
import com.hiron.rivet.ui.COMPOSER_TAG
import com.hiron.rivet.ui.STAGE_TAG
import com.hiron.rivet.ui.TAB_TAG_PREFIX
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drives the real app (MainActivity, runtime, gateway client, screens) against a stand-in brain over a real
 * WebSocket, and saves a screenshot of every screen to build/screens for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<JSONObject>()
    private val sockets = LinkedBlockingQueue<WebSocket>()

    @After
    fun tearDown() {
        compose.activity.rivet.disconnect()
        sockets.forEach { it.close(1000, null) }
        brainSockets.forEach { it.close(1000, null) }
        Thread.sleep(200)
        runCatching { server.close() }
    }

    private val brainSockets = mutableListOf<WebSocket>()

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("rivet.screenshots") ?: "build/screens").apply { mkdirs() }
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            // Every window in z-order: the activity, then sheets and dialogs on top of it.
            val canvas = android.graphics.Canvas(bitmap)
            for (view in windowRoots()) {
                val at = IntArray(2).also { view.getLocationOnScreen(it) }
                canvas.save()
                canvas.translate(at[0].toFloat(), at[1].toFloat())
                view.draw(canvas)
                canvas.restore()
            }
        }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The robot WebView draws nothing under Robolectric; record where its stage is so a review can place it.
        compose.onAllNodesWithTag(STAGE_TAG).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.let { b ->
            File(dir, "$name.stage.json").writeText("""{"left":${b.left},"top":${b.top},"right":${b.right},"bottom":${b.bottom}}""")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<android.view.View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>
        return views.filter { it.isShown && it.width > 0 }
    }

    private fun next(type: String): JSONObject {
        while (true) {
            val m = received.poll(10, TimeUnit.SECONDS) ?: error("brain never got $type")
            if (m.optString("type") == type) return m
        }
    }

    private fun until(timeoutMs: Long = 10_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
            compose.waitForIdle()
        }
    }

    private fun brain(socket: WebSocket, vararg events: String) = events.forEach { socket.send(it) }

    @Test
    fun theWholeAppWorksAgainstABrain() {
        shot("01-home-start")

        // Point the runtime at the stand-in brain and connect, as the baked-in config does with the real one.
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    brainSockets += webSocket
                    sockets.put(webSocket)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.put(JSONObject(text))
                }
            }),
        )
        server.start()
        val runtime = compose.activity.rivet
        runtime.settings.gatewayUrl = server.url("/").toString()
        runtime.settings.sessionId = "flow-test"
        runtime.connect()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        assertEquals("resume", next("resume").getString("type"))
        until { runtime.state.value.connection == Connection.Online }
        shot("02-home-online")

        // Chat: type, send, stream a reply.
        compose.onNodeWithTag(COMPOSER_TAG).performTextInput("What's on my calendar tomorrow?")
        compose.onNodeWithContentDescription("Send").performClick()
        val turn = next("turn")
        assertEquals("What's on my calendar tomorrow?", turn.getString("text"))
        val id = turn.getString("turn_id")
        brain(
            socket,
            """{"type":"turn.accepted","turn_id":"$id"}""",
            """{"type":"turn.started","turn_id":"$id","lane":"default"}""",
            """{"type":"tool","id":"c1","name":"calendar_list","lane":"default"}""",
        )
        // The brain asks the phone for its calendar; the app runs the tool and answers.
        brain(socket, """{"type":"device.call","action_id":"c1","tool":"device_status","input":{}}""")
        val result = next("device.result")
        assertEquals("c1", result.getString("action_id"))
        assertTrue(result.getJSONObject("result").getBoolean("ok"))
        brain(
            socket,
            """{"type":"text","delta":"Tomorrow you have ","lane":"default"}""",
            """{"type":"text","delta":"a dentist cleaning at 9 AM.","lane":"default"}""",
            """{"type":"turn.completed","turn_id":"$id","lane":"default","text":"Tomorrow you have a dentist cleaning at 9 AM.","restarts":0}""",
        )
        until { compose.onAllNodesWithTextCount("dentist cleaning") > 0 }
        compose.onNodeWithText("Tomorrow you have a dentist cleaning at 9 AM.").assertIsDisplayed()
        shot("03-home-conversation")

        // An approval pops the review sheet; approving answers the brain.
        brain(
            socket,
            """{"type":"approval.request","turn_id":"$id","lane":"default","expires_at":9999999999,
               "approvals":[{"id":"rivet-approval:a1","name":"rivet-approval","request":{"v":1,"tool":"sms_send",
               "tier":"send","high":false,"summary":"Text +18015550123","preview":"{\"to\": \"+18015550123\", \"body\": \"Friday at 9 works!\"}",
               "remember":true}}]}""",
        )
        until { compose.onAllNodesWithTextCount("Text +18015550123") > 0 }
        shot("04-approval-sheet")
        compose.onNodeWithText("Approve").performClick()
        val answer = next("approval.response")
        assertTrue(answer.getJSONObject("answers").getJSONObject("rivet-approval:a1").getBoolean("approved"))

        // A call message arrives.
        brain(
            socket,
            """{"type":"call.message","caller_name":"Dana from Wasatch Dental","callback_number":"+18015550123",
               "message":"Confirming Friday at 9.","urgent":false,"hung_up_early":false,"at":1793080062.0}""",
        )
        compose.onNodeWithTag(TAB_TAG_PREFIX + "activity").performClick()
        until { compose.onAllNodesWithTextCount("Dana from Wasatch Dental") > 0 }
        shot("05-activity")

        compose.onNodeWithTag(TAB_TAG_PREFIX + "settings").performClick()
        compose.waitForIdle()
        shot("06-settings-top")
        compose.onNode(hasText("Turn on")).performScrollTo()
        shot("07-settings-call-answering")
        compose.onNode(hasText("Open Rivet's app info")).performScrollTo().assertIsDisplayed()
        shot("08-settings-bottom")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
}
