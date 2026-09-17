package com.chmouel.liseur.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a typed address, for the servers whose base carries a query.
 *
 * The other kinds drop it, and their own tests cover that. What is here
 * is what `keepQuery` changed: an OPDS root commonly picks a shelf, a
 * library or a user with a query, and two of those at one path are two
 * catalogs rather than one.
 */
class RemoteUrlTest {

    @Test
    fun `a query is kept when it is asked for`() {
        assertEquals(
            "https://books.example/opds?shelf=a",
            RemoteUrl.normaliseBase("books.example/opds?shelf=a", keepQuery = true),
        )
    }

    @Test
    fun `and dropped when it is not`() {
        assertEquals(
            "https://books.example/opds",
            RemoteUrl.normaliseBase("books.example/opds?shelf=a"),
        )
    }

    @Test
    fun `a trailing slash goes even with a query behind it`() {
        assertEquals(
            "https://books.example/opds?shelf=a",
            RemoteUrl.normaliseBase("books.example/opds/?shelf=a", keepQuery = true),
        )
    }

    @Test
    fun `unless the slash is part of what is being asked for`() {
        // A catalog address is fetched rather than built onto, and some
        // catalogs serve one spelling and refuse the other (#219).
        assertEquals(
            "https://books.example/search.opds/",
            RemoteUrl.normaliseBase("books.example/search.opds/", keepTrailingSlash = true),
        )
        assertEquals(
            "https://books.example/opds/?shelf=a",
            RemoteUrl.normaliseBase(
                "books.example/opds/?shelf=a",
                keepQuery = true,
                keepTrailingSlash = true,
            ),
        )
    }

    @Test
    fun `the other spelling of an address is the one with the slash flipped`() {
        assertEquals(
            "https://books.example/opds/",
            RemoteUrl.flipTrailingSlash("https://books.example/opds"),
        )
        assertEquals(
            "https://books.example/opds",
            RemoteUrl.flipTrailingSlash("https://books.example/opds/"),
        )
        assertEquals(
            "https://books.example/opds/?shelf=a",
            RemoteUrl.flipTrailingSlash("https://books.example/opds?shelf=a"),
        )
    }

    @Test
    fun `and a bare host has no other spelling`() {
        // `https://books.example` and `https://books.example/` are one
        // request; offering them as two would be a repeat, not a guess.
        assertNull(RemoteUrl.flipTrailingSlash("https://books.example"))
        assertNull(RemoteUrl.flipTrailingSlash("https://books.example/"))
    }

    @Test
    fun `an address that is only a query is not an address`() {
        // The host ends at the query as surely as at a slash. Read
        // otherwise, `?shelf=a` becomes its own hostname and normalises
        // to `https://?shelf=a`.
        assertNull(RemoteUrl.normaliseBase("?shelf=a", keepQuery = true))
        assertNull(RemoteUrl.normaliseBase("https://?shelf=a", keepQuery = true))
        assertNull(RemoteUrl.normaliseBase("?shelf=a"))
    }

    @Test
    fun `a fragment is never part of a base`() {
        assertEquals(
            "https://books.example/opds",
            RemoteUrl.normaliseBase("books.example/opds#top", keepQuery = true),
        )
    }

    /**
     * The stored spelling of a trailing slash changed between versions,
     * and the address is what decides whether a reconnection is the
     * same account or a stranger whose books are thrown away.
     */
    @Test
    fun `a trailing slash does not make it a different catalog`() {
        assertTrue(RemoteUrl.sameAddress("https://books.example/opds", "https://books.example/opds/"))
        assertTrue(RemoteUrl.sameAddress("https://books.example", "https://books.example/"))
        assertTrue(
            RemoteUrl.sameAddress(
                "https://www.gutenberg.org/ebooks/search.opds?sort_order=downloads",
                "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads",
            ),
        )
    }

    @Test
    fun `everything else still tells two addresses apart`() {
        assertFalse(RemoteUrl.sameAddress("https://books.example/opds", "https://books.example/feed"))
        assertFalse(RemoteUrl.sameAddress("https://books.example/opds", "http://books.example/opds"))
        assertFalse(
            RemoteUrl.sameAddress(
                "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads",
                "https://www.gutenberg.org/ebooks/search.opds/?sort_order=title",
            ),
        )
    }
}
