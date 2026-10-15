package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.RemoteServer
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class AccountBackupTest {
    @Test
    fun `every account kind retains usable secrets on a different device without sync metadata`() {
        fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        CredentialCipher.keyForTesting = key()
        try {
            val source = RemoteServer(kind = ServerKind.CALIBRE, baseUrl = "https://books.example",
                username = "reader", passwordCipher = RemoteServer.seal(""),
                apiKeyCipher = RemoteServer.seal("api-key"), accountId = "account", userId = 3,
                koboTokenCipher = RemoteServer.seal("kobo-token"), canDownload = true,
                addedAt = 10, catalogSyncedAt = 20, positionSyncedAt = 30, syncToken = "cursor",
                liseurTokenCipher = RemoteServer.seal("device-token"), liseurAccountId = "user",
                orbitAccessCipher = RemoteServer.seal("access-token"), orbitRefreshCipher = RemoteServer.seal("refresh-token"),
                orbitAccessExpires = 10000, orbitSessionId = 4, syncCursorSeq = 999, annotationCursorSeq = 888)
            val backups = ServerKind.entries.associateWith { AccountBackup.encode(source.copy(kind = it)) }
            CredentialCipher.keyForTesting = key()
            val expected = mapOf(
                ServerKind.CALIBRE to RemoteCredentials.Basic("reader", ""),
                ServerKind.KOMGA to RemoteCredentials.ApiKey("api-key"),
                ServerKind.LISEUR_SYNC to RemoteCredentials.Bearer("device-token"),
                ServerKind.BOOKORBIT to RemoteCredentials.Bearer("access-token"),
                ServerKind.CUSTOM to RemoteCredentials.Basic("reader", ""),
            )
            backups.forEach { (kind, json) ->
                assertFalse(json.toString().contains("cursor"))
                val restored = AccountBackup.decode(json, CredentialCipher::encrypt)
                assertEquals(expected.getValue(kind), restored.credentials)
                assertEquals("kobo-token", restored.koboToken)
                assertEquals("refresh-token", CredentialCipher.decrypt(restored.orbitRefreshCipher!!))
                assertNotEquals(source.passwordCipher, restored.passwordCipher)
                assertEquals(0L, restored.syncCursorSeq)
                assertEquals(0L, restored.annotationCursorSeq)
            }
        } finally { CredentialCipher.keyForTesting = null }
    }
}
