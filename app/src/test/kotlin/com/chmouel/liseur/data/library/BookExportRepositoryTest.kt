package com.chmouel.liseur.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BookExportRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LiseurDatabase
    private val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "destination")

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        FakeDocs.directory = folder.newFolder("documents")
        FakeDocs.names.clear()
        FakeDocs.sources.clear()
        FakeDocs.deleted.clear()
        FakeDocs.failWrites.clear()
        FakeDocs.writable = true
        FakeDocs.loading = false
        FakeDocs.onCreateDocument = {}
        Robolectric.buildContentProvider(FakeDocs::class.java).create(AUTHORITY)
    }

    @After fun close() = db.close()

    private fun repository() = BookExportRepository(context, db.bookDao())

    private fun book(title: String, url: String, local: String? = null) = Book(
        url = url, title = title, author = "Author", coverPath = null, source = null,
        addedAt = 0, lastOpenedAt = null, localUri = local,
    )

    private fun source(name: String, bytes: ByteArray = name.toByteArray()) =
        folder.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `copies all offline categories byte for byte and excludes remote only books`() = runTest {
        val data = ByteArray(150_000) { (it % 255).toByte() }
        val downloaded = source("download.epub", data)
        val imported = source("import.epub")
        val hidden = source("hidden.epub")
        val archived = source("archived.epub")
        db.bookDao().upsert(book("Download", "calibre:download", Uri.fromFile(downloaded).toString()))
        db.bookDao().upsert(book("Imported", Uri.fromFile(imported).toString()))
        db.bookDao().upsert(book("Hidden", Uri.fromFile(hidden).toString()).copy(hiddenAt = 1))
        db.bookDao().upsert(book("Archived", Uri.fromFile(archived).toString()).copy(archivedAt = 1))
        db.bookDao().upsert(book("Remote", "calibre:remote").copy(downloadState = DownloadState.REMOTE))
        val progress = mutableListOf<BookExportProgress>()

        assertEquals(BookExportResult.Completed(BookExportProgress(4, exported = 4)), repository().exportTo(tree, progress::add))
        assertEquals(4, FakeDocs.names.size)
        assertEquals(data.toList(), FakeDocs.fileNamed("Download - Author.epub").readBytes().toList())
        assertEquals(imported.readBytes().toList(), FakeDocs.fileNamed("Imported - Author.epub").readBytes().toList())
        assertEquals(listOf(0, 1, 2, 3, 4), progress.map { it.processed })
        assertEquals(BookExportResult.Completed(BookExportProgress(4, skipped = 4)), repository().exportTo(tree) {})
        assertEquals(5, db.bookDao().allOnce().size)
    }

    @Test
    fun `watched books use the surviving tree rather than their original URL`() = runTest {
        val source = source("watched.epub")
        FakeDocs.sources["watched/book.epub"] = source
        val old = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, "released-tree"), "watched/book.epub",
        )
        val surviving = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "surviving-tree")
        db.bookDao().upsert(book("Watched", old.toString()).copy(source = surviving.toString()))

        assertEquals(BookExportResult.Completed(BookExportProgress(1, exported = 1)), repository().exportTo(tree) {})
        assertEquals(source.readBytes().toList(), FakeDocs.fileNamed("Watched - Author.epub").readBytes().toList())
    }

    @Test
    fun `existing names and duplicate generated names are skipped case insensitively`() = runTest {
        FakeDocs.names["existing"] = "EXISTING - AUTHOR.EPUB"
        File(FakeDocs.directory, "existing").writeText("Do not replace")
        val file = source("source.epub")
        db.bookDao().upsert(book("Existing", Uri.fromFile(file).toString()))
        db.bookDao().upsert(book("Duplicate", "calibre:a", Uri.fromFile(file).toString()))
        db.bookDao().upsert(book("Duplicate", "calibre:b", Uri.fromFile(file).toString()))

        assertEquals(BookExportResult.Completed(BookExportProgress(3, exported = 1, skipped = 2)), repository().exportTo(tree) {})
        assertEquals("Do not replace", File(FakeDocs.directory, "existing").readText())
        assertEquals(2, FakeDocs.names.size)
    }

    @Test
    fun `missing sources and failed writes do not stop other books and partial files are removed`() = runTest {
        val file = source("source.epub")
        db.bookDao().upsert(book("Missing", Uri.fromFile(File(folder.root, "gone.epub")).toString()))
        db.bookDao().upsert(book("Failed", Uri.fromFile(file).toString()))
        db.bookDao().upsert(book("Good", "calibre:good", Uri.fromFile(file).toString()))
        FakeDocs.failWrites += "Failed - Author.epub"

        assertEquals(BookExportResult.Completed(BookExportProgress(3, exported = 1, failed = 2)), repository().exportTo(tree) {})
        assertEquals(listOf("Good - Author.epub"), FakeDocs.names.values.toList())
        assertEquals(1, FakeDocs.deleted.size)
    }

    @Test
    fun `empty libraries and unavailable or incomplete folders are explicit results`() = runTest {
        assertEquals(BookExportResult.Empty, repository().exportTo(tree) {})
        db.bookDao().upsert(book("Book", Uri.fromFile(source("source.epub")).toString()))
        FakeDocs.writable = false
        assertEquals(BookExportResult.Failed(BookExportResult.Failure.FOLDER), repository().exportTo(tree) {})
        FakeDocs.writable = true
        FakeDocs.loading = true
        assertEquals(BookExportResult.Failed(BookExportResult.Failure.FOLDER), repository().exportTo(tree) {})
        assertTrue(FakeDocs.names.isEmpty())
    }

    @Test
    fun `cancellation removes the current file and keeps completed copies`() = runTest {
        val file = source("source.epub")
        db.bookDao().upsert(book("A", "calibre:a", Uri.fromFile(file).toString()))
        db.bookDao().upsert(book("B", "calibre:b", Uri.fromFile(file).toString()))
        db.bookDao().upsert(book("C", "calibre:c", Uri.fromFile(file).toString()))
        // Cancel an isolated job, leaving this test's own job alive for assertions.
        val job = Job(currentCoroutineContext()[Job])
        FakeDocs.onCreateDocument = { if (FakeDocs.names.size == 2) job.cancel() }
        val progress = mutableListOf<BookExportProgress>()
        val outcome = runCatching {
            kotlinx.coroutines.withContext(job) { repository().exportTo(tree, progress::add) }
        }

        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(listOf("A - Author.epub"), FakeDocs.names.values.toList())
        assertEquals(BookExportProgress(3, exported = 1), progress.last())
        assertEquals(1, FakeDocs.deleted.size)
        assertFalse(FakeDocs.fileNamed("A - Author.epub").length() == 0L)
    }

    class FakeDocs : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, sort: String?,
        ): Cursor = MatrixCursor(projection!!).apply {
            if (uri.lastPathSegment == "children") {
                names.values.forEach { addRow(arrayOf(it)) }
                extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, loading) }
            } else {
                addRow(arrayOf(if (writable) Document.FLAG_DIR_SUPPORTS_CREATE else 0))
            }
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            when (method) {
                "android:createDocument" -> {
                    val name = extras!!.getString(Document.COLUMN_DISPLAY_NAME)!!
                    val id = "export-${names.size}"
                    names[id] = name
                    File(directory, id).createNewFile()
                    onCreateDocument()
                    return Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUri(AUTHORITY, id)) }
                }
                "android:deleteDocument" -> {
                    val uri = extras!!.getParcelable<Uri>("uri")!!
                    val id = DocumentsContract.getDocumentId(uri)
                    deleted += id
                    names.remove(id)
                    File(directory, id).delete()
                    return Bundle()
                }
            }
            return null
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = DocumentsContract.getDocumentId(uri)
            if (mode.startsWith("w")) {
                if (names[id] in failWrites) throw FileNotFoundException("Write failed")
                return ParcelFileDescriptor.open(File(directory, id), ParcelFileDescriptor.MODE_WRITE_ONLY)
            }
            if (DocumentsContract.isTreeUri(uri) && DocumentsContract.getTreeDocumentId(uri) == "released-tree") {
                throw SecurityException("Released tree permission")
            }
            return ParcelFileDescriptor.open(sources.getValue(id), ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String = "application/epub+zip"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, args: Array<out String>?) = 0

        companion object {
            lateinit var directory: File
            val names = linkedMapOf<String, String>()
            val sources = mutableMapOf<String, File>()
            val deleted = mutableListOf<String>()
            val failWrites = mutableSetOf<String>()
            var writable = true
            var loading = false
            var onCreateDocument: () -> Unit = {}
            fun fileNamed(name: String) = File(directory, names.entries.single { it.value == name }.key)
        }
    }

    companion object {
        private const val AUTHORITY = "test.bookexport.documents"
    }
}
