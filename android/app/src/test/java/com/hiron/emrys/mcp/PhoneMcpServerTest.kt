package com.hiron.emrys.mcp

import com.hiron.emrys.device.ToolResult
import com.hiron.emrys.voice.AgentTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneMcpServerTest {
    private val host = "127.0.0.1"
    private val token = "test-token"
    private val url = "http://$host:$PHONE_MCP_PORT$PHONE_MCP_PATH"
    private val calls = mutableListOf<Pair<String, String>>()
    private var answer = ToolResult.ok(JSONObject().put("battery", 80))

    private lateinit var app: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private val http = HttpClient(CIO) { install(SSE) }

    @Before
    fun start() {
        val server = PhoneMcpServer("test") { tool, input ->
            calls += tool to input.toString()
            answer
        }.build()
        app = phoneMcpApplication(server, host, token).start(wait = false)
    }

    @After
    fun stop() {
        http.close()
        app.stop(500, 2000)
    }

    private suspend fun connect(): Client {
        val client = Client(Implementation(name = "test", version = "0"))
        client.connect(http.mcpStreamableHttpTransport(url) { header(HttpHeaders.Authorization, "Bearer $token") })
        return client
    }

    private fun initialize(): String =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""

    @Test
    fun listsTheEighteenTools() = runBlocking {
        val client = connect()
        assertEquals(AgentTools.NAMES, client.listTools().tools.map { it.name })
        client.close()
    }

    @Test
    fun callsRunWithTheToolNameAndArguments() = runBlocking {
        val client = connect()
        val result = client.callTool("sms_send", mapOf("to" to "+15555550100", "body" to "hi"))
        assertEquals(listOf("sms_send" to JSONObject().put("to", "+15555550100").put("body", "hi").toString()), calls)
        assertNotEquals(true, result.isError)
        assertEquals(listOf("""{"battery":80}"""), result.content.filterIsInstance<TextContent>().map { it.text })
        client.close()
    }

    @Test
    fun aFailureIsAToolError() = runBlocking {
        answer = ToolResult(false, null)
        val client = connect()
        val result = client.callTool("device_status", emptyMap())
        assertEquals(true, result.isError)
        assertEquals(listOf("The action failed on the phone."), result.content.filterIsInstance<TextContent>().map { it.text })
        client.close()
    }

    @Test
    fun noTokenOrAWrongTokenIs401() = runBlocking {
        val none = http.post(url) { contentType(ContentType.Application.Json); setBody(initialize()) }
        val wrong = http.post(url) {
            header(HttpHeaders.Authorization, "Bearer wrong")
            contentType(ContentType.Application.Json)
            setBody(initialize())
        }
        assertEquals(HttpStatusCode.Unauthorized, none.status)
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun anotherHostIsRejected() = runBlocking {
        val response = http.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Host, "evil.example")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(initialize())
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(calls.isEmpty())
    }
}
