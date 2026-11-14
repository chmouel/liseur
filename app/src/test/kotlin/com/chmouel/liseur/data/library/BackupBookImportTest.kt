package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BackupBookImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: LiseurDatabase
    private lateinit var library: LocalLibraryRepository

    @Before
    fun open() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "books").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val removal = BookRemoval(
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
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val opener = PublicationOpener(
            DefaultPublicationParser(
                context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null,
            ),
        )
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
        File(context.filesDir, "books").deleteRecursively()
    }

    @Test
    fun `a backed-up EPUB moves into app storage under its digest and is shelved`() = runTest {
        val staged = folder.newFile("staged.epub").also(::writeEpub)
        val sha = sha256(staged)

        val result = library.importBackupBook(staged, sha)

        assertTrue(result is ImportResult.Added)
        val added = (result as ImportResult.Added).book
        val expectedFile = File(File(context.filesDir, "books"), "$sha.epub")
        assertTrue(expectedFile.isFile)
        assertFalse(staged.exists())
        assertEquals(Uri.fromFile(expectedFile).toString(), added.url)
        assertEquals(DownloadState.DOWNLOADED, added.downloadState)
        assertEquals("A Test Book", added.title)
    }

    @Test
    fun `a backed-up EPUB already on the shelf by content is reported, not duplicated`() = runTest {
        val existingFile = folder.newFile("existing.epub").also(::writeEpub)
        val firstImport = library.importBook(Uri.fromFile(existingFile)) as ImportResult.Added
        db.bookDao().setHiddenAt(firstImport.book.url, 123L)

        val staged = folder.newFile("staged-duplicate.epub").also(::writeEpub)
        val sha = sha256(staged)

        val result = library.importBackupBook(staged, sha)

        assertTrue(result is ImportResult.AlreadyShelved)
        val shelved = (result as ImportResult.AlreadyShelved).book
        assertEquals(firstImport.book.url, shelved.url)
        assertEquals(1, db.bookDao().allOnce().size)
        val referenced = db.bookDao().allOnce().map { it.url }.toSet()
        val orphans = File(context.filesDir, "books").listFiles().orEmpty()
            .filterNot { Uri.fromFile(it).toString() in referenced }
        assertTrue(orphans.isEmpty())
        assertTrue(db.bookDao().getByUrl(firstImport.book.url)!!.hidden)
    }

    @Test
    fun `a book that will not open is dropped from app storage`() = runTest {
        val broken = folder.newFile("broken.epub").also { it.writeText("not an epub") }
        val sha = sha256(broken)

        val result = library.importBackupBook(broken, sha)

        assertEquals(ImportResult.Failed, result)
        val target = File(File(context.filesDir, "books"), "$sha.epub")
        assertFalse(target.exists())
        assertTrue(db.bookDao().allOnce().isEmpty())
    }

    private companion object {
        const val FIXED_ZIP_TIME = 1_577_836_800_000L
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun writeEpub(target: File) {
        // Fixed entry times keep two writes of this book byte-identical, so the
        // content fingerprint matches instead of changing with the clock.
        fun entry(name: String) = ZipEntry(name).apply { time = FIXED_ZIP_TIME }
        ZipOutputStream(target.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.setMethod(ZipOutputStream.STORED)
            zip.putNextEntry(
                entry("mimetype").apply {
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.setMethod(ZipOutputStream.DEFLATED)
            fun put(name: String, body: String) {
                zip.putNextEntry(entry(name))
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
