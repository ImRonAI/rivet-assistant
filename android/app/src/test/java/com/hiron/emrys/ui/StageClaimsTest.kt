package com.hiron.emrys.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The newest slot still on screen holds the robot page, an overlay's slot outranks the screens under it, and
 * handing the page back never skips a slot.
 */
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

    @Test
    fun anOverlayOutranksNewerScreenClaims() {
        val claims = StageClaims()
        val screen = claims.claim()
        val overlay = claims.claim(StageLayer.Overlay)
        val under = claims.claim()
        assertEquals(overlay, claims.owner)
        claims.release(overlay)
        assertEquals(under, claims.owner)
        claims.release(under)
        assertEquals(screen, claims.owner)
    }
}
