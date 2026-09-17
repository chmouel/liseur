package com.chmouel.liseur.ui.settings

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether submitting the address form would leave the account it opened
 * over. A yes costs a running bulk download, so a correction that names
 * the same account must read as no; anything the typed form cannot
 * vouch for reads as yes.
 */
class SwitchesAccountTest {

    @Test
    fun `a form over nothing switches nothing`() {
        assertFalse(state(server = null, url = "https://books.example").switchesAccount())
    }

    @Test
    fun `a form that is not editing switches nothing`() {
        assertFalse(
            state(url = "https://elsewhere.example", editing = false).switchesAccount(),
        )
    }

    @Test
    fun `the same address is not a switch`() {
        assertFalse(state(url = "https://books.example").switchesAccount())
    }

    @Test
    fun `a trailing slash is not a switch`() {
        assertFalse(state(url = "https://books.example/").switchesAccount())
    }

    @Test
    fun `surrounding space is not a switch`() {
        assertFalse(state(url = "  https://books.example  ").switchesAccount())
    }

    @Test
    fun `another address is a switch`() {
        assertTrue(state(url = "https://elsewhere.example").switchesAccount())
    }

    @Test
    fun `another kind is a switch`() {
        assertTrue(state(url = "https://books.example", kind = ServerKind.KOMGA).switchesAccount())
    }

    @Test
    fun `another name is a switch`() {
        assertTrue(state(url = "https://books.example", username = "grace").switchesAccount())
    }

    @Test
    fun `a komga key is what names its account`() {
        // The cipher cannot be read in a JVM test, so the stored key is
        // unreadable here — which is the case the rule is written for.
        // A credential this form cannot vouch for reads as a switch.
        val base = state(
            server = server(ServerKind.KOMGA),
            kind = ServerKind.KOMGA,
            url = "https://books.example",
        )

        assertTrue(base.copy(apiKey = "second").switchesAccount())
    }

    private fun state(
        server: RemoteServer? = server(ServerKind.CALIBRE),
        url: String,
        kind: ServerKind = ServerKind.CALIBRE,
        username: String = "ada",
        editing: Boolean = true,
    ) = ServerAccountUiState(
        server = server,
        editingAddress = editing,
        kind = kind,
        url = url,
        username = username,
    )

    private fun server(kind: ServerKind) = RemoteServer(
        kind = kind,
        baseUrl = "https://books.example",
        catalogUrl = "https://books.example",
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
