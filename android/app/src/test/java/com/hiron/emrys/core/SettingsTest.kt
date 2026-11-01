package com.hiron.emrys.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theUserIdIsMadeOnceAndKept() {
        val first = Settings(app).userId
        assertTrue(first.isNotBlank())
        assertEquals(first, Settings(app).userId)
    }

    @Test
    fun everyPermissionAsksFirstOnAFreshInstall() {
        val variables = Settings(app).permissionVariables()
        assertEquals(PermAction.entries.map { "perm_${it.wire}" }.toSet(), variables.keys)
        assertTrue(variables.values.all { it == Settings.PROMPT })
    }

    @Test
    fun anAlwaysAllowIsKeptForEverySession() {
        Settings(app).grant(PermAction.SendMessages, true)
        val variables = Settings(app).permissionVariables()
        assertEquals(Settings.GRANTED, variables["perm_send_messages"])
        assertEquals(Settings.PROMPT, variables["perm_purchases"])
    }
}
