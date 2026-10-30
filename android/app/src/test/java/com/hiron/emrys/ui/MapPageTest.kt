package com.hiron.emrys.ui

import com.hiron.emrys.core.LatLng
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class MapPageTest {
    private val dir = File("src/main/assets/map")

    @Test
    fun thePayloadCarriesEachPinForThePageToSwapToLngLat() {
        val payload = JSONObject(MapPage.payload(listOf(MapPin(1, LatLng(30.27, -97.74), "Summit Coffee")), interactive = false, selected = 1))
        val pin = payload.getJSONArray("places").getJSONObject(0)
        assertEquals(30.27, pin.getDouble("lat"), 0.0)
        assertEquals(-97.74, pin.getDouble("lng"), 0.0)
        assertFalse(payload.getBoolean("interactive"))
        assertEquals(1, payload.getInt("selected"))
        assertTrue(JSONObject(MapPage.payload(emptyList(), true, null)).isNull("selected"))
        // MapLibre takes [lng, lat]; Brave and the payload give lat and lng.
        assertTrue(File(dir, "index.html").readText().contains("setLngLat([place.lng, place.lat])"))
    }

    @Test
    fun thePageUsesOpenFreeMapDarkAndKeepsItsAttribution() {
        val page = File(dir, "index.html").readText()
        assertTrue(page.contains("\"https://tiles.openfreemap.org/styles/dark\""))
        assertTrue(MapPage.TILES.startsWith("https://tiles.openfreemap.org/"))
        assertFalse(Regex("""attributionControl\s*:\s*false""").containsMatchIn(page))
    }

    @Test
    fun theVendoredMapLibreFilesAreTheRecordedOnes() {
        val expected = mapOf(
            "maplibre-gl.js" to "45a9b07a9189ce56054c620a947ccf41e291e58c95e9b61533b740aaa65ee5cb",
            "maplibre-gl.css" to "ab1e70d59ec40465bae7e7030da2f3ccf28133fd502e62bd598eefbadfd7a732",
            "LICENSE.txt" to "ee5fc05a0677eaf69601d2c7db0d9ecd6cc27c3abc1d0733bc9ed34707cf8ef2",
        )
        val source = File(dir, "SOURCE.txt").readText()
        expected.forEach { (name, sha) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(File(dir, name).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(name, sha, digest)
            assertTrue(source.contains(sha))
        }
    }
}
