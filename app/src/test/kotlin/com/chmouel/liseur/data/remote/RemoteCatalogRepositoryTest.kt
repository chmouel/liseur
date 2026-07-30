package com.chmouel.liseur.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import java.io.IOException
import java.net.SocketTimeoutException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a refresh leaves behind for the library to show.
 *
 * The rule every test here is really about is the last one: whatever
 * happens, the status must settle. A refresh that ends still marked as
 * refreshing is a spinner that never stops, and that is what a malformed
 * feed used to produce -- the exception it threw was not one anything
 * caught.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class RemoteCatalogRepositoryTest {

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

    private suspend fun connect() {
        db.remoteServerDao().upsert(
            RemoteServer(
                kind = ServerKind.KOMGA,
                baseUrl = "https://books.example",
                username = "reader",
                passwordCipher = null,
                apiKeyCipher = RemoteServer.seal("a-key"),
                accountId = "u1",
                userId = null,
                koboTokenCipher = null,
                canDownload = true,
                addedAt = 0L,
                catalogSyncedAt = null,
                positionSyncedAt = null,
                syncToken = null,
            ),
        )
    }

    /** A catalog that does whatever the test needs it to do. */
    private class FakeCatalog(
        private val walk: suspend (suspend (List<RemoteBook>) -> Unit) -> List<RemoteBook>,
    ) : CatalogSource {
        override suspend fun allBooks(
            baseUrl: String,
            credentials: RemoteCredentials,
            onPage: suspend (List<RemoteBook>) -> Unit,
        ): List<RemoteBook> = walk(onPage)

        override suspend fun search(
            baseUrl: String,
            credentials: RemoteCredentials,
            query: String,
        ): List<RemoteBook> = emptyList()
    }

    private fun repository(catalog: CatalogSource) = RemoteCatalogRepository(
        router = RemoteRouter(
            serverDao = db.remoteServerDao(),
            catalogs = mapOf(ServerKind.KOMGA to catalog),
            files = emptyMap(),
            positions = emptyMap(),
        ),
        serverDao = db.remoteServerDao(),
        bookDao = db.bookDao(),
    )

    private fun failing(e: Throwable) = FakeCatalog { throw e }

    private fun book(id: String) = RemoteBook(
        remoteId = id,
        title = id,
        author = null,
        coverHref = null,
        downloadHref = "/api/v1/books/$id/file",
        updatedAt = null,
        pageCount = null,
    )

    @Test
    fun `a refused sign-in is reported as such, not as being offline`() = runTest {
        connect()
        val repository = repository(failing(RemoteHttpFailure(SyncFailure.Unauthorised)))

        assertEquals(false, repository.refresh())
        assertEquals(CatalogStatus.Failed(SyncFailure.Unauthorised), repository.status.value)
    }

    @Test
    fun `a server in trouble keeps its code, so retrying stays worth it`() = runTest {
        connect()
        val repository = repository(failing(RemoteHttpFailure(SyncFailure.ServerError(503))))

        repository.refresh()

        val status = repository.status.value as CatalogStatus.Failed
        assertEquals(SyncFailure.ServerError(503), status.reason)
        assertTrue(status.reason.worthRetrying)
    }

    @Test
    fun `a slow server is waiting, not unreachable`() = runTest {
        connect()
        val repository = repository(failing(SocketTimeoutException("timed out")))

        repository.refresh()

        assertEquals(CatalogStatus.Failed(SyncFailure.Timeout), repository.status.value)
    }

    @Test
    fun `an unreachable server is offline`() = runTest {
        connect()
        val repository = repository(failing(IOException("no route to host")))

        repository.refresh()

        assertEquals(CatalogStatus.Failed(SyncFailure.Offline), repository.status.value)
    }

    /**
     * Signing out mid-walk is not a failure and must not be shown as
     * one: the answer is dropped because it belongs to an account that
     * is gone, which is exactly what was asked for.
     */
    @Test
    fun `signing out mid-refresh settles quietly rather than accusing the server`() = runTest {
        connect()
        val repository = repository(
            FakeCatalog { onPage ->
                onPage(listOf(book("b1")))
                db.remoteServerDao().delete()
                onPage(listOf(book("b2")))
                emptyList()
            },
        )

        assertEquals(false, repository.refresh())
        assertEquals(CatalogStatus.Idle, repository.status.value)
    }

    @Test
    fun `a refresh that works leaves nothing showing`() = runTest {
        connect()
        val repository = repository(FakeCatalog { onPage -> listOf(book("b1")).also { onPage(it) } })

        assertEquals(true, repository.refresh())
        assertEquals(CatalogStatus.Idle, repository.status.value)
        assertEquals(1, db.bookDao().allRemote().size)
    }

    @Test
    fun `a failure is cleared by the refresh that finally works`() = runTest {
        connect()
        repository(failing(IOException("no route to host"))).refresh()

        val repository = repository(FakeCatalog { onPage -> listOf(book("b1")).also { onPage(it) } })
        repository.refresh()

        assertEquals(CatalogStatus.Idle, repository.status.value)
    }

    /**
     * The library screen going away cancels the refresh. It leaves no
     * spinner behind, and it does not blame the server for something
     * nobody's server did.
     */
    @Test
    fun `a cancelled refresh does not leave the library spinning`() = runTest {
        connect()
        val started = CompletableDeferred<Unit>()
        val repository = repository(
            FakeCatalog {
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            },
        )

        val job = async { repository.refresh() }
        started.await()
        job.cancel()
        job.join()

        assertEquals(CatalogStatus.Idle, repository.status.value)
    }
}
