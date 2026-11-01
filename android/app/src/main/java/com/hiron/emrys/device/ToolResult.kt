package com.hiron.emrys.device

/** A phone tool's answer: [data] is what the agent gets back, or why it failed. */
data class ToolResult(val ok: Boolean, val data: Any?) {
    companion object {
        fun ok(data: Any?) = ToolResult(true, data)
        fun error(message: String) = ToolResult(false, message)
    }
}
