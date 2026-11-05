package com.hiron.emrys.mcp

import com.hiron.emrys.device.ToolResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.modelcontextprotocol.kotlin.sdk.types.success
import org.json.JSONObject

/**
 * Emrys's phone tools as one MCP server; every call goes to [run], the same function the client tools use.
 * [version] is the app's version name, reported to the hub in `initialize`.
 */
class PhoneMcpServer(private val version: String, private val run: suspend (String, JSONObject) -> ToolResult) {
    fun build(): Server {
        val server = Server(
            serverInfo = Implementation(name = "emrys-phone", version = version, title = "Emrys phone"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        PhoneTools.all.forEach { tool -> server.addTool(tool) { request -> answer(run(tool.name, input(request))) } }
        return server
    }

    companion object {
        /** The MCP arguments as the JSON object [com.hiron.emrys.device.DeviceTools] reads; both sides are plain JSON. */
        internal fun input(request: CallToolRequest): JSONObject = request.arguments?.let { JSONObject(it.toString()) } ?: JSONObject()

        /** A [ToolResult] as the SDK's result: the data as text on success, the message as an error otherwise. */
        internal fun answer(result: ToolResult): CallToolResult {
            val text = when (val data = result.data) {
                null, JSONObject.NULL -> ""
                else -> data.toString()
            }
            return if (result.ok) CallToolResult.success(text) else CallToolResult.error(text.ifBlank { "The action failed on the phone." })
        }
    }
}
