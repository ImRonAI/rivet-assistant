package com.hiron.ren.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The favorite links, with the rules of ren desktop's favorites store (`src/stores/favoritesStore.ts`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FavoritesTest {
    private var saved: String? = null
    private fun favorites() = Favorites({ saved }, { saved = it })

    @Test
    fun aFreshInstallStartsWithRenDesktopsFavorites() {
        assertEquals(Favorites.DEFAULTS, favorites().list.value)
        assertEquals(listOf("GitHub", "Hacker News", "YouTube", "Wikipedia", "X", "Reddit"), Favorites.DEFAULTS.map { it.title })
    }

    @Test
    fun addressesAreNormalizedAsRenDesktopDoes() {
        assertEquals("https://example.com/", Favorites.normalize("example.com"))
        assertEquals("http://example.com/a?b=1", Favorites.normalize(" http://example.com/a?b=1 "))
        assertNull(Favorites.normalize(""))
        assertNull(Favorites.normalize("ftp://example.com"))
        assertNull(Favorites.normalize("javascript://alert(1)"))
    }

    @Test
    fun anAddedFavoriteIsKeptAndTitledByItsHostWhenUntitled() {
        val list = favorites()
        val added = list.add("", "news.example.org/today")
        assertEquals("news.example.org", added?.title)
        assertEquals("https://news.example.org/today", added?.url)
        assertEquals(added, list.list.value.last())
        assertEquals(list.list.value, favorites().list.value)
    }

    @Test
    fun theSameAddressIsNotAddedTwice() {
        val list = favorites()
        val first = list.add("Example", "example.com")
        assertSame(first, list.add("Again", "https://example.com/"))
        assertEquals(Favorites.DEFAULTS.size + 1, list.list.value.size)
    }

    @Test
    fun anInvalidAddressAddsNothing() {
        val list = favorites()
        assertNull(list.add("Nope", "not a url at all"))
        assertEquals(Favorites.DEFAULTS, list.list.value)
    }

    @Test
    fun removingAndReplacingIsSaved() {
        val list = favorites()
        list.remove("github")
        assertEquals(Favorites.DEFAULTS.drop(1), favorites().list.value)
        val fromDiscover = listOf(Favorite("fav-1", "Ren", "https://oubliai.com/"))
        list.replace(fromDiscover)
        assertEquals(fromDiscover, favorites().list.value)
    }

    @Test
    fun anEntryWithoutAnIdOrAddressIsLeftOut() {
        assertEquals(
            listOf(Favorite("a", "https://a.example/", "https://a.example/")),
            Favorites.parse("""[{"id":"a","url":"https://a.example/"},{"title":"No id","url":"https://b.example/"},{"id":"c","title":"No url"}]"""),
        )
        assertNull(Favorites.parse("not json"))
    }

    @Test
    fun theSiteIconComesFromGooglesFaviconService() {
        assertEquals("https://www.google.com/s2/favicons?domain=github.com&sz=64", Favorites.icon("https://github.com"))
    }

    @Test
    fun renDesktopsDestinationsAreRensPages() {
        val page = "https://appassets.androidplatform.net/assets/ren/web/destinations/index.html"
        for (destination in Destination.entries) {
            assertEquals("$page?tab=${destination.id}", Browser.internal(destination.address))
            assertEquals(destination.address, Browser.display("$page?tab=${destination.id}#signin"))
            assertEquals(destination.name, Addresses.resting(destination.address))
        }
        assertEquals("ren://execute", Destination.Execute.address)
        assertNull(Browser.internal("ren://projects"))
    }
}
