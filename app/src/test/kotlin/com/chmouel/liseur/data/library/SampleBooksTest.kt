package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import java.io.File
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SampleBooksTest {

    private lateinit var context: Context
    private lateinit var db: LiseurDatabase
    private lateinit var fingerprints: BookFingerprintStore
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
        fingerprints = BookFingerprintStore(context, db.workIdentityDao())
        library = LocalLibraryRepository(
            context = context,
            assetRetriever = assetRetriever,
            publicationOpener = opener,
            bookDao = db.bookDao(),
            folderDao = db.libraryFolderDao(),
            bookRemoval = removal,
            fingerprints = fingerprints,
        )
    }

    @After
    fun close() {
        db.close()
        File(context.filesDir, "books").deleteRecursively()
    }

    @Test
    fun `supported locales resolve to their bundled sample code and unsupported fall back to English`() {
        assertEquals(
            SampleBookSpec("fr", "samples/fr.epub", "sample-fr.epub"),
            SampleBooks.specFor(Locale.forLanguageTag("fr-CA")),
        )
        assertEquals(
            SampleBookSpec("pt", "samples/pt.epub", "sample-pt.epub"),
            SampleBooks.specFor(Locale.forLanguageTag("pt-BR")),
        )
        assertEquals(
            SampleBookSpec("zh", "samples/zh.epub", "sample-zh.epub"),
            SampleBooks.specFor(Locale.forLanguageTag("zh-Hans-CN")),
        )
        assertEquals(
            SampleBookSpec("en", "samples/en.epub", "sample-en.epub"),
            SampleBooks.specFor(Locale.forLanguageTag("ja-JP")),
        )
    }

    @Test
    fun `every supported sample code exists as a non-empty bundled asset`() {
        for (code in SampleBooks.SUPPORTED_CODES) {
            val spec = SampleBooks.specFor(Locale.forLanguageTag(code))
            val size = context.assets.open(spec.assetPath).use { it.readBytes().size }
            assertTrue("Expected non-empty asset for $code", size > 0)
        }
    }

    @Test
    fun `importing a sample book copies it into app storage and unhides it if previously hidden`() = runTest {
        val first = library.importSampleBook(Locale.FRENCH)
        assertTrue(first is ImportResult.Added)
        val added = (first as ImportResult.Added).book
        val expectedFile = File(File(context.filesDir, "books"), "sample-fr.epub")
        assertTrue(expectedFile.isFile && expectedFile.length() > 0L)
        assertEquals(Uri.fromFile(expectedFile).toString(), added.url)
        assertEquals(DownloadState.DOWNLOADED, added.downloadState)

        val second = library.importSampleBook(Locale.FRENCH)
        assertTrue(second is ImportResult.AlreadyShelved)
        assertEquals(added.url, (second as ImportResult.AlreadyShelved).book.url)

        library.removeFromLibrary(added)
        assertTrue(db.bookDao().getByUrl(added.url)!!.hidden)

        val unhidden = library.importSampleBook(Locale.FRENCH)
        assertTrue(unhidden is ImportResult.Added)
        assertFalse(db.bookDao().getByUrl(added.url)!!.hidden)
    }

    @Test
    fun `every bundled sample EPUB parses and imports cleanly`() = runTest {
        for (code in SampleBooks.SUPPORTED_CODES) {
            val result = library.importSampleBook(Locale.forLanguageTag(code))
            assertTrue("Expected Added for $code, got $result", result is ImportResult.Added)
            val book = (result as ImportResult.Added).book
            assertTrue("Expected non-blank title for $code", book.title.isNotBlank())
            assertTrue("Expected openable URI for $code", !book.openableUri().isNullOrBlank())
        }
    }

    @Test
    fun `importing a sample book recopies when the target file was deleted`() = runTest {
        val first = library.importSampleBook(Locale.GERMAN)
        assertTrue(first is ImportResult.Added)
        val expectedFile = File(File(context.filesDir, "books"), "sample-de.epub")
        assertTrue(expectedFile.delete())

        val recovered = library.importSampleBook(Locale.GERMAN)
        assertTrue(recovered is ImportResult.AlreadyShelved || recovered is ImportResult.Added)
        assertTrue(expectedFile.isFile && expectedFile.length() > 0L)
    }

    @Test
    fun `importing a sample book adopts bundled copy when deduplicated original file is missing`() = runTest {
        val externalDir = File(context.cacheDir, "external-sample-test").apply { mkdirs() }
        val externalCopy = File(externalDir, "other-copy.epub")
        context.assets.open("samples/en.epub").use { input ->
            externalCopy.outputStream().use { output -> input.copyTo(output) }
        }
        val externalImport = library.importBook(Uri.fromFile(externalCopy))
        assertTrue(externalImport is ImportResult.Added)
        val externalBook = (externalImport as ImportResult.Added).book
        // Cache the fingerprint before deleting the external file so content
        // deduplication matches the existing row.
        assertTrue(fingerprints.of(externalBook) != null)

        library.removeFromLibrary(externalBook)
        assertTrue(externalCopy.delete())

        val recovered = library.importSampleBook(Locale.ENGLISH)
        assertTrue("Expected Added after adopting bundled copy, got $recovered", recovered is ImportResult.Added)
        val book = (recovered as ImportResult.Added).book
        assertEquals(externalBook.url, book.url)
        assertFalse(book.hidden)
        val expectedFile = File(File(context.filesDir, "books"), "sample-en.epub")
        assertTrue(expectedFile.isFile && expectedFile.length() > 0L)
        assertEquals(Uri.fromFile(expectedFile).toString(), book.openableUri())
        externalDir.deleteRecursively()
    }
}
