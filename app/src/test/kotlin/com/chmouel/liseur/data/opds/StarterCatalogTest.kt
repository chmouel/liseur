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

    @Test
    fun `language URLs query the language on search and survive normalisation`() {
        for (lang in listOf("fr", "de", "es", "it", "ru")) {
            val popularUrl = StarterCatalog.Category.POPULAR.url(lang)
            assertTrue(popularUrl.contains("query=l.$lang"))
            assertTrue(popularUrl.contains("sort_order=downloads"))
            assertEquals(popularUrl, normalised(popularUrl))
            assertNotNull(OpdsScope.of(popularUrl))

            val sciFiUrl = StarterCatalog.Category.SCIENCE_FICTION.url(lang)
            assertTrue(sciFiUrl.contains("query=s.science+fiction+l.$lang"))
            assertTrue(sciFiUrl.contains("sort_order=downloads"))
            assertEquals(sciFiUrl, normalised(sciFiUrl))
            assertNotNull(OpdsScope.of(sciFiUrl))
        }
    }

    @Test
    fun `english and unsupported languages fall back to default shelf`() {
        for (category in StarterCatalog.Category.entries) {
            assertEquals(category.englishUrl, category.url(null))
            assertEquals(category.englishUrl, category.url("en"))
            assertEquals(category.englishUrl, category.url("EN"))
            assertEquals(category.englishUrl, category.url("unsupported_language"))
            assertEquals(category.englishUrl, category.url(""))
        }
    }

    @Test
    fun `the offer leads with English and holds every supported language`() {
        assertEquals(StarterCatalog.DEFAULT_LANGUAGE, StarterCatalog.OFFERED_LANGUAGES.first())
        assertTrue(StarterCatalog.OFFERED_LANGUAGES.containsAll(StarterCatalog.SUPPORTED_LANGUAGES))
        assertEquals(
            StarterCatalog.SUPPORTED_LANGUAGES.size + 1,
            StarterCatalog.OFFERED_LANGUAGES.size,
        )
        assertEquals(
            StarterCatalog.OFFERED_LANGUAGES,
            StarterCatalog.OFFERED_LANGUAGES.distinct(),
        )
        // English is reached by not asking, so it is deliberately not a
        // feed of its own.
        assertTrue(StarterCatalog.DEFAULT_LANGUAGE !in StarterCatalog.SUPPORTED_LANGUAGES)
    }

    @Test
    fun `the picker opens on the reader's language when there is a shelf for it`() {
        for (lang in StarterCatalog.OFFERED_LANGUAGES) {
            assertEquals(lang, StarterCatalog.resolveLanguage(lang))
            assertEquals(lang, StarterCatalog.resolveLanguage(lang.uppercase()))
        }
    }

    @Test
    fun `a language with no shelf opens on English rather than on nothing`() {
        // A phone in Welsh or Arabic has no Gutenberg feed to offer, and
        // an empty shelf is a worse first minute than an English one.
        for (unknown in listOf(null, "", "cy", "ar", "unsupported_language")) {
            assertEquals(StarterCatalog.DEFAULT_LANGUAGE, StarterCatalog.resolveLanguage(unknown))
        }
    }

    @Test
    fun `every offered language names an address the catalog may walk`() {
        for (lang in StarterCatalog.OFFERED_LANGUAGES) {
            for (category in StarterCatalog.Category.entries) {
                val url = category.url(lang)
                assertEquals(url, normalised(url))
                assertNotNull(OpdsScope.of(url))
            }
        }
    }

    @Test
    fun `no two shelves are the same account in any language`() {
        // The English-only check above missed this: "Best books ever"
        // is a curated list with no subject to search by, so asking for
        // it in French once built the identical address "Most popular"
        // did. Two chips, one account, and no way to tell from the
        // shelf which had been tapped.
        for (lang in StarterCatalog.OFFERED_LANGUAGES) {
            val fingerprints = StarterCatalog.Category.entries
                .filter { it.offeredIn(lang) }
                .map { OpdsScope.of(it.url(lang))!!.fingerprint }

            assertEquals(lang, fingerprints.size, fingerprints.toSet().size)
        }
    }

    @Test
    fun `an English-only shelf is offered in English and nowhere else`() {
        for (lang in StarterCatalog.OFFERED_LANGUAGES) {
            for (category in StarterCatalog.Category.entries) {
                val offered = category.offeredIn(lang)
                if (lang == StarterCatalog.DEFAULT_LANGUAGE) {
                    assertTrue(offered)
                } else {
                    assertEquals(!category.englishOnly, offered)
                }
            }
        }
        // Most popular is every language's shelf; it is the one with no
        // curated list behind it to be English about.
        assertTrue(!StarterCatalog.Category.POPULAR.englishOnly)
        assertTrue(StarterCatalog.Category.BEST_EVER.englishOnly)
    }

    @Test
    fun `a language tag keeps its collection when it carries a region`() {
        // Gutenberg catalogues by language and knows nothing of where
        // it is spoken. Reading `pt-BR` as "no Portuguese here" would
        // answer a Brazilian reader in English.
        assertEquals("pt", StarterCatalog.resolveLanguage("pt-BR"))
        assertEquals("pt", StarterCatalog.resolveLanguage("pt_BR"))
        assertEquals("zh", StarterCatalog.resolveLanguage("zh-Hans"))
        assertEquals("fr", StarterCatalog.resolveLanguage("fr-CA"))
        assertEquals("en", StarterCatalog.resolveLanguage("en-GB"))

        assertEquals(
            StarterCatalog.Category.POPULAR.url("pt"),
            StarterCatalog.Category.POPULAR.url("pt-BR"),
        )
    }

    private companion object {
        /** Entries per page, as Gutenberg reports in `opensearch:itemsPerPage`. */
        const val PAGE = 25
    }
}
