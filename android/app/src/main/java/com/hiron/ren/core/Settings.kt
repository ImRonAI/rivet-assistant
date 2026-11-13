package com.hiron.ren.core

import android.content.Context
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.BuildConfig
import com.hiron.ren.device.CarrierStyle
import com.hiron.ren.device.ForwardMode
import com.hiron.ren.voice.Provider
import java.util.UUID

/** This phone's settings, and the permissions the user granted the agents ("Always allow"). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("ren", Context.MODE_PRIVATE)

    /** Who this phone's user is to the agents (`user_id`): made once, then kept. */
    val userId: String
        get() = prefs.getString("user_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("user_id", it).apply() }

    /** Ren's Telnyx answering line, where call forwarding points. */
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

    /** The number Ren last asked the carrier to forward to ("" after turning it off); the carrier is the truth. */
    var forwardedTo: String
        get() = prefs.getString("forwarded_to", "").orEmpty()
        set(value) = prefs.edit().putString("forwarded_to", value.trim()).apply()

    /** The Brave Search plan's key, for the agents' search tools (web, news, images, videos, places, page text). */
    /** The open tabs, as [com.hiron.ren.browser.BrowserStore] writes them. */
    var browserTabs: String?
        get() = prefs.getString("browser_tabs", null)
        set(value) = prefs.edit().putString("browser_tabs", value).apply()

    /** How hard a search from the address bar works. */
    var searchLevel: SearchLevel
        get() = SearchLevel.of(prefs.getString("search_level", null)) ?: SearchLevel.Default
        set(value) = prefs.edit().putString("search_level", value.wire).apply()

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

    /** Talk to Ren out loud (the voice agent). Off: Home shows the keyboard and send key only. */
    var voiceModel: Boolean
        get() = prefs.getBoolean("voice_model", true)
        set(value) = prefs.edit().putBoolean("voice_model", value).apply()

    /** Who runs Ren's agents: the ElevenLabs agents (the default), or Gemini, NVIDIA NIM or Mistral on the workspace VM. */
    var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", null).orEmpty()) }.getOrDefault(Provider.ElevenLabs)
        set(value) = prefs.edit().putString("provider", value.name).apply()

    /** The [provider]'s text model, which also runs the voice agent's helpers; null is the provider's default. */
    fun textModel(provider: Provider): String? = prefs.getString("text_model_${provider.name}", null)

    fun setTextModel(provider: Provider, model: String?) {
        prefs.edit().putString("text_model_${provider.name}", model).putString("effort_${provider.name}", AUTO).apply()
    }

    /** The reasoning level for the [provider]'s text model, one the model lists, or [AUTO] for the provider's own. */
    fun effort(provider: Provider): String = prefs.getString("effort_${provider.name}", null) ?: AUTO

    fun setEffort(provider: Provider, effort: String) = prefs.edit().putString("effort_${provider.name}", effort).apply()

    /** The bearer token for the workspace VM: the Strands agents, Ren's hub and Ren's search. */
    var geminiToken: String
        get() = prefs.getString("gemini_token", null) ?: BuildConfig.DEFAULT_GEMINI_TOKEN
        set(value) = prefs.edit().putString("gemini_token", value.trim()).apply()

    /** Whether the phone stays attached to Ren's hub (Settings › Remote tools). */
    var hubEnabled: Boolean
        get() = prefs.getBoolean("hub_enabled", false)
        set(value) = prefs.edit().putBoolean("hub_enabled", value).apply()

    /** Ren's hub on the workspace VM, as a WebSocket base. */
    var hubUrl: String
        get() = prefs.getString("hub_url", null) ?: DEFAULT_HUB_URL
        set(value) = prefs.edit().putString("hub_url", value.trim()).apply()

    fun granted(action: PermAction): Boolean = prefs.getString(action.variable, PROMPT) == GRANTED

    fun grant(action: PermAction, granted: Boolean) = prefs.edit().putString(action.variable, if (granted) GRANTED else PROMPT).apply()

    /** Every `perm_*` dynamic variable, "granted" or "prompt". */
    fun permissionVariables(): Map<String, String> = PermAction.entries.associate { it.variable to if (granted(it)) GRANTED else PROMPT }

    companion object {
        const val GRANTED = "granted"
        const val PROMPT = "prompt"
        const val AUTO = "auto"

        /** Ren's hub on the owner's workspace VM. */
        const val DEFAULT_HUB_URL = "wss://rivet.ron-ai.dev/hub"
    }
}
