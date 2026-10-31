package com.hiron.emrys.core

import android.Manifest
import android.os.Build

/** One group in Settings › What Emrys can use: its name and the Android permissions behind it. */
data class PermissionGroup(val name: String, val why: String, val permissions: List<String>)

object PermissionGroups {
    /** The runtime permissions Emrys asks for, in the order Settings lists them. */
    val ALL: List<PermissionGroup> = buildList {
        add(PermissionGroup("Texts", "Send and read SMS", listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)))
        add(PermissionGroup("Contacts", "Find people by name", listOf(Manifest.permission.READ_CONTACTS)))
        add(PermissionGroup("Calendar", "Read and add events", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)))
        add(PermissionGroup("Phone", "Place calls and read recent calls", listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)))
        add(PermissionGroup("Microphone", "Talk to Emrys out loud", listOf(Manifest.permission.RECORD_AUDIO)))
        if (Build.VERSION.SDK_INT >= 33) {
            add(PermissionGroup("Notifications", "Show approvals and reminders", listOf(Manifest.permission.POST_NOTIFICATIONS)))
        }
    }
}
