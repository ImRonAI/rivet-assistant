package com.hiron.emrys.device

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** SMS, contacts, the call log and placing calls. */
class Messaging(private val context: Context) {

    suspend fun send(to: String, body: String): ToolResult {
        val number = to.trim()
        if (Phones.digits(number).length < 3) return ToolResult.error("\"$to\" is not a phone number. Look the contact up first.")
        val sms = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return ToolResult.error("This phone cannot send SMS.")
        val parts = sms.divideMessage(body)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val results = Channel<Int>(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList(parts.indices.map { i ->
                PendingIntent.getBroadcast(
                    context, i, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            })
            sms.sendMultipartTextMessage(number, null, parts, sent, null)
            val codes = withTimeoutOrNull(SEND_TIMEOUT_MS) { List(parts.size) { results.receive() } }
                ?: return ToolResult.error("The phone handed the text to the carrier but got no confirmation in 60s; it may not have sent.")
            val failed = codes.count { it != Activity.RESULT_OK }
            if (failed > 0) return ToolResult.error("The carrier rejected the text (${failedReason(codes.first { it != Activity.RESULT_OK })}). It was not sent.")
            return ToolResult.ok(JSONObject().put("sent", true).put("to", number).put("parts", parts.size))
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    suspend fun list(contact: String?, limit: Int): ToolResult = withContext(Dispatchers.IO) {
        val want = contact?.let { Phones.digits(it).takeLast(7) }?.takeIf { it.length >= 4 }
        val nameFilter = contact?.takeIf { want == null }?.lowercase()
        val rows = JSONArray()
        val names = HashMap<String, String?>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.length() < limit.coerceIn(1, 100)) {
                val address = c.getString(0).orEmpty()
                if (want != null && !Phones.digits(address).endsWith(want)) continue
                val name = names.getOrPut(address) { lookupName(address) }
                if (nameFilter != null && name?.lowercase()?.contains(nameFilter) != true) continue
                rows.put(
                    JSONObject()
                        .put("from", if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "me" else (name ?: address))
                        .put("number", address)
                        .put("contact", name ?: JSONObject.NULL)
                        .put("text", c.getString(1).orEmpty())
                        .put("at", Times.format(c.getLong(2)))
                        .put("unread", c.getInt(4) == 0),
                )
            }
        }
        ToolResult.ok(JSONObject().put("messages", rows).put("count", rows.length()))
    }

    suspend fun contacts(query: String, limit: Int): ToolResult = withContext(Dispatchers.IO) {
        val byId = LinkedHashMap<Long, JSONObject>()
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query)),
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext() && byId.size <= limit) {
                val entry = byId.getOrPut(c.getLong(0)) {
                    JSONObject().put("name", c.getString(1)).put("phones", JSONArray()).put("emails", JSONArray())
                }
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), "").toString()
                entry.getJSONArray("phones").put(JSONObject().put("number", c.getString(2)).put("type", label))
            }
        }
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, Uri.encode(query)),
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val entry = byId[c.getLong(0)]
                    ?: if (byId.size < limit) {
                        JSONObject().put("name", c.getString(1)).put("phones", JSONArray()).put("emails", JSONArray())
                            .also { byId[c.getLong(0)] = it }
                    } else {
                        continue
                    }
                entry.getJSONArray("emails").put(c.getString(2))
            }
        }
        ToolResult.ok(JSONObject().put("contacts", JSONArray(byId.values.take(limit))))
    }

    suspend fun recentCalls(limit: Int): ToolResult = withContext(Dispatchers.IO) {
        val rows = JSONArray()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.length() < limit.coerceIn(1, 100)) {
                rows.put(
                    JSONObject()
                        .put("number", c.getString(0).orEmpty())
                        .put("name", c.getString(1) ?: JSONObject.NULL)
                        .put("kind", callKind(c.getInt(2)))
                        .put("at", Times.format(c.getLong(3)))
                        .put("seconds", c.getLong(4)),
                )
            }
        }
        ToolResult.ok(JSONObject().put("calls", rows))
    }

    fun call(number: String): ToolResult {
        if (Phones.digits(number).length < 3) return ToolResult.error("\"$number\" is not a phone number.")
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return ToolResult.error("This phone cannot place calls.")
        return try {
            telecom.placeCall(Uri.fromParts("tel", number.trim(), null), Bundle())
            ToolResult.ok(JSONObject().put("calling", number.trim()))
        } catch (e: SecurityException) {
            ToolResult.error("Emrys isn't allowed to place calls. Ask the user to allow Phone in Emrys › Settings › Permissions.")
        }
    }

    private fun lookupName(number: String): String? = try {
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        // Without READ_CONTACTS the thread still lists, just by number.
        null
    }

    private fun callKind(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other"
    }

    private fun failedReason(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "bad message"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        else -> "error $code"
    }

    companion object {
        private const val SEND_TIMEOUT_MS = 60_000L
    }
}

object Phones {
    fun digits(value: String): String = value.filter { it.isDigit() }
}
