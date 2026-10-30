package com.hiron.emrys.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** A contact's name for a phone number, or null when there is none. */
fun interface NameLookup {
    fun name(e164: String): String?
}

/**
 * Names for numbers from the phone's contacts (`ContactsContract.PhoneLookup`), so alerts say "Dana" instead of a
 * number. Needs `READ_CONTACTS`; without it every lookup is null. Lookups query a content provider, so call this off
 * the main thread. Hits and misses are cached for the process.
 */
class ContactNames(private val context: Context) : NameLookup {
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_SIZE
    }

    override fun name(e164: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        synchronized(cache) { cache[e164]?.let { return it.ifEmpty { null } } }
        val found = try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(e164))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: Exception) {
            null
        }
        synchronized(cache) { cache[e164] = found.orEmpty() }
        return found
    }

    private companion object {
        const val CACHE_SIZE = 256
    }
}
