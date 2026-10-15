package com.chmouel.liseur.data.library

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.SyncPeerState
import com.chmouel.liseur.data.kosync.KosyncAccountRepository
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.security.SecretCipher
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.providers.ServiceAccounts
import com.chmouel.liseur.tts.ServerKeys
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ConnectionBackupRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `offline replacement reencrypts accounts and derived keys and replaces service keys without peer state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        CredentialCipher.keyForTesting = newKey()
        try {
            val app = AppSettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(folder.root, "app.preferences_pb")
            })
            val cipher = SecretCipher("test-services").apply { keyForTesting = newKey() }
            val keys = ServerKeys(folder.root, cipher)
            val services = ServerConnections(app, keys, scope = backgroundScope)
            val kosync = KosyncAccountRepository(db.kosyncPeerDao(), db.syncPeerStateDao())
            val account = RemoteAccountRepository(
                dao = db.remoteServerDao(), bookDao = db.bookDao(), progressDao = db.readingProgressDao(),
                bookRemoval = BookRemoval(db.bookDao(), db.readingSessionDao(), db.syncPeerStateDao(),
                    db.workIdentityDao(), db.readingProgressDao(), db.annotationDao(), db.annotationSyncDao()),
                seriesExtraDao = db.seriesExtraDao(), peerStateDao = db.syncPeerStateDao(), setups = emptyMap(),
                inTransaction = { work -> db.withTransaction { work() } },
            )
            var stopped = 0
            val repository = ConnectionBackupRepository(account, kosync, app, services,
                ServiceAccounts(context, services), beforeReplace = { stopped++ })
            val source = RemoteServer(kind = ServerKind.LISEUR_SYNC, baseUrl = "https://library.example",
                username = "reader", passwordCipher = null, apiKeyCipher = null, accountId = "device",
                userId = null, koboTokenCipher = null, canDownload = true, addedAt = 10,
                catalogSyncedAt = 20, positionSyncedAt = 30, syncToken = "not-portable",
                liseurTokenCipher = RemoteServer.seal("device-token"), liseurAccountId = "user",
                syncCursorSeq = 80, annotationCursorSeq = 70)
            db.remoteServerDao().upsert(source)
            val pairing = KosyncPeer(baseUrl = "https://sync.example", username = "reader",
                keyCipher = KosyncPeer.seal("a".repeat(32)), addedAt = 10, positionSyncedAt = 90)
            db.kosyncPeerDao().upsert(pairing)
            app.addServer("Voice", "https://voice.example/v1")
            keys.set("https://voice.example:443", "service-key")
            val values = app.backupValues()
            val payload = repository.export()
            ConnectionBackupRepository.validate(payload, values)
            assertFalse(payload.toString().contains("not-portable"))
            CredentialCipher.keyForTesting = newKey()
            cipher.keyForTesting = newKey()
            // Replacing even the same account drops its destination agreements.
            db.syncPeerStateDao().upsert(SyncPeerState("book", source.accountKey, ackedRevision = 99))
            db.syncPeerStateDao().upsert(SyncPeerState("book", pairing.accountKey, ackedRevision = 99))
            app.addServer("Old", "https://old.example/v1")
            keys.set("https://old.example:443", "old-service-key")
            keys.set("https://voice.example:443", "replaced-key")
            val owners = mutableListOf<String>()
            services.addListener { owners += it }
            repository.prepareRestore(payload, values)()
            val restored = account.current()!!
            assertEquals(RemoteCredentials.Bearer("device-token"), restored.credentials)
            assertNotEquals(source.liseurTokenCipher, restored.liseurTokenCipher)
            assertEquals(0L, restored.syncCursorSeq)
            assertEquals(0L, restored.annotationCursorSeq)
            assertNull(restored.syncToken)
            assertNull(restored.catalogSyncedAt)
            assertEquals("a".repeat(32), kosync.current()!!.credentials!!.key)
            assertNotEquals(pairing.keyCipher, kosync.current()!!.keyCipher)
            assertNull(kosync.current()!!.positionSyncedAt)
            assertTrue(db.syncPeerStateDao().forPeer(source.accountKey).isEmpty())
            assertTrue(db.syncPeerStateDao().forPeer(pairing.accountKey).isEmpty())
            assertEquals("service-key", keys.get("https://voice.example:443"))
            assertNull(keys.get("https://old.example:443"))
            assertTrue(owners.containsAll(listOf("https://voice.example:443", "https://old.example:443", "gemini")))
            assertEquals(1, stopped)
            // An empty saved connection set replaces destination accounts and keys too.
            payload.put("account", org.json.JSONObject.NULL).put("kosync", org.json.JSONObject.NULL)
                .put("keys", org.json.JSONObject())
            repository.prepareRestore(payload, values)()
            assertNull(account.current())
            assertNull(kosync.current())
            assertNull(keys.get("https://voice.example:443"))
        } finally {
            db.close()
            CredentialCipher.keyForTesting = null
        }
    }
}
