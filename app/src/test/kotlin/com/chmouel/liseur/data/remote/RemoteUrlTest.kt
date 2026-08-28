package com.chmouel.liseur.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
