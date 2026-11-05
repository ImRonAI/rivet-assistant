package com.hiron.emrys.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

/** What the hub must send: `Authorization: Bearer <token>`. */
internal class HubTokenConfig {
    var token: String = ""
}

/** Answers 401 to any request without the phone's token, before the MCP route sees it. */
internal val HubToken = createApplicationPlugin(name = "HubToken", createConfiguration = ::HubTokenConfig) {
    val expected = "Bearer " + pluginConfig.token
    onCall { call ->
        if (call.request.header(HttpHeaders.Authorization) != expected) call.respond(HttpStatusCode.Unauthorized)
    }
}

const val PHONE_MCP_PORT = 8765
const val PHONE_MCP_PATH = "/mcp"

/** The MCP server on [host] (the phone's Tailscale address) only, at http://host:8765/mcp. */
internal fun phoneMcpApplication(server: Server, host: String, token: String): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, host = host, port = PHONE_MCP_PORT) {
        install(HubToken) { this.token = token }
        mcpStreamableHttp(path = PHONE_MCP_PATH, allowedHosts = listOf(host)) { server }
    }
