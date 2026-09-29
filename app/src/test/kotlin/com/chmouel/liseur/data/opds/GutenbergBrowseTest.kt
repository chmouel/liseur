package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GutenbergBrowseTest {
    @Test
    fun `the dedicated root offers languages and categories`() {
        val server = server(StarterCatalog.BROWSE_URL)
        assertTrue(GutenbergBrowse.isRoot(server))
        assertEquals(
            listOf(GutenbergBrowse.LANGUAGES, GutenbergBrowse.CATEGORIES),
            GutenbergBrowse.rootCategories().map { it.id },
        )
        assertFalse(GutenbergBrowse.isRoot(server.copy(catalogUrl = StarterCatalog.Category.POPULAR.url)))
        assertFalse(GutenbergBrowse.isRoot(server.copy(kind = ServerKind.CALIBRE)))
    }

    @Test
    fun `language shelves lead to language-specific subject feeds`() {
        val languages = GutenbergBrowse.listing(GutenbergBrowse.LANGUAGES)!!.categories
        assertEquals(StarterCatalog.OFFERED_LANGUAGES.size, languages.size)
        val french = languages.single { GutenbergBrowse.languageCode(it.id) == "fr" }
        val shelves = GutenbergBrowse.listing(french.id)!!.categories
        assertFalse(shelves.any { GutenbergBrowse.shelfCategory(it.id) == StarterCatalog.Category.BEST_EVER })
        val scienceFiction = shelves.single {
            GutenbergBrowse.shelfCategory(it.id) == StarterCatalog.Category.SCIENCE_FICTION
        }
        assertEquals(StarterCatalog.Category.SCIENCE_FICTION.url("fr"), GutenbergBrowse.feedUrl(scienceFiction.id))
    }

    @Test
    fun `categories include the curated English shelves and reject invented routes`() {
        val categories = GutenbergBrowse.listing(GutenbergBrowse.CATEGORIES)!!.categories
        assertEquals(StarterCatalog.Category.entries.size, categories.size)
        val bestEver = categories.single {
            GutenbergBrowse.shelfCategory(it.id) == StarterCatalog.Category.BEST_EVER
        }
        assertEquals(StarterCatalog.Category.BEST_EVER.englishUrl, GutenbergBrowse.feedUrl(bestEver.id))
        assertNull(GutenbergBrowse.feedUrl("gutenberg:shelf:fr:BEST_EVER"))
        assertNull(GutenbergBrowse.listing("gutenberg:language:unknown"))
    }

    private fun server(catalogUrl: String) = RemoteServer(
        id = 2,
        kind = ServerKind.CUSTOM,
        baseUrl = catalogUrl,
        catalogUrl = catalogUrl,
        username = "",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 1,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
    )
}
