package com.hiron.ren.mcp

import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.device.ToolResult
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
 * Ren's phone as one MCP server: the phone tools, `request_approval`, the search tools and the browser's tools. The
 * device tools go to [run], the same function the client tools use; `request_approval` goes to [approve] and the search
 * tools to [search], which answer what the agent is told or why the call failed; the `browser_*` tools drive [browser].
 * [version] is the app's version name, reported in `initialize`.
 */
class PhoneMcpServer(
    private val version: String,
    private val browser: Browser,
    private val level: () -> SearchLevel,
    private val approve: suspend (JSONObject) -> Result<String>,
    private val search: suspend (String, JSONObject) -> Result<String>,
    private val run: suspend (String, JSONObject) -> ToolResult,
) {
    fun build(): Server {
        val server = Server(
            serverInfo = Implementation(name = "ren-phone", version = version, title = "Ren phone"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        PhoneTools.all.forEach { tool -> server.addTool(tool) { request -> answer(run(tool.name, input(request))) } }
        server.addTool(PhoneTools.approval) { request -> answer(approve(input(request))) }
        PhoneTools.searches.forEach { tool -> server.addTool(tool) { request -> answer(search(tool.name, input(request))) } }
        val pages = BrowserTools(browser, level)
        pages.tools.forEach { tool -> server.addTool(tool) { request -> pages.call(tool.name, input(request)) } }
        return server
    }

    companion object {
        /** The MCP arguments as the JSON object [com.hiron.ren.device.DeviceTools] reads; both sides are plain JSON. */
        internal fun input(request: CallToolRequest): JSONObject = request.arguments?.let { JSONObject(it.toString()) } ?: JSONObject()

        /** A [ToolResult] as the SDK's result: the data as text on success, the message as an error otherwise. */
        internal fun answer(result: ToolResult): CallToolResult {
            val text = when (val data = result.data) {
                null, JSONObject.NULL -> ""
                else -> data.toString()
            }
            return if (result.ok) CallToolResult.success(text) else CallToolResult.error(text.ifBlank { "The action failed on the phone." })
        }

        /** What the agent is told as a success, or the failure's message as an error. */
        internal fun answer(result: Result<String>): CallToolResult =
            result.fold({ CallToolResult.success(it) }, { CallToolResult.error(it.message ?: "The action failed on the phone.") })

        /** The arguments as the map the client tools' handlers read, the way the ElevenLabs SDK hands them over. */
        fun parameters(input: JSONObject): Map<String, Any> = input.keys().asSequence().associateWith { input.get(it) }
    }
}
