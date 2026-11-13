package com.hiron.ren.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.BrowserStore
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.device.ToolResult
import com.hiron.ren.voice.AgentTools
import com.hiron.ren.voice.SearchTools
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.WebSocketMcpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's link to the hub over a real WebSocket. The far end stands where Ren's hub stands: it checks the bearer
 * token and is an MCP client on the socket the phone dialed, with the SDK's own client and transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubLinkTest {
    private val token = "test-token"
    private val calls = mutableListOf<Pair<String, String>>()
    private var answer = ToolResult.ok(JSONObject().put("battery", 80))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hubClient = CompletableDeferred<Client>()

    private lateinit var hub: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private lateinit var link: HubLink

    @Before
    fun start() {
        hub = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/attach/phone", protocol = "mcp") {
                    if (call.request.header(HttpHeaders.Authorization) != "Bearer $token") {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no token"))
                        return@webSocket
                    }
                    val client = Client(Implementation(name = "hub", version = "0"))
                    client.connect(WebSocketMcpServerTransport(this))
                    hubClient.complete(client)
                    closeReason.await()
                }
            }
        }.start(wait = false)
    }

    private fun attach(withToken: String = token) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var saved: String? = null
        val server = PhoneMcpServer(
            "test",
            Browser(context, BrowserStore({ saved }, { saved = it })) { withToken },
            level = { SearchLevel.Default },
            approve = { input ->
                calls += "request_approval" to input.toString()
                Result.success("""{"approved":true,"remember_decision":false}""")
            },
            search = { name, input ->
                calls += name to input.toString()
                if (input.has("q")) Result.success("""{"shown_on_screen":true}""") else Result.failure(IllegalArgumentException("Say what to search for."))
            },
        ) { tool, input ->
            calls += tool to input.toString()
            answer
        }
        val port = runBlocking { hub.engine.resolvedConnectors().first().port }
        link = HubLink(scope, { "ws://127.0.0.1:$port" }, { withToken }, "test") { server.build() }
        link.start()
    }

    private fun client(): Client = runBlocking { withTimeout(10_000) { hubClient.await() } }

    @After
    fun stop() {
        link.stop()
        scope.cancel()
        hub.stop(200, 1000)
    }

    @Test
    fun thePhoneAttachesAndServesItsToolsThenApprovalThenSearchesThenTheBrowser() = runBlocking {
        attach()
        val names = client().listTools().tools.map { it.name }
        val browser = names.filter { it.startsWith("browser_") }
        assertEquals(AgentTools.NAMES + AgentTools.REQUEST_APPROVAL + SearchTools.NAMES.keys + browser, names)
        assertEquals(
            listOf(
                "browser_tabs", "browser_open", "browser_navigate", "browser_back", "browser_forward", "browser_reload", "browser_select",
                "browser_close", "browser_read", "browser_click", "browser_type", "browser_scroll", "browser_screenshot",
            ),
            browser,
        )
        assertEquals(HubState.Attached, link.state.value)
    }

    @Test
    fun requestApprovalAnswersWhatTheUserChose() = runBlocking {
        attach()
        val result = client().callTool("request_approval", mapOf("action_type" to "send_messages", "details" to "Text Dana"))
        assertEquals(listOf("request_approval" to JSONObject().put("action_type", "send_messages").put("details", "Text Dana").toString()), calls)
        assertNotEquals(true, result.isError)
        assertEquals(listOf("""{"approved":true,"remember_decision":false}"""), result.content.filterIsInstance<TextContent>().map { it.text })
    }

    @Test
    fun aSearchGoesToSearchAndAFailureIsAToolError() = runBlocking {
        attach()
        val found = client().callTool("news_search", mapOf("q" to "rain"))
        val empty = client().callTool("web_search", emptyMap())
        assertEquals(listOf("""{"shown_on_screen":true}"""), found.content.filterIsInstance<TextContent>().map { it.text })
        assertEquals(true, empty.isError)
        assertEquals(listOf("Say what to search for."), empty.content.filterIsInstance<TextContent>().map { it.text })
    }

    @Test
    fun callsRunWithTheToolNameAndArgumentsAndAFailureIsAToolError() = runBlocking {
        attach()
        val result = client().callTool("sms_send", mapOf("to" to "+15555550100", "body" to "hi"))
        assertEquals(listOf("sms_send" to JSONObject().put("to", "+15555550100").put("body", "hi").toString()), calls)
        assertNotEquals(true, result.isError)
        assertEquals(listOf("""{"battery":80}"""), result.content.filterIsInstance<TextContent>().map { it.text })

        answer = ToolResult(false, null)
        val failed = client().callTool("device_status", emptyMap())
        assertEquals(true, failed.isError)
        assertEquals(listOf("The action failed on the phone."), failed.content.filterIsInstance<TextContent>().map { it.text })
    }

    @Test
    fun aWrongTokenNeverAttachesAndTheLinkKeepsTrying() = runBlocking {
        attach(withToken = "wrong")
        val state = withTimeout(30_000) { link.state.first { it is HubState.Retrying } }
        assertTrue(state is HubState.Retrying)
        assertTrue(!hubClient.isCompleted)
        assertTrue(calls.isEmpty())
    }
}
