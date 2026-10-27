package com.hiron.rivet.ui

import org.json.JSONArray
import org.json.JSONObject

/** Turns a tool's JSON input into labelled lines for the approval sheet ("To", "Message", ...). */
object ApprovalPreview {
    private val LABELS = mapOf(
        "to" to "To", "number" to "Number", "body" to "Message", "text" to "Message", "subject" to "Subject",
        "code" to "Telnyx code", "label" to "Button", "app" to "App", "title" to "Title", "start" to "Starts",
        "end" to "Ends", "owner_name" to "Your name", "reassign" to "Move the number",
    )

    /** Null when the preview is not a JSON object (it is then shown as-is). */
    fun fields(preview: String): List<Pair<String, String>>? {
        val obj = try {
            JSONObject(preview.trim())
        } catch (e: Exception) {
            return null
        }
        return obj.keys().asSequence().toList().map { key -> label(key) to value(obj.opt(key)) }
    }

    private fun label(key: String) =
        LABELS[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun value(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "—"
        is Boolean -> if (v) "Yes" else "No"
        is JSONObject -> v.toString(2)
        is JSONArray -> v.toString(2)
        else -> v.toString()
    }
}
