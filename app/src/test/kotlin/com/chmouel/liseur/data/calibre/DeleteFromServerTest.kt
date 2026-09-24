package com.chmouel.liseur.data.calibre

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.remote.BookDeleter
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerDeleteResult
import com.chmouel.liseur.data.remote.ServerKind
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a "deleted" from the server licenses here.
 *
 * The answer arrives after a network round trip. By then the reader may
 * have switched account, the entry may have been linked to another
 * server book, or its file replaced. The answer is about the book as it
 * was sent, so only that book may be removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DeleteFromServerTest {

    private lateinit var db: LiseurDatabase
    private lateinit var downloads: BookDownloadRepository
    private lateinit var server: RemoteServer
    private var connected: String? = null

    @Before
    fun open() = runBlocking {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).allowMainThreadQueries().build()
        val removal = BookRemoval(
            bookDao = db.bookDao(),
            sessionDao = db.readingSessionDao(),
            peerStateDao = db.syncPeerStateDao(),
            identityDao = db.workIdentityDao(),
            progressDao = db.readingProgressDao(),
            annotationDao = db.annotationDao(),
            annotationSyncDao = db.annotationSyncDao(),
            inTransaction = { work -> db.withTransaction { work() } },
        )
        // The repository asks for it as it is built; nothing here enqueues.
        runCatching {
            WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
        }
        downloads = BookDownloadRepository(
            context = context,
            bookDao = db.bookDao(),
            bookRemoval = removal,
            scope = CoroutineScope(Dispatchers.Unconfined),
            accountKey = { connected },
            inTransaction = { work -> db.withTransaction { work() } },
        )
        server = RemoteServer(
            kind = ServerKind.BOOKORBIT, baseUrl = "https://books.example", username = "reader",
            passwordCipher = null, apiKeyCipher = null, accountId = "1", userId = null,
            koboTokenCipher = null, canDownload = true, canDelete = true, addedAt = 1,
            catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
            orbitAccessCipher = RemoteServer.seal("access"), orbitRefreshCipher = RemoteServer.seal("refresh"),
            orbitAccessExpires = Long.MAX_VALUE, orbitEpoch = 7,
        )
        connected = server.accountKey
    }

    @After
    fun close() {
        db.close()
        downloads.booksDir().deleteRecursively()
        CredentialCipher.keyForTesting = null
    }

    private class Deleter(
        val result: ServerDeleteResult = ServerDeleteResult.Deleted,
        val during: suspend () -> Unit = {},
    ) : BookDeleter {
        val forgotten = mutableListOf<Pair<String, String>>()

        override suspend fun delete(
            baseUrl: String,
            credentials: RemoteCredentials,
            book: Book,
            forgetReading: Boolean,
        ): ServerDeleteResult {
            during()
            return result
        }

        override suspend fun forgetDeleted(bookUrl: String, accountKey: String) {
            forgotten += bookUrl to accountKey
        }
    }

    private suspend fun downloaded(uuid: String = "bo_scope_5"): Book {
        downloads.fileFor(uuid).writeText("the book")
        val book = Book(
            url = "bookorbit:$uuid", title = "One", author = null, coverPath = null, source = null,
            addedAt = 1, lastOpenedAt = null, localUri = downloads.localUriFor(uuid),
            remoteUuid = uuid, downloadState = DownloadState.DOWNLOADED,
        )
        db.bookDao().upsert(book)
        return book
    }

    @Test
    fun `a book the server deleted goes here with its file and its binding`() = runBlocking {
        val book = downloaded()
        val deleter = Deleter()

        assertEquals(ServerDeleteResult.Deleted, downloads.deleteFromServer(book, deleter, server))

        assertNull(db.bookDao().getByUrl(book.url))
        assertFalse(downloads.fileFor(book.remoteUuid!!).exists())
        assertEquals(listOf(book.url to server.accountKey), deleter.forgotten)
    }

    @Test
    fun `a refusal leaves everything here`() = runBlocking {
        val book = downloaded()
        val deleter = Deleter(ServerDeleteResult.NotAllowed)

        assertEquals(ServerDeleteResult.NotAllowed, downloads.deleteFromServer(book, deleter, server))

        assertNotNull(db.bookDao().getByUrl(book.url))
        assertTrue(downloads.fileFor(book.remoteUuid!!).exists())
        assertTrue(deleter.forgotten.isEmpty())
    }

    @Test
    fun `a book that changed while the delete was out stays here`() = runBlocking {
        val changes = listOf<suspend (Book) -> Unit>(
            { connected = "another account" },
            { db.bookDao().upsert(db.bookDao().getByUrl(it.url)!!.copy(remoteUuid = "bo_scope_6")) },
            { downloads.fileFor(it.remoteUuid!!).writeText("a different, longer book") },
        )
        for ((index, change) in changes.withIndex()) {
            connected = server.accountKey
            val book = downloaded("bo_scope_1$index")
            val deleter = Deleter(during = { change(book) })

            assertEquals(ServerDeleteResult.Deleted, downloads.deleteFromServer(book, deleter, server))

            assertNotNull("change $index", db.bookDao().getByUrl(book.url))
            assertTrue("change $index", downloads.fileFor(book.remoteUuid!!).exists())
            assertTrue("change $index", deleter.forgotten.isEmpty())
        }
    }

    @Test
    fun `a folder book whose file was replaced while the delete was out stays here`() = runBlocking {
        val source = java.io.File.createTempFile("folder", ".epub").apply { writeText("the book"); deleteOnExit() }
        val book = Book(
            url = "file://${source.path}", title = "One", author = null, coverPath = null, source = null,
            addedAt = 1, lastOpenedAt = null, localUri = "file://${source.path}",
            remoteUuid = "bo_scope_7", downloadState = DownloadState.DOWNLOADED,
        )
        db.bookDao().upsert(book)
        val deleter = Deleter(during = { source.writeText("a different, longer book") })

        assertEquals(ServerDeleteResult.Deleted, downloads.deleteFromServer(book, deleter, server))

        assertNotNull(db.bookDao().getByUrl(book.url))
        assertTrue(deleter.forgotten.isEmpty())
    }

    @Test
    fun `a document whose size and time cannot be read is kept after a server delete`() = runBlocking {
        Robolectric.buildContentProvider(Silent::class.java).create(SILENT)
        val book = Book(
            url = "content://$SILENT/document/one", title = "One", author = null, coverPath = null,
            source = null, addedAt = 1, lastOpenedAt = null, localUri = "content://$SILENT/document/one",
            remoteUuid = "bo_scope_8", downloadState = DownloadState.DOWNLOADED,
        )
        db.bookDao().upsert(book)
        val deleter = Deleter()

        assertEquals(ServerDeleteResult.Deleted, downloads.deleteFromServer(book, deleter, server))

        assertNotNull(db.bookDao().getByUrl(book.url))
        assertTrue(deleter.forgotten.isEmpty())
    }

    /** A provider that answers without the size or modification time. */
    class Silent : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: android.net.Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, sort: String?,
        ): android.database.Cursor = android.database.MatrixCursor(arrayOf("_display_name")).apply {
            addRow(arrayOf<Any>("one.epub"))
        }

        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(
            uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?,
        ) = 0
    }

    private companion object {
        const val SILENT = "com.chmouel.liseur.test.silent"
    }
}
