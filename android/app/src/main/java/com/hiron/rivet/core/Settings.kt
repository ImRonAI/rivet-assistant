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
class Settings(context: Context) {
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

    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", BuildConfig.DEFAULT_DEVICE_TOKEN.isNotBlank())
        set(value) = prefs.edit().putBoolean("auto_connect", value).apply()

    /** Start a reply task for each new message notification (every reply still needs approval). */
    var offerReplies: Boolean
        get() = prefs.getBoolean("offer_replies", false)
        set(value) = prefs.edit().putBoolean("offer_replies", value).apply()

    /** Rivet's Telnyx answering line (saved by the brain's call_answering_setup, or typed in Settings). */
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

    /** The number Rivet last asked the carrier to forward to ("" after turning it off); the carrier is the truth. */
    var forwardedTo: String
        get() = prefs.getString("forwarded_to", "").orEmpty()
        set(value) = prefs.edit().putString("forwarded_to", value.trim()).apply()

    /** Whether the one-time permission request has been shown. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    fun gateway(): GatewayConfig = GatewayConfig(gatewayUrl, deviceToken, sessionId)
}

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
