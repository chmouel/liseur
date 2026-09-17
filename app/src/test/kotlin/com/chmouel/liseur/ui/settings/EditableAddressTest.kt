package com.chmouel.liseur.ui.settings

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What "change address" puts in the field. The catalog address is what
 * the reader typed, and it is the only thing the field connects with,
 * so a Custom account paired with nothing but a sync server has to
 * open empty rather than offering that server as a shelf.
 */
class EditableAddressTest {

    @Test
    fun `a server offers the address its books come from`() {
        for (kind in listOf(ServerKind.CALIBRE, ServerKind.KOMGA, ServerKind.LISEUR_SYNC)) {
            assertEquals(
                "https://books.example",
                editableAddress(server(kind, catalogUrl = "https://books.example")),
            )
        }
    }

    @Test
    fun `a custom catalog offers the catalog address`() {
        assertEquals(
            "https://www.gutenberg.org/ebooks/search.opds/",
            editableAddress(
                server(
                    ServerKind.CUSTOM,
                    catalogUrl = "https://www.gutenberg.org/ebooks/search.opds/",
                ),
            ),
        )
    }

    @Test
    fun `a custom account with only a sync server opens empty`() {
        assertEquals(
            "",
            editableAddress(
                server(ServerKind.CUSTOM, catalogUrl = null, baseUrl = "https://sync.example"),
            ),
        )
    }

    private fun server(
        kind: ServerKind,
        baseUrl: String = "https://books.example",
        catalogUrl: String? = baseUrl,
    ) = RemoteServer(
        kind = kind,
        baseUrl = baseUrl,
        catalogUrl = catalogUrl,
        username = "ada",
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
    )
}
