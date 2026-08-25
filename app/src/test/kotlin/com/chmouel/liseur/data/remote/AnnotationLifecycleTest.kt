package com.chmouel.liseur.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookRemoval
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

/**
 * What happens to annotation state when an account goes away.
 *
 * A rev is a number only the server that issued it can read. Offering
 * one to a different account has it refuse edits over a history it has
 * no part in, so every door out of an account — disconnecting, pairing
 * as somebody else, and finding the stored credential unreadable — has
 * to leave the same nothing behind. The marks themselves are the
 * reader's and stay; only the agreements go.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class AnnotationLifecycleTest {

    private lateinit var db: LiseurDatabase

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
            LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() {
        db.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `disconnecting forgets every agreement and keeps every mark`() = runTest {
        val repository = repository(RotatingLiseurSync("acc-1"))
        repository.connectLiseurSyncToken(BASE, "token-1")
        val peer = seedAnnotationState()

        repository.disconnect()

        assertForgotten(peer)
    }

    @Test
    fun `pairing as somebody else does not hand them the last account's agreements`() = runTest {
        val repository = repository(RotatingLiseurSync("acc-1"))
        repository.connectLiseurSyncToken(BASE, "token-1")
        val peer = seedAnnotationState()

        repository(RotatingLiseurSync("acc-2")).connectLiseurSyncToken(BASE, "token-x")

        assertForgotten(peer)
    }

    @Test
    fun `an account whose credential cannot be read leaves nothing behind`() = runTest {
        val repository = repository(RotatingLiseurSync("acc-1"))
        repository.connectLiseurSyncToken(BASE, "token-1")
        val peer = seedAnnotationState()

        // What a database restored onto another phone looks like: the
        // row is there, the ciphertext is unopenable by this device's
        // Keystore. This used to drop the row on its own, without going
        // through the same door as a disconnect.
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertTrue(repository.forgetUnreadableAccount())

        assertNull(db.remoteServerDao().get())
        assertForgotten(peer)
    }

    @Test
    fun `a rotated token keeps the account's place in the annotation feed`() = runTest {
        val setup = RotatingLiseurSync("acc-1")
        val repository = repository(setup)
        repository.connectLiseurSyncToken(BASE, "token-1")
        db.remoteServerDao().setAnnotationCursor(4_100)

        // A revoked credential replaced. The device id changes; the
        // account does not, and neither does what it has already read.
        repository.connectLiseurSyncToken(BASE, "token-2")

        assertEquals(4_100, db.remoteServerDao().get()!!.annotationCursorSeq)
    }

    /** The state one synced highlight leaves across four tables. */
    private suspend fun seedAnnotationState(): String {
        val peer = requireNotNull(db.remoteServerDao().get()).accountKey
        db.remoteServerDao().setAnnotationCursor(77)
        db.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = BOOK,
                peerId = peer,
                workId = "w-1",
                confidence = "high",
                confirmed = true,
                seeded = true,
                sourceSent = true,
                editionSha = null,
                annotationsReconciledAt = 1_700_000_000_000,
                resolvedAt = 1_700_000_000_000,
            ),
        )
        db.annotationDao().upsert(
            BookAnnotation(
                id = "mark-1",
                bookId = BOOK,
                kind = AnnotationKind.HIGHLIGHT.name,
                chapter = "One",
                text = "a sentence",
                note = null,
                tint = "YELLOW",
                locatorJson = """{"href":"one"}""",
                position = 1,
                totalProgression = 0.25,
                createdAt = 1_700_000_000,
                updatedAt = 1_700_000_000_000_000,
            ),
        )
        db.annotationSyncDao().upsert(
            AnnotationSync(
                id = "mark-1",
                peerId = peer,
                bookId = BOOK,
                workId = "w-1",
                rev = 4,
                seq = 12,
                ackedFingerprint = "settled",
            ),
        )
        return peer
    }

    private suspend fun assertForgotten(peer: String) {
        assertEquals(emptyList<AnnotationSync>(), db.annotationSyncDao().forPeer(peer))
        assertNull(db.workIdentityDao().alias(BOOK, peer))
        assertEquals(0, db.remoteServerDao().get()?.annotationCursorSeq ?: 0)
        // The reader's own marks were never the server's to take away.
        assertNotNull(db.annotationDao().byId("mark-1"))
    }

    private fun repository(setup: ServerSetup) = RemoteAccountRepository(
        dao = db.remoteServerDao(),
        bookDao = db.bookDao(),
        progressDao = db.readingProgressDao(),
        bookRemoval = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        ),
        seriesExtraDao = db.seriesExtraDao(),
        peerStateDao = db.syncPeerStateDao(),
        identityDao = db.workIdentityDao(),
        annotationSyncDao = db.annotationSyncDao(),
        setups = mapOf(ServerKind.LISEUR_SYNC to setup),
    )

    private class RotatingLiseurSync(private val accountId: String) : ServerSetup {
        private var mints = 0

        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult = SetupResult.Success(
            ServerCapabilities(
                baseUrl = rawUrl,
                canDownload = true,
                accountId = "device-${++mints}",
                displayName = "reader",
                liseurToken = "token-$mints",
                liseurAccountId = accountId,
            ),
        )
    }

    private companion object {
        const val BASE = "https://sync.example"
        const val BOOK = "content://sd/a-book.epub"
    }
}
