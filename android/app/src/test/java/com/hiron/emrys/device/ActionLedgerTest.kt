package com.hiron.emrys.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ActionLedgerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "actions.json")

    @Test
    fun aResentCallGetsTheStoredResultInsteadOfRunningAgain() = runTest {
        val ledger = ActionLedger(file())
        var runs = 0
        val first = ledger.run("tu-1", "sms_send") { runs++; ToolResult.ok("sent").toJson() }
        val again = ledger.run("tu-1", "sms_send") { runs++; ToolResult.ok("sent twice").toJson() }
        assertEquals(1, runs)
        assertEquals(first.toString(), again.toString())
    }

    @Test
    fun theLedgerSurvivesARestart() = runTest {
        ActionLedger(file()).run("tu-1", "sms_send") { ToolResult.ok("sent").toJson() }
        var ran = false
        val result = ActionLedger(file()).run("tu-1", "sms_send") { ran = true; JSONObject() }
        assertFalse(ran)
        assertEquals("sent", result!!.getString("data"))
    }

    @Test
    fun aCallInterruptedByAppDeathIsNeverRetriedBlindly() = runTest {
        ActionLedger(file()).start("tu-1", "phone_call")
        var ran = false
        val result = ActionLedger(file()).run("tu-1", "phone_call") { ran = true; JSONObject() }!!
        assertFalse(ran)
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("data").contains("may or may not"))
    }

    @Test
    fun aDuplicateWhileRunningIsDropped() = runTest {
        val ledger = ActionLedger(file())
        val gate = CompletableDeferred<Unit>()
        val first = async { ledger.run("tu-1", "sms_send") { gate.await(); ToolResult.ok(1).toJson() } }
        yield()
        assertNull(ledger.run("tu-1", "sms_send") { ToolResult.ok(2).toJson() })
        gate.complete(Unit)
        assertEquals(1, first.await()!!.getInt("data"))
    }

    @Test
    fun exceptionsBecomeToolErrors() = runTest {
        val result = ActionLedger(file()).run("tu-1", "x") { throw IllegalStateException("boom") }!!
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("data").contains("boom"))
    }

    @Test
    fun aCorruptLedgerIsSetAsideNotFatal() = runTest {
        file().writeText("{not json")
        val result = ActionLedger(file()).run("tu-1", "x") { ToolResult.ok("ran").toJson() }!!
        assertEquals("ran", result.getString("data"))
        assertTrue(File(file().path + ".corrupt").exists())
    }

    @Test
    fun oldEntriesAreEvicted() = runTest {
        val ledger = ActionLedger(file(), limit = 3)
        repeat(5) { ledger.run("tu-$it", "x") { ToolResult.ok(it).toJson() } }
        var ran = false
        ledger.run("tu-0", "x") { ran = true; ToolResult.ok("again").toJson() }
        assertTrue(ran)
    }
}
