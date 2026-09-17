package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.remote.RemoteUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addresses the empty library offers, held to what they have to be.
 *
 * They are the URLs in the app nobody types, so nothing in front of a
 * reader would report one going wrong: a connection made by tapping a
 * card would simply fail, on a screen whose whole purpose is somebody's
 * first minute with the app. These are the properties Project Gutenberg
 * actually cares about.
 */
class StarterCatalogTest {

    /** As `OpdsSetupClient` normalises it before probing. */
    private fun normalised(url: String) =
        RemoteUrl.normaliseBase(url, keepQuery = true, keepTrailingSlash = true)

    @Test
    fun `every address survives normalising exactly as written`() {
        // Gutenberg's two routes disagree about the trailing slash:
        // `search.opds` answers 403 without one and `bookshelf/N.opds`
        // answers 403 with one. Normalising is allowed to tidy an
        // address; it is not allowed to change which of the two this is.
        for (category in StarterCatalog.Category.entries) {
            assertEquals(category.url, normalised(category.url))
        }
    }

    @Test
    fun `a curated shelf is spelled without a trailing slash`() {
        val shelves = StarterCatalog.Category.entries
            .filter { it != StarterCatalog.Category.POPULAR }

        assertTrue(shelves.isNotEmpty())
        for (category in shelves) {
            assertTrue(category.url, category.url.endsWith(".opds"))
        }
    }

    @Test
    fun `the popular shelf keeps its slash and its sort`() {
        val url = StarterCatalog.Category.POPULAR.url

        assertTrue(url.substringBefore('?').endsWith("/"))
        // The query is the whole reason this feed is worth connecting
        // rather than the root: it is what puts the most-downloaded
        // books inside the walk's budget.
        assertTrue(url.contains("sort_order=downloads"))
    }

    @Test
    fun `every address is HTTPS, so no plain-text retry is ever needed`() {
        // The card connects with `allowHttp` false and asks nobody
        // anything. An address that needed the HTTP fallback would fail
        // silently rather than offer it.
        for (category in StarterCatalog.Category.entries) {
            assertTrue(category.url.startsWith("https://"))
        }
    }

    @Test
    fun `every address is one the OPDS scope accepts`() {
        for (category in StarterCatalog.Category.entries) {
            assertNotNull(category.url, OpdsScope.of(category.url))
        }
    }

    @Test
    fun `no two shelves are the same account`() {
        // A fingerprint takes in the path and the query, and it is what
        // every `books.url` on the shelf hangs off. Two categories
        // sharing one would have the second overwrite the first's books
        // rather than replace them.
        val fingerprints = StarterCatalog.Category.entries
            .map { OpdsScope.of(it.url)!!.fingerprint }

        assertEquals(fingerprints.size, fingerprints.toSet().size)
    }

    @Test
    fun `the default is one of the sizes offered`() {
        assertTrue(StarterCatalog.DEFAULT_SHELF in StarterCatalog.SIZES)
    }

    @Test
    fun `the largest shelf offered fits inside the walk's budget`() {
        // Gutenberg lists each book as a link to its own feed, so a
        // book is a request, plus one more per page of twenty-five. A
        // size the budget could not reach would quietly shelve fewer
        // books than the reader asked for.
        val biggest = StarterCatalog.SIZES.max()
        val pages = (biggest + PAGE - 1) / PAGE

        assertTrue(biggest + pages < OpdsCatalogClient.MAX_REQUESTS)
    }

    @Test
    fun `the book feeds the walk descends into are inside the scope`() {
        val scope = OpdsScope.of(StarterCatalog.Category.POPULAR.url)!!
        // Every entry in this feed is a navigation link to a one-book
        // feed elsewhere on the same host. A scope that refused them
        // would connect happily and shelve nothing.
        assertTrue(scope.mayFetch("https://www.gutenberg.org/ebooks/1342.opds".toHttpUrl()))
        assertTrue(
            scope.mayFetch(
                "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.small.jpg".toHttpUrl(),
            ),
        )
        assertTrue(
            scope.mayFetch("https://www.gutenberg.org/ebooks/1342.epub.noimages".toHttpUrl()),
        )
    }

    @Test
    fun `a curated shelf's book feeds are inside its scope too`() {
        val scope = OpdsScope.of(StarterCatalog.Category.SCIENCE_FICTION.url)!!

        assertTrue(scope.mayFetch("https://www.gutenberg.org/ebooks/84.opds".toHttpUrl()))
    }

    private companion object {
        /** Entries per page, as Gutenberg reports in `opensearch:itemsPerPage`. */
        const val PAGE = 25
    }
}
