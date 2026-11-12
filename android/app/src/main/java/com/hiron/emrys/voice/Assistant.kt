package com.hiron.emrys.voice

enum class AgentMode { Voice, Text }

/**
 * The conversation the app holds with Emrys's agents, whichever provider runs them: [start] a voice or text
 * session, [sendText], [switchMode], [stop]. The screens use only these members.
 */
interface Assistant {
    /** The mode of the session that is starting or running; null when there is none. */
    val mode: AgentMode?

    fun start(mode: AgentMode, firstMessage: String? = null)

    /** Sends [text] to the text agent, starting a text session when none is running; returns the message's id. */
    fun sendText(text: String): String

    fun switchMode(mode: AgentMode)

    fun stop()

    fun hasMicrophone(): Boolean
}

/**
 * Who runs Emrys's agents; the screens, tools and approvals are the same either way. Every provider but ElevenLabs is a
 * text model under the Strands agents on the workspace VM ([StrandsAgent]), named there by [strands].
 */
enum class Provider(val label: String, val strands: String?) {
    ElevenLabs("ElevenLabs", null),
    Gemini("Gemini", "gemini"),
    Nim("NVIDIA NIM", "nim"),
    Mistral("Mistral", "mistral"),
}
