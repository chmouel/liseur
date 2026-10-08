package com.chmouel.liseur.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A shelved book whose content:// permission is gone, as after a
 * reinstall or when the app that provided it was removed. Readium
 * 3.4.0 lets the provider's SecurityException out of
 * `AssetRetriever.retrieve()`, which crashed the author backfill
 * through exactly this path in production (0.21.0).
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class RevokedContentPermissionTest {

    private lateinit var db: LiseurDatabase
    private lateinit var retriever: AssetRetriever
    private lateinit var library: LocalLibraryRepository

    @Before
    fun open() {
        Robolectric.buildContentProvider(Revoked::class.java).create(AUTHORITY)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java)
            .allowMainThreadQueries().build()
        val httpClient = DefaultHttpClient()
        retriever = permissionSafeAssetRetriever(context.contentResolver, httpClient)
        library = LocalLibraryRepository(
            context = context,
            assetRetriever = retriever,
            publicationOpener = PublicationOpener(DefaultPublicationParser(
                context, httpClient = httpClient, assetRetriever = retriever, pdfFactory = null,
            )),
            bookDao = db.bookDao(),
            folderDao = db.libraryFolderDao(),
            bookRemoval = BookRemoval(
                bookDao = db.bookDao(),
                sessionDao = db.readingSessionDao(),
                peerStateDao = db.syncPeerStateDao(),
                identityDao = db.workIdentityDao(),
                progressDao = db.readingProgressDao(),
                annotationDao = db.annotationDao(),
                annotationSyncDao = db.annotationSyncDao(),
                inTransaction = { work -> db.withTransaction { work() } },
            ),
            fingerprints = BookFingerprintStore(context, db.workIdentityDao()),
        )
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `retrieving a book we may no longer read is a failure`() = runTest {
        assertNotNull(retriever.retrieve(AbsoluteUrl(BOOK_URL)!!).failureOrNull())
    }

    @Test
    fun `author backfill leaves a book we may no longer read for later`() = runTest {
        val book = Book(
            url = BOOK_URL,
            title = "Revoked",
            author = "Someone",
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = null,
            downloadState = DownloadState.DOWNLOADED,
            localUri = BOOK_URL,
            identityAuthor = null,
        )
        val id = db.bookDao().upsert(book)

        library.backfillAuthors()

        assertEquals(book.copy(id = id), db.bookDao().getByUrl(BOOK_URL))
    }

    class Revoked : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            throw SecurityException("Permission Denial: no grant for $uri")
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, sort: String?,
        ): Cursor? = null
        // An unreachable provider gives no type either, which is what
        // sends Readium to read the bytes and hit the SecurityException.
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, args: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "com.chmouel.liseur.test.revoked"
        const val BOOK_URL = "content://$AUTHORITY/book.epub"
    }
}
