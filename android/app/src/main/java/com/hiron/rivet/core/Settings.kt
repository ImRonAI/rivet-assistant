package com.hiron.rivet.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hiron.rivet.BuildConfig
import com.hiron.rivet.device.CarrierStyle
import com.hiron.rivet.device.ForwardMode
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Connection settings. The device token is encrypted with a key that never leaves the Android Keystore. */
class Settings(context: Context) : RecommendedPrefs {
    private val prefs = context.getSharedPreferences("rivet", Context.MODE_PRIVATE)

    var gatewayUrl: String
        get() = prefs.getString("gateway_url", null) ?: BuildConfig.DEFAULT_BRAIN_URL
        set(value) = prefs.edit().putString("gateway_url", value.trim()).apply()

    var sessionId: String
        get() = prefs.getString("session_id", null)
            ?: BuildConfig.DEFAULT_SESSION.ifBlank { "phone-" + UUID.randomUUID().toString().take(8) }.also { sessionId = it }
        set(value) = prefs.edit().putString("session_id", value.trim()).apply()

    var deviceToken: String
        get() = prefs.getString("device_token", null)?.let { SecretBox.open(it) } ?: BuildConfig.DEFAULT_DEVICE_TOKEN
        set(value) = prefs.edit().putString("device_token", if (value.isBlank()) null else SecretBox.seal(value.trim())).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(value) = prefs.edit().putBoolean("speak_replies", value).apply()

    override var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", BuildConfig.DEFAULT_DEVICE_TOKEN.isNotBlank())
        set(value) = prefs.edit().putBoolean("auto_connect", value).apply()

    /** Read replies aloud while the assistant overlay is on screen too (with [speakReplies]). */
    override var speakInOverlay: Boolean
        get() = prefs.getBoolean("speak_in_overlay", true)
        set(value) = prefs.edit().putBoolean("speak_in_overlay", value).apply()

    /** Rivet's Telnyx answering line (saved by the brain's call_answering_setup, or typed in Settings). */
    var answeringNumber: String
        get() = prefs.getString("answering_number", null) ?: BuildConfig.DEFAULT_ANSWERING_NUMBER
        set(value) = prefs.edit().putString("answering_number", value.trim()).apply()

    /** The user's own phone number (E.164), where "Take over" transfers a call Rivet is answering. */
    var ownNumber: String
        get() = prefs.getString("own_number", "").orEmpty()
        set(value) = prefs.edit().putString("own_number", value.trim()).apply()

    var answeringMode: ForwardMode
        get() = runCatching { ForwardMode.valueOf(prefs.getString("answering_mode", null).orEmpty()) }.getOrDefault(ForwardMode.Unanswered)
        set(value) = prefs.edit().putString("answering_mode", value.name).apply()

    /** Null means detect from the SIM. */
    var carrierStyle: CarrierStyle?
        get() = prefs.getString("carrier_style", null)?.let { runCatching { CarrierStyle.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit().putString("carrier_style", value?.name).apply()

    /** The number Rivet last asked the carrier to forward to ("" after turning it off); the carrier is the truth. */
    var forwardedTo: String
        get() = prefs.getString("forwarded_to", "").orEmpty()
        set(value) = prefs.edit().putString("forwarded_to", value.trim()).apply()

    /** Whether the one-time permission request has been shown. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** Talk to Rivet out loud (the voice model). Off: Home shows the keyboard and send key only. */
    var voiceModel: Boolean
        get() = prefs.getBoolean("voice_model", true)
        set(value) = prefs.edit().putBoolean("voice_model", value).apply()

    /** Talk and type through the ElevenLabs agents (the brain needs `ELEVENLABS_API_KEY`). Off: the brain's own turns. */
    var elevenLabsAgents: Boolean
        get() = prefs.getBoolean("elevenlabs_agents", true)
        set(value) = prefs.edit().putBoolean("elevenlabs_agents", value).apply()

    /** Brave Suggest and Spellcheck while typing in the composer and the search page; each keystroke goes to Brave. */
    var searchSuggestions: Boolean
        get() = prefs.getBoolean("search_suggestions", true)
        set(value) = prefs.edit().putBoolean("search_suggestions", value).apply()

    /** What the last "Use recommended settings" changed, until it is undone. */
    override var recommendedUndo: RecommendedUndo?
        get() {
            if (!prefs.contains(UNDO_AT)) return null
            fun flag(key: String) = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
            return RecommendedUndo(
                autoConnect = flag(UNDO_AUTO_CONNECT),
                speakInOverlay = flag(UNDO_SPEAK_IN_OVERLAY),
                ruleTargets = prefs.getStringSet(UNDO_RULE_TARGETS, null).orEmpty().mapNotNull(RuleTargetCodec::decode).toSet(),
            )
        }
        set(value) {
            val edit = prefs.edit()
            listOf(UNDO_AUTO_CONNECT, UNDO_SPEAK_IN_OVERLAY, UNDO_RULE_TARGETS, UNDO_AT).forEach(edit::remove)
            if (value != null) {
                value.autoConnect?.let { edit.putBoolean(UNDO_AUTO_CONNECT, it) }
                value.speakInOverlay?.let { edit.putBoolean(UNDO_SPEAK_IN_OVERLAY, it) }
                if (value.ruleTargets.isNotEmpty()) edit.putStringSet(UNDO_RULE_TARGETS, value.ruleTargets.map(RuleTargetCodec::encode).toSet())
                edit.putBoolean(UNDO_AT, true)
            }
            edit.apply()
        }

    fun gateway(): GatewayConfig = GatewayConfig(gatewayUrl, deviceToken, sessionId)

    private companion object {
        const val UNDO_AUTO_CONNECT = "undo_auto_connect"
        const val UNDO_SPEAK_IN_OVERLAY = "undo_speak_in_overlay"
        const val UNDO_RULE_TARGETS = "undo_rule_targets"

        /** Marks that there is something to undo. */
        const val UNDO_AT = "undo_at"
    }
}

private val E164 = Regex("""\+[1-9]\d{6,14}""")

/** Whether [number] is in E.164 form (`+` and 7 to 15 digits), as the brain requires for a take-over. */
fun isE164(number: String): Boolean = E164.matches(number)

/** AES-GCM with an Android Keystore key: `base64(iv || ciphertext)`. */
object SecretBox {
    private const val ALIAS = "rivet-secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = try {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
