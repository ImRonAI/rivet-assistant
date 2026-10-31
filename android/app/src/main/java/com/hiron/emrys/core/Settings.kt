package com.hiron.emrys.core

import android.content.Context
import com.hiron.emrys.BuildConfig
import com.hiron.emrys.device.CarrierStyle
import com.hiron.emrys.device.ForwardMode
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

    /** Whether the one-time permission request has been shown. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** Talk to Emrys out loud (the voice agent). Off: Home shows the keyboard and send key only. */
    var voiceModel: Boolean
        get() = prefs.getBoolean("voice_model", true)
        set(value) = prefs.edit().putBoolean("voice_model", value).apply()

    fun granted(action: PermAction): Boolean = prefs.getString(action.variable, PROMPT) == GRANTED

    fun grant(action: PermAction, granted: Boolean) = prefs.edit().putString(action.variable, if (granted) GRANTED else PROMPT).apply()

    /** Every `perm_*` dynamic variable, "granted" or "prompt". */
    fun permissionVariables(): Map<String, String> = PermAction.entries.associate { it.variable to if (granted(it)) GRANTED else PROMPT }

    companion object {
        const val GRANTED = "granted"
        const val PROMPT = "prompt"
    }
}
