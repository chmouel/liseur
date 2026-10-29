package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LibraryFolder
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.BookAccessibility
import com.chmouel.liseur.domain.Contributor
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** The `file` source of the details screen (ADR-0016): what is read, when, and when it goes. */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class FileMetadataIndexTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: LiseurDatabase
    private lateinit var removal: BookRemoval
    private lateinit var library: LocalLibraryRepository

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).allowMainThreadQueries().build()
        removal = BookRemoval(
            bookDao = db.bookDao(),
            sessionDao = db.readingSessionDao(),
            peerStateDao = db.syncPeerStateDao(),
            identityDao = db.workIdentityDao(),
            progressDao = db.readingProgressDao(),
            annotationDao = db.annotationDao(),
            annotationSyncDao = db.annotationSyncDao(),
            inTransaction = { work -> db.withTransaction { work() } },
            metadataDao = db.bookMetadataDao(),
        )
        val httpClient = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, httpClient)
        library = LocalLibraryRepository(
            context = context,
            assetRetriever = retriever,
            publicationOpener = PublicationOpener(
                DefaultPublicationParser(context, httpClient = httpClient, assetRetriever = retriever, pdfFactory = null),
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
    fun `an imported book keeps what its package declares and its measured size`() = runTest {
        val epub = folder.newFile("rich.epub").also { writeEpub(it, RICH_METADATA) }

        val result = library.importBook(Uri.fromFile(epub))

        val url = (result as ImportResult.Added).book.url
        assertTrue(db.bookDao().getByUrl(url)!!.fileMetadataChecked)
        val file = fileRow(url)!!
        assertEquals("The subtitle", file.subtitle)
        assertEquals("First paragraph.\n\nSecond & last.", file.description)
        assertEquals(listOf("Ace Books"), file.publishers)
        assertEquals("1969-03-01", file.published)
        assertEquals(listOf("Science fiction", "Anarchism"), file.subjects)
        assertEquals(
            listOf(Contributor("author", "Ursula K. Le Guin"), Contributor("translator", "Someone Else")),
            file.contributors,
        )
        assertEquals(listOf(Identifier("isbn", "9780441478125")), file.identifiers)
        assertEquals(epub.length(), file.fileSizeBytes)
        assertEquals(BookAccessibility(features = listOf("tableOfContents"), summary = "Readable."), file.accessibility)
    }

    @Test
    fun `the backfill reads old books once and retries files that would not open`() = runTest {
        val epub = folder.newFile("old.epub").also { it.writeText("not yet a book") }
        val url = Uri.fromFile(epub).toString()
        db.bookDao().upsert(book(url).copy(downloadState = DownloadState.DOWNLOADED, localUri = url))

        library.backfillMetadata(batchSize = 1)
        assertFalse(db.bookDao().getByUrl(url)!!.fileMetadataChecked)
        assertNull(fileRow(url))

        writeEpub(epub, RICH_METADATA)
        library.backfillMetadata(batchSize = 1)

        assertTrue(db.bookDao().getByUrl(url)!!.fileMetadataChecked)
        assertEquals("The subtitle", fileRow(url)?.subtitle)
    }

    @Test
    fun `removing a download or the folder behind it forgets the file's metadata`() = runTest {
        val downloaded = "calibre:1"
        val folderUrl = "content://tree/books"
        val viaFolder = "content://tree/books/document/a.epub"
        db.libraryFolderDao().upsert(LibraryFolder(url = folderUrl, addedAt = 0))
        db.bookDao().upsert(book(downloaded).copy(downloadState = DownloadState.DOWNLOADED, localUri = "file:///x.epub"))
        db.bookDao().upsert(book(viaFolder).copy(source = folderUrl, downloadState = DownloadState.DOWNLOADED))
        for (url in listOf(downloaded, viaFolder)) {
            removal.fileMetadataRead(url, BookMetadata.of(url, MetadataSource.FILE, MetadataSnapshot(subtitle = "S")), null)
            db.bookMetadataDao().upsert(BookMetadata.of(url, MetadataSource.CATALOG, MetadataSnapshot(subtitle = "C")))
        }

        removal.downloadRemoved(downloaded)
        removal.deleteFolder(db.libraryFolderDao(), folderUrl)

        for (url in listOf(downloaded, viaFolder)) {
            assertFalse(db.bookDao().getByUrl(url)!!.fileMetadataChecked)
            assertEquals(listOf("catalog"), db.bookMetadataDao().forBook(url).map { it.source })
        }
    }

    @Test
    fun `a file read that finishes after its download was removed stores nothing`() = runTest {
        val url = "calibre:1"
        db.bookDao().upsert(book(url))
        removal.downloadArrived(url, "file:///x.epub", downloadedAt = 1, fileModifiedAt = null)
        val read = BookMetadata.of(url, MetadataSource.FILE, MetadataSnapshot(subtitle = "S"))
        removal.downloadRemoved(url)

        removal.fileMetadataRead(url, read, downloadedAt = 1)
        assertFalse(db.bookDao().getByUrl(url)!!.fileMetadataChecked)
        assertTrue(db.bookMetadataDao().forBook(url).isEmpty())

        // Downloaded again at the same path before the old read landed:
        // that read describes the copy that was removed, not this one.
        removal.downloadArrived(url, "file:///x.epub", downloadedAt = 2, fileModifiedAt = null)
        removal.fileMetadataRead(url, read, downloadedAt = 1)
        assertFalse(db.bookDao().getByUrl(url)!!.fileMetadataChecked)
        assertTrue(db.bookMetadataDao().forBook(url).isEmpty())

        removal.fileMetadataRead(url, read, downloadedAt = 2)
        assertTrue(db.bookDao().getByUrl(url)!!.fileMetadataChecked)
    }

    @Test
    fun `a different book at the same path drops both sources of the old one`() = runTest {
        val url = "file:///shelf/a.epub"
        db.bookDao().upsert(book(url).copy(downloadState = DownloadState.DOWNLOADED))
        removal.fileMetadataRead(url, BookMetadata.of(url, MetadataSource.FILE, MetadataSnapshot(subtitle = "Old")), null)
        db.bookMetadataDao().upsert(BookMetadata.of(url, MetadataSource.CATALOG, MetadataSnapshot(subtitle = "Old")))

        removal.contentReplaced(url)

        assertTrue(db.bookMetadataDao().forBook(url).isEmpty())
        assertFalse(db.bookDao().getByUrl(url)!!.fileMetadataChecked)
    }

    private suspend fun fileRow(url: String): MetadataSnapshot? =
        db.bookMetadataDao().forBook(url).singleOrNull { it.source == MetadataSource.FILE.key }?.toSnapshot()

    private fun book(url: String) = Book(
        url = url,
        title = "Book",
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        remoteUuid = "remote",
        downloadState = DownloadState.REMOTE,
    )

    private val RICH_METADATA = """
        <dc:identifier id="id">urn:isbn:9780441478125</dc:identifier>
        <dc:title>A Test Book</dc:title>
        <meta property="title-type" refines="#sub">subtitle</meta>
        <dc:title id="sub">The subtitle</dc:title>
        <dc:creator>Ursula K. Le Guin</dc:creator>
        <dc:contributor id="trl">Someone Else</dc:contributor>
        <meta refines="#trl" property="role" scheme="marc:relators">trl</meta>
        <dc:publisher>Ace Books</dc:publisher>
        <dc:date>1969-03-01</dc:date>
        <dc:subject>Science fiction</dc:subject>
        <dc:subject>Anarchism</dc:subject>
        <dc:description>&lt;p&gt;First paragraph.&lt;/p&gt;&lt;img src="x.png"/&gt;&lt;p&gt;Second &amp;amp; last.&lt;/p&gt;</dc:description>
        <meta property="schema:accessibilityFeature">tableOfContents</meta>
        <meta property="schema:accessibilityFeature">madeUpFeature</meta>
        <meta property="schema:accessibilitySummary">Readable.</meta>
    """.trimIndent()

    private fun writeEpub(target: File, metadata: String) {
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
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en-US">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    $metadata
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
