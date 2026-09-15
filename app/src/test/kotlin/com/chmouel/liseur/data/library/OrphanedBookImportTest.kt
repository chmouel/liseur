package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Picking the file of a book orphaned by folder removal asks for the
 * book back (issue #207). The row kept its URL, its reading and its
 * server identity; what it lost is a way to open the file. These tests
 * pin down what taking it back may and may not touch.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class OrphanedBookImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: LiseurDatabase
    private lateinit var library: LocalLibraryRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        library = LocalLibraryRepository(
            context = context,
            assetRetriever = assetRetriever,
            publicationOpener = PublicationOpener(
                publicationParser = DefaultPublicationParser(
                    context,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                ),
            ),
            bookDao = db.bookDao(),
            folderDao = db.libraryFolderDao(),
            bookRemoval = removal,
            fingerprints = BookFingerprintStore(context, db.workIdentityDao()),
        )
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `picking an orphan's file gives the row its file back`() = runTest {
        val epub = folder.newFile("orphan.epub").also { writeEpub(it) }
        val fileUrl = Uri.fromFile(epub).toString()
        db.bookDao().upsert(orphan(fileUrl))
        db.readingProgressDao().upsert(progress(fileUrl))

        val result = library.importBook(Uri.fromFile(epub))

        assertTrue(result is ImportResult.Added)
        val restored = db.bookDao().getByUrl(fileUrl)
        assertEquals(DownloadState.DOWNLOADED, restored?.downloadState)
        assertEquals(fileUrl, restored?.localUri)
        // Under its own URL and its own server identity: the reading
        // position and the upload both hang off them.
        assertEquals("remote", restored?.remoteUuid)
        assertNotNull(db.readingProgressDao().get(fileUrl))
        assertEquals(listOf(fileUrl), db.bookDao().allOnce().map { it.url })
    }

    @Test
    fun `a file that will not open restores nothing`() = runTest {
        val broken = folder.newFile("broken.epub").also { it.writeText("not a zip") }
        val fileUrl = Uri.fromFile(broken).toString()
        db.bookDao().upsert(orphan(fileUrl))

        val result = library.importBook(Uri.fromFile(broken))

        assertTrue(result is ImportResult.AlreadyShelved)
        val kept = db.bookDao().getByUrl(fileUrl)
        assertEquals(DownloadState.REMOTE, kept?.downloadState)
        assertNull(kept?.localUri)
    }

    @Test
    fun `a different book at the old path starts fresh`() = runTest {
        val epub = folder.newFile("replaced.epub").also { writeEpub(it) }
        val fileUrl = Uri.fromFile(epub).toString()
        db.bookDao().upsert(orphan(fileUrl).copy(workId = "the-old-work"))
        db.readingProgressDao().upsert(progress(fileUrl))

        val result = library.importBook(Uri.fromFile(epub))

        // The row keeps its shelf place, but nothing that described the
        // old contents: not the reading, and not the link to the
        // server's copy of the book that used to be here.
        assertTrue(result is ImportResult.Added)
        val fresh = db.bookDao().getByUrl(fileUrl)
        assertEquals(DownloadState.DOWNLOADED, fresh?.downloadState)
        assertEquals(fileUrl, fresh?.localUri)
        assertNull(fresh?.remoteUuid)
        assertNull(db.readingProgressDao().get(fileUrl))
    }

    private fun orphan(url: String) = Book(
        url = url,
        title = "Orphan",
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        remoteUuid = "remote",
        downloadState = DownloadState.REMOTE,
    )

    private fun progress(bookUrl: String) = ReadingProgress(
        bookUrl = bookUrl,
        locatorJson = """{"href":"one"}""",
        totalProgression = 0.5,
        updatedAt = 1,
    )

    /** The smallest EPUB the streamer will open. */
    private fun writeEpub(target: File) {
        ZipOutputStream(target.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.setMethod(ZipOutputStream.STORED)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.setMethod(ZipOutputStream.DEFLATED)
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:uuid:0f6a7e2c-1111-4222-8333-444455556666</dc:identifier>
                    <dc:title>A Test Book</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>""",
            )
            put(
                "OEBPS/chapter.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>One</title></head>
                  <body><p>Words.</p></body>
                </html>""",
            )
        }
    }
}
