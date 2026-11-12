package com.hiron.ren.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The address bar's rules, with the cases of ren desktop's own `navigable-url.test.ts`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddressesTest {
    @Test
    fun aBareHostGetsHttps() {
        assertEquals("https://example.com", Addresses.toNavigableUrl("example.com"))
        assertEquals("https://github.com/anthropics", Addresses.toNavigableUrl("github.com/anthropics"))
        assertEquals("https://www.example.com/a?b=1#c", Addresses.toNavigableUrl("www.example.com/a?b=1#c"))
        assertEquals("https://example.com:8443/x", Addresses.toNavigableUrl("example.com:8443/x"))
        assertEquals("https://cdn.example.com/a.js", Addresses.toNavigableUrl("//cdn.example.com/a.js"))
    }

    @Test
    fun localhostAndIpLiteralsGetHttp() {
        assertEquals("http://localhost", Addresses.toNavigableUrl("localhost"))
        assertEquals("http://localhost:3000", Addresses.toNavigableUrl("localhost:3000"))
        assertEquals("http://LOCALHOST:5173/app", Addresses.toNavigableUrl("LOCALHOST:5173/app"))
        assertEquals("http://192.168.1.1:8080", Addresses.toNavigableUrl("192.168.1.1:8080"))
        assertEquals("http://127.0.0.1/health?x=1", Addresses.toNavigableUrl("127.0.0.1/health?x=1"))
        assertEquals("http://[::1]:8765", Addresses.toNavigableUrl("[::1]:8765"))
    }

    @Test
    fun aHostThatOnlyStartsLikeLocalhostOrAnIpIsANormalHost() {
        assertEquals("https://localhost.example.com", Addresses.toNavigableUrl("localhost.example.com"))
        assertEquals("https://1.2.3.4.example.com", Addresses.toNavigableUrl("1.2.3.4.example.com"))
    }

    @Test
    fun addressesThatNameASchemeAreUnchanged() {
        listOf(
            "https://example.com", "HTTP://example.com", "ren://home", "ren://search?q=a%20b&level=medium",
            "file:///Users/me/a.html", "about:blank", "mailto:me@example.com", "data:text/plain,hi",
        ).forEach { assertEquals(it, Addresses.toNavigableUrl(it)) }
        assertEquals("https://example.com", Addresses.toNavigableUrl("  https://example.com  "))
        assertEquals("", Addresses.toNavigableUrl("   "))
    }

    @Test
    fun aPhraseIsASearchAndAnAddressLoads() {
        assertEquals("ren://search?q=best%20ramen%20in%20reno&level=low", Addresses.resolve("best ramen in reno", SearchLevel.Balanced))
        assertEquals("ren://search?q=weather&level=xhigh", Addresses.resolve("weather", SearchLevel.Max))
        assertEquals("https://example.com/a", Addresses.resolve("example.com/a", SearchLevel.Balanced))
        assertEquals("http://localhost:3000", Addresses.resolve("localhost:3000", SearchLevel.Balanced))
        assertEquals("ren://search?q=cats&level=high", Addresses.resolve("ren://search?q=cats&level=high", SearchLevel.Balanced))
        assertEquals("", Addresses.resolve("  ", SearchLevel.Balanced))
        assertTrue(Addresses.looksLikeUrl("example.com"))
        assertFalse(Addresses.looksLikeUrl("what is example.com"))
    }

    @Test
    fun aSearchAddressGivesItsQueryAndLevelBack() {
        val url = Addresses.searchUrl("a b & c", SearchLevel.Deep)
        assertEquals("a b & c", Addresses.searchQuery(url))
        assertEquals(SearchLevel.Deep, Addresses.searchLevel(url))
        assertNull(Addresses.searchQuery("https://example.com/?q=x"))
        assertNull(Addresses.searchLevel("ren://search?q=x&level=nope"))
    }

    @Test
    fun atRestTheBarShowsThePagesIdentity() {
        assertEquals("best ramen", Addresses.resting(Addresses.searchUrl("best ramen", SearchLevel.Balanced)))
        assertEquals("example.com/a?b=1", Addresses.resting("https://www.example.com/a?b=1"))
        assertEquals("example.com", Addresses.resting("https://example.com/"))
        assertEquals("http://localhost:3000/", Addresses.resting("http://localhost:3000/"))
        assertEquals("", Addresses.resting(Addresses.HOME))
        assertTrue(Addresses.secure("https://example.com"))
        assertTrue(Addresses.secure("ren://home"))
        assertFalse(Addresses.secure("http://example.com"))
    }
}
