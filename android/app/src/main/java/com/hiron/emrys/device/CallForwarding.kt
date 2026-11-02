package com.hiron.emrys.device

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/** Which calls the carrier forwards to Emrys's answering line. */
enum class ForwardMode { Unanswered, Always }

/** How the carrier takes forwarding codes: GSM supplementary-service codes, or Verizon's feature codes. */
enum class CarrierStyle { Gsm, Verizon }

/**
 * Carrier call forwarding to Emrys's Telnyx answering line.
 *
 * Android has no public API for ordinary apps to set forwarding, so Emrys dials the carrier's own codes, the
 * same thing a user would type in the dialer; the carrier shows its confirmation. Only the user turns this on,
 * from Settings — the brain can never dial these codes.
 */
object CallForwarding {
    private val VERIZON_NETWORKS: Set<String> =
        setOf("310004", "310010", "310012", "310013", "311012", "311110", "311390") +
            (311270..311289).map { it.toString() } + (311480..311489).map { it.toString() }

    fun detect(simOperator: String?, operatorName: String?): CarrierStyle =
        if (simOperator in VERIZON_NETWORKS || operatorName?.contains("verizon", ignoreCase = true) == true) {
            CarrierStyle.Verizon
        } else {
            CarrierStyle.Gsm
        }

    fun detect(context: Context): CarrierStyle {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return detect(telephony?.simOperator, telephony?.simOperatorName)
    }

    /** The code that turns forwarding on, or null when [target] is not a usable phone number. */
    fun enableCode(target: String, mode: ForwardMode, style: CarrierStyle): String? {
        val digits = Phones.digits(target)
        if (digits.length < 8) return null
        return when (style) {
            // **004: no answer, busy and unreachable together; **21: every call.
            CarrierStyle.Gsm -> if (mode == ForwardMode.Unanswered) "**004*+$digits#" else "**21*+$digits#"
            CarrierStyle.Verizon -> {
                val local = if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
                if (mode == ForwardMode.Unanswered) "*71$local" else "*72$local"
            }
        }
    }

    /** The code that turns all forwarding off (##002# clears every GSM forwarding rule). */
    fun disableCode(style: CarrierStyle): String = if (style == CarrierStyle.Gsm) "##002#" else "*73"

    /** Dials [code] through the system dialer; null on success, otherwise why it could not. Needs CALL_PHONE. */
    fun dial(context: Context, code: String): String? {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return "This phone cannot place calls."
        return try {
            telecom.placeCall(Uri.fromParts("tel", code, null), Bundle())
            null
        } catch (e: SecurityException) {
            "Emrys needs the Phone permission to dial your carrier's forwarding code."
        }
    }
}
