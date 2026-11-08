package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What makes a saved connection one of the free catalogs.
 *
 * The stored address is the one that answered a probe, not the one that
 * was typed, so the match cannot be a string comparison: a redirect, a
 * `www.` or a trailing slash would each save a second copy of the same
 * catalog and leave the reader with two of everything.
 */
class FreeCatalogTest {

    private fun server(
        address: String?,
        kind: ServerKind = ServerKind.CUSTOM,
    ) = RemoteServer(
        id = 2L,
        kind = kind,
        baseUrl = address ?: "https://books.example.com",
        username = "",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 0L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
        catalogUrl = address,
    )

    @Test
    fun `every preset is a distinct https catalog`() {
        val identities = FreeCatalog.entries.map { FreeCatalog.identity(it.address) }
        assertEquals(FreeCatalog.entries.size, identities.filterNotNull().distinct().size)
        assertTrue(FreeCatalog.entries.all { it.address.startsWith("https://") })
        assertEquals(StarterCatalog.BROWSE_URL, FreeCatalog.GUTENBERG.address)
    }

    @Test
    fun `a saved catalog is matched whatever spelling answered the probe`() {
        listOf(
            "https://www.gutenberg.org/ebooks.opds/",
            "https://gutenberg.org/ebooks.opds/",
            "http://WWW.Gutenberg.org/ebooks.opds",
        ).forEach { address ->
            assertEquals(
                FreeCatalog.GUTENBERG,
                FreeCatalog.of(server(address)),
            )
        }
    }

    @Test
    fun `another path on the same host is another catalog`() {
        assertNull(FreeCatalog.of(server(StarterCatalog.Category.POPULAR.url)))
        assertEquals(FreeCatalog.GUTENBERG, FreeCatalog.of(server(StarterCatalog.BROWSE_URL)))
    }

    @Test
    fun `only a custom OPDS connection can be a preset`() {
        assertNull(FreeCatalog.of(server(StarterCatalog.BROWSE_URL, ServerKind.CALIBRE)))
        assertNull(FreeCatalog.of(server(null)))
        assertFalse(FreeCatalog.GUTENBERG.matches(server("https://example.org/opds")))
    }
}
