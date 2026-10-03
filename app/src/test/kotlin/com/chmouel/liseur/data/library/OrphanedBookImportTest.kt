package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.liseursync.WorkResolution
import com.chmouel.liseur.data.liseursync.WorkResolver
import com.chmouel.liseur.data.remote.RemoteCredentials
import java.io.File
import java.net.InetAddress
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.resource.SingleResourceContainer
import org.readium.r2.shared.util.resource.StringResource
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
    private lateinit var retriever: AssetRetriever
    private lateinit var opener: PublicationOpener

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
        retriever = assetRetriever
        opener = PublicationOpener(DefaultPublicationParser(
            context, httpClient = httpClient, assetRetriever = assetRetriever, pdfFactory = null,
        ))
        library = LocalLibraryRepository(
            context = context,
            assetRetriever = assetRetriever,
            publicationOpener = opener,
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

    @Test
    fun `import displays original authors but retains Readium work identity`() = runTest {
        val epub = folder.newFile("authors.epub").also {
            writeEpub(it, authors = AUTHOR_METADATA, identifier = "none")
        }
        val result = library.importBook(Uri.fromFile(epub)) as ImportResult.Added
        assertEquals("Original author", result.book.author)
        assertEquals("a test book — الكاتب", result.book.workId)
        assertFallbackIsNotSent(result.book.url)
        val asset = retriever.retrieve(Uri.fromFile(epub).toAbsoluteUrl()!!).getOrNull()!!
        val publication = opener.open(asset, allowUserInteraction = false).getOrNull()!!
        try {
            assertEquals("الكاتب", publication.metadata.authors.single().name)
            assertEquals(listOf("Original author"), primaryEpubAuthors(publication))
            // Optional metadata failures must leave callers free to use Readium's names.
            val missing = Publication(publication.manifest)
            try {
                assertNull(primaryEpubAuthors(missing))
            } finally {
                missing.close()
            }
            val malformed = Publication(publication.manifest, SingleResourceContainer(
                Url("META-INF/container.xml")!!, StringResource("<container><broken></container>"),
            ))
            try {
                assertNull(primaryEpubAuthors(malformed))
            } finally {
                malformed.close()
            }
        } finally {
            publication.close()
        }
    }

    @Test
    fun `import uses original contributor names while retaining Readium author roles`() = runTest {
        val epub = folder.newFile("contributors.epub").also {
            writeEpub(it, authors = """
                <dc:creator>First author</dc:creator>
                <dc:contributor xmlns:opf="http://www.idpf.org/2007/opf" opf:role="aut">Legacy author</dc:contributor>
                <dc:contributor id="author">Modern author</dc:contributor>
                <meta property="role" refines="#author" scheme="marc:relators">aut</meta>
                <meta property="alternate-script" refines="#author">الكاتب</meta>
                <dc:contributor id="translator">Translator</dc:contributor>
                <meta property="role" refines="#translator">trl</meta>
            """.trimIndent())
        }
        val result = library.importBook(Uri.fromFile(epub)) as ImportResult.Added
        assertEquals("First author, Legacy author, Modern author", result.book.author)
    }

    @Test
    fun `repair and later reindex preserve a local book's identity and reading`() = runTest {
        val epub = folder.newFile("old-authors.epub").also {
            writeEpub(it, authors = AUTHOR_METADATA, identifier = "none")
        }
        val url = Uri.fromFile(epub).toString()
        val saved = orphan(url).copy(
            title = "A Test Book", author = "الكاتب", workId = "a test book — الكاتب",
            archivedAt = 4, hiddenAt = 5,
        )
        val id = db.bookDao().upsert(saved)
        val before = saved.copy(id = id)
        val place = progress(url)
        val annotation = BookAnnotation(
            id = "note", bookId = url, kind = "BOOK_NOTE", locatorJson = "", note = "Keep me",
            createdAt = 2, updatedAt = 3,
        )
        db.readingProgressDao().upsert(place)
        db.annotationDao().upsert(annotation)
        val sessionId = db.readingSessionDao().insert(ReadingSession(
            bookUrl = url, startedAt = 1, endedAt = 2, lastCheckpointAt = 2, durationMs = 1,
        ))
        val session = db.readingSessionDao().get(sessionId)
        val asset = retriever.retrieve(Uri.fromFile(epub).toAbsoluteUrl()!!).getOrNull()!!
        val publication = opener.open(asset, allowUserInteraction = false).getOrNull()!!
        try {
            library.refreshAuthor(url, primaryEpubAuthors(publication)!!, publication)
        } finally {
            publication.close()
        }
        assertEquals(before.copy(author = "Original author", identityAuthor = before.author), db.bookDao().getByUrl(url))
        assertFallbackIsNotSent(url)
        assertEquals(place, db.readingProgressDao().get(url))
        assertEquals(annotation, db.annotationDao().byId("note"))
        assertEquals(session, db.readingSessionDao().get(sessionId))

        // Restoring an orphan forces the same reindex path as a changed folder file.
        library.importBook(Uri.fromFile(epub))
        assertEquals("a test book — الكاتب", db.bookDao().getByUrl(url)?.workId)
        assertEquals("remote", db.bookDao().getByUrl(url)?.remoteUuid)
        assertFallbackIsNotSent(url)
        assertEquals(place, db.readingProgressDao().get(url))
        assertEquals(annotation, db.annotationDao().byId("note"))
        assertEquals(session, db.readingSessionDao().get(sessionId))
    }

    @Test
    fun `reindex keeps work identity when alternate script language changes`() = runTest {
        val epub = folder.newFile("alternate-script.epub").also {
            writeEpub(it, authors = AUTHOR_METADATA, identifier = "none")
        }
        val url = Uri.fromFile(epub).toString()
        val added = library.importBook(Uri.fromFile(epub)) as ImportResult.Added
        val workId = added.book.workId
        assertEquals("a test book — الكاتب", workId)
        val place = progress(url)
        db.readingProgressDao().upsert(place)

        writeEpub(
            epub,
            authors = """
                <dc:creator id="author">Original author</dc:creator>
                <meta property="alternate-script" refines="#author" xml:lang="ar">الكاتب</meta>
            """.trimIndent(),
            identifier = "none",
        )
        db.bookDao().setDownloadState(url, DownloadState.REMOTE, null)

        library.importBook(Uri.fromFile(epub))

        assertEquals(workId, db.bookDao().getByUrl(url)?.workId)
        assertEquals("الكاتب", db.bookDao().getByUrl(url)?.identityAuthor)
        assertEquals(place, db.readingProgressDao().get(url))

        // A further rewrite must still compare against the author that
        // produced the retained fallback work ID.
        db.bookDao().setDownloadState(url, DownloadState.REMOTE, null)
        library.importBook(Uri.fromFile(epub))

        assertEquals(workId, db.bookDao().getByUrl(url)?.workId)
        assertEquals("الكاتب", db.bookDao().getByUrl(url)?.identityAuthor)
        assertEquals(place, db.readingProgressDao().get(url))
    }

    @Test
    fun `opening a replaced book cannot update the old shelf author before reindex`() = runTest {
        val epub = folder.newFile("replaced-author.epub").also {
            writeEpub(it, authors = "<dc:creator id=\"author\">Old author</dc:creator>", identifier = "none")
        }
        val url = Uri.fromFile(epub).toString()
        val added = library.importBook(Uri.fromFile(epub)) as ImportResult.Added
        assertEquals("Old author", added.book.author)
        val place = progress(url)
        db.readingProgressDao().upsert(place)

        writeEpub(epub, authors = "<dc:creator id=\"author\">New author</dc:creator>", identifier = "none")
        val asset = retriever.retrieve(Uri.fromFile(epub).toAbsoluteUrl()!!).getOrNull()!!
        val publication = opener.open(asset, allowUserInteraction = false).getOrNull()!!
        try {
            library.refreshAuthor(url, primaryEpubAuthors(publication)!!, publication)
        } finally {
            publication.close()
        }
        assertEquals("Old author", db.bookDao().getByUrl(url)?.author)

        db.bookDao().setDownloadState(url, DownloadState.REMOTE, null)
        library.importBook(Uri.fromFile(epub))

        assertEquals("New author", db.bookDao().getByUrl(url)?.author)
        assertNull(db.readingProgressDao().get(url))
    }

    @Test
    fun `author backfill repairs old shelves and retries unavailable files`() = runTest {
        val epub = folder.newFile("legacy-authors.epub")
        val url = Uri.fromFile(epub).toString()
        val id = db.bookDao().upsert(
            orphan(url).copy(
                title = "A Test Book",
                author = "الكاتب",
                identityAuthor = null,
                workId = "a test book — الكاتب",
                downloadState = DownloadState.DOWNLOADED,
                localUri = url,
            ),
        )
        val remoteUrl = "calibre:remote"
        val remote = orphan(remoteUrl).copy(
            author = "Catalog author",
            downloadState = DownloadState.DOWNLOADED,
            localUri = url,
        )
        val remoteId = db.bookDao().upsert(remote)
        assertTrue(db.bookDao().needingAuthorCheck(10).none { it.url == remoteUrl })

        library.backfillAuthors(batchSize = 1)
        assertEquals("الكاتب", db.bookDao().getByUrl(url)?.author)
        assertNull(db.bookDao().getByUrl(url)?.identityAuthor)
        assertEquals(remote.copy(id = remoteId), db.bookDao().getByUrl(remoteUrl))

        writeEpub(epub, authors = AUTHOR_METADATA, identifier = "none")
        library.backfillAuthors(batchSize = 1)

        val repaired = db.bookDao().getByUrl(url)
        assertEquals(id, repaired?.id)
        assertEquals("Original author", repaired?.author)
        assertEquals("الكاتب", repaired?.identityAuthor)
        assertEquals("a test book — الكاتب", repaired?.workId)
    }

    @Test
    fun `author backfill preserves legacy title-only identity and reading`() = runTest {
        val epub = folder.newFile("title-only.epub").also {
            writeEpub(it, authors = AUTHOR_METADATA, identifier = "none")
        }
        val url = Uri.fromFile(epub).toString()
        db.bookDao().upsert(orphan(url).copy(
            title = "A Test Book", author = null, identityAuthor = null,
            workId = "a test book", downloadState = DownloadState.DOWNLOADED, localUri = url,
        ))
        val place = progress(url)
        db.readingProgressDao().upsert(place)
        val annotation = BookAnnotation(
            id = "title-only-note", bookId = url, kind = "BOOK_NOTE", locatorJson = "",
            note = "Keep me", createdAt = 2, updatedAt = 3,
        )
        db.annotationDao().upsert(annotation)

        library.backfillAuthors()

        assertEquals("Original author", db.bookDao().getByUrl(url)?.author)
        assertEquals("", db.bookDao().getByUrl(url)?.identityAuthor)
        assertEquals("a test book", db.bookDao().getByUrl(url)?.workId)
        assertEquals(place, db.readingProgressDao().get(url))
        assertEquals(annotation, db.annotationDao().byId(annotation.id))
    }

    @Test
    fun `opening a catalog book does not replace its server author`() = runTest {
        val book = orphan("calibre:remote").copy(author = "Catalog author")
        val id = db.bookDao().upsert(book)
        val epub = folder.newFile("catalog.epub").also {
            writeEpub(it, authors = "<dc:creator>EPUB author</dc:creator>")
        }
        val asset = retriever.retrieve(Uri.fromFile(epub).toAbsoluteUrl()!!).getOrNull()!!
        val publication = opener.open(asset, allowUserInteraction = false).getOrNull()!!
        try {
            library.refreshAuthor(book.url, listOf("EPUB author"), publication)
        } finally {
            publication.close()
        }
        assertEquals(book.copy(id = id), db.bookDao().getByUrl(book.url))
    }

    private suspend fun assertFallbackIsNotSent(url: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = WorkResolver(
            dao = db.workIdentityDao(),
            fingerprints = BookFingerprintStore(context, db.workIdentityDao()),
        )
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse.Builder().code(200).body(
                """{"work_id":"matched-work","confidence":"low","created":false}""",
            ).build())
            val baseUrl = "http://127.0.0.1:${server.port}"
            // Exercise file identifiers even when the restored row also has an upload link.
            val book = db.bookDao().getByUrl(url)!!.copy(remoteUuid = null)
            val result = resolver.resolve(
                book, "liseursync|$baseUrl|test", baseUrl,
                RemoteCredentials.Bearer("test-token"),
            )
            assertTrue(result is WorkResolution.NeedsConfirming)
            val body = JSONObject(server.takeRequest().body!!.utf8())
            val identifiers = body.getJSONArray("identifiers")
            val kinds = (0 until identifiers.length()).map {
                identifiers.getJSONObject(it).getString("kind")
            }
            assertFalse("dc" in kinds)
            val titleAuthor = (0 until identifiers.length()).map { identifiers.getJSONObject(it) }
                .single { it.getString("kind") == "ta" }
            assertEquals("a test book|original author", titleAuthor.getString("value"))
        }
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

    private val AUTHOR_METADATA = """
        <dc:creator id="author">Original author</dc:creator>
        <meta property="alternate-script" refines="#author">الكاتب</meta>
    """.trimIndent()

    /** The smallest EPUB the streamer will open. */
    private fun writeEpub(
        target: File,
        authors: String = "",
        identifier: String = "urn:uuid:0f6a7e2c-1111-4222-8333-444455556666",
    ) {
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
                    <dc:identifier id="id">$identifier</dc:identifier>
                    $authors
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
