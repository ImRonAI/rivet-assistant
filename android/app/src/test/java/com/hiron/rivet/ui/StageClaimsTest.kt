package com.hiron.rivet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The newest slot still on screen holds the robot page, and handing it back never skips a slot. */
class StageClaimsTest {
    @Test
    fun ownerIsTheNewestClaim() {
        val claims = StageClaims()
        claims.claim()
        val b = claims.claim()
        assertEquals(b, claims.owner)
    }

    @Test
    fun releasingTheOwnerHandsBackToTheOneBefore() {
        val claims = StageClaims()
        val a = claims.claim()
        val b = claims.claim()
        val c = claims.claim()
        claims.release(c)
        assertEquals(b, claims.owner)
        claims.release(a)
        assertEquals(b, claims.owner)
        claims.release(b)
        assertNull(claims.owner)
    }

    @Test
    fun releasingANonOwnerKeepsTheOwner() {
        val claims = StageClaims()
        val a = claims.claim()
        val b = claims.claim()
        claims.release(a)
        assertEquals(b, claims.owner)
    }

    @Test
    fun releasingTwiceDoesNothing() {
        val claims = StageClaims()
        val a = claims.claim()
        claims.release(a)
        claims.release(a)
        assertNull(claims.owner)
        val c = claims.claim()
        assertEquals(c, claims.owner)
    }
}
