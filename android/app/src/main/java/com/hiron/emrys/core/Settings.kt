package com.hiron.emrys.core

import android.content.Context
import android.util.Base64
import com.hiron.emrys.BuildConfig
import com.hiron.emrys.device.CarrierStyle
import com.hiron.emrys.device.ForwardMode
import com.hiron.emrys.voice.GeminiTextModel
import com.hiron.emrys.voice.Provider
import java.security.SecureRandom
import java.util.UUID

/** This phone's settings, and the permissions the user granted the agents ("Always allow"). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("emrys", Context.MODE_PRIVATE)

    /** Who this phone's user is to the agents (`user_id`): made once, then kept. */
    val userId: String
        get() = prefs.getString("user_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("user_id", it).apply() }

    /** Emrys's Telnyx answering line, where call forwarding points. */
    var answeringNumber: String
        get() = prefs.getString("answering_number", null) ?: BuildConfig.DEFAULT_ANSWERING_NUMBER
        set(value) = prefs.edit().putString("answering_number", value.trim()).apply()

    var answeringMode: ForwardMode
        get() = runCatching { ForwardMode.valueOf(prefs.getString("answering_mode", null).orEmpty()) }.getOrDefault(ForwardMode.Unanswered)
        set(value) = prefs.edit().putString("answering_mode", value.name).apply()

    /** Null means detect from the SIM. */
    var carrierStyle: CarrierStyle?
        get() = prefs.getString("carrier_style", null)?.let { runCatching { CarrierStyle.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit().putString("carrier_style", value?.name).apply()

    /** The number Emrys last asked the carrier to forward to ("" after turning it off); the carrier is the truth. */
    var forwardedTo: String
        get() = prefs.getString("forwarded_to", "").orEmpty()
        set(value) = prefs.edit().putString("forwarded_to", value.trim()).apply()

    /** The Brave Search plan's key, for the agents' search tools (web, news, images, videos, places, page text). */
    var braveSearchKey: String
        get() = prefs.getString("brave_search_key", null) ?: BuildConfig.DEFAULT_BRAVE_SEARCH_KEY
        set(value) = prefs.edit().putString("brave_search_key", value.trim()).apply()

    /** The Brave Answers plan's key, for the `answers` tool. */
    var braveAnswersKey: String
        get() = prefs.getString("brave_answers_key", null) ?: BuildConfig.DEFAULT_BRAVE_ANSWERS_KEY
        set(value) = prefs.edit().putString("brave_answers_key", value.trim()).apply()

    /** Whether the one-time permission request has been shown. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** Talk to Emrys out loud (the voice agent). Off: Home shows the keyboard and send key only. */
    var voiceModel: Boolean
        get() = prefs.getBoolean("voice_model", true)
        set(value) = prefs.edit().putBoolean("voice_model", value).apply()

    /** Who runs Emrys's agents: the ElevenLabs agents (the default) or Gemini on the workspace VM. */
    var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", null).orEmpty()) }.getOrDefault(Provider.ElevenLabs)
        set(value) = prefs.edit().putString("provider", value.name).apply()

    /** The Gemini text model, which also runs the voice agent's helpers. */
    var geminiTextModel: GeminiTextModel
        get() = runCatching { GeminiTextModel.valueOf(prefs.getString("gemini_text_model", null).orEmpty()) }.getOrDefault(GeminiTextModel.Argon)
        set(value) = prefs.edit().putString("gemini_text_model", value.name).apply()

    /** The bearer token for the Gemini agents on the workspace VM. */
    var geminiToken: String
        get() = prefs.getString("gemini_token", null) ?: BuildConfig.DEFAULT_GEMINI_TOKEN
        set(value) = prefs.edit().putString("gemini_token", value.trim()).apply()

    /** The agents reach this phone's MCP server with this token; made once per install. */
    val phoneMcpToken: String
        get() = prefs.getString("phone_mcp_token", null) ?: newToken().also { prefs.edit().putString("phone_mcp_token", it).apply() }

    /** Whether the phone's MCP server runs (Settings › Remote tools). */
    var phoneMcpEnabled: Boolean
        get() = prefs.getBoolean("phone_mcp_enabled", false)
        set(value) = prefs.edit().putBoolean("phone_mcp_enabled", value).apply()

    private fun newToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    fun granted(action: PermAction): Boolean = prefs.getString(action.variable, PROMPT) == GRANTED

    fun grant(action: PermAction, granted: Boolean) = prefs.edit().putString(action.variable, if (granted) GRANTED else PROMPT).apply()

    /** Every `perm_*` dynamic variable, "granted" or "prompt". */
    fun permissionVariables(): Map<String, String> = PermAction.entries.associate { it.variable to if (granted(it)) GRANTED else PROMPT }

    companion object {
        const val GRANTED = "granted"
        const val PROMPT = "prompt"
    }
}
