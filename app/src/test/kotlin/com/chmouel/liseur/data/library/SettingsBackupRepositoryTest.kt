package com.chmouel.liseur.data.library

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import androidx.room.withTransaction
import com.chmouel.liseur.companion.ChapterRecap
import com.chmouel.liseur.companion.ChapterRecapRow
import com.chmouel.liseur.companion.CompanionDatabase
import com.chmouel.liseur.companion.CompanionStore
import com.chmouel.liseur.companion.RecapIdentity
import com.chmouel.liseur.companion.RecapPartRow
import com.chmouel.liseur.companion.RecapQueueRow
import com.chmouel.liseur.companion.Recaps
import com.chmouel.liseur.companion.StorySummaries
import com.chmouel.liseur.companion.StorySummaryRow
import com.chmouel.liseur.companion.SummaryDep
import com.chmouel.liseur.domain.CompanionBackupContents
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.domain.BackedUpBook
import com.chmouel.liseur.domain.encodeAnnotationBackup
import com.chmouel.liseur.data.remote.AccountBackup
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.VoicePreference
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.PageTurnStyle
import com.chmouel.liseur.data.settings.ThemeMode
import com.chmouel.liseur.data.settings.TapZones
import com.chmouel.liseur.data.settings.VolumeKeyMode
import com.chmouel.liseur.data.settings.UserFontRepository
import com.chmouel.liseur.data.settings.ImportResult as FontImportResult
import com.chmouel.liseur.data.settings.fonts.SfntFixtures
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SettingsBackupRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LiseurDatabase
    private lateinit var companionDb: CompanionDatabase
    private lateinit var companionStore: CompanionStore
    private lateinit var localLibrary: LocalLibraryRepository
    private val requested = mutableListOf<String>()

    @Before
    fun open() {
        File(context.filesDir, "books").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        companionDb = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java).build()
        companionStore = CompanionStore(companionDb, present = { db.bookDao().presentUrls(it) })
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
        localLibrary = LocalLibraryRepository(
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
        companionDb.close()
        File(context.filesDir, "books").deleteRecursively()
    }

    @Test
    fun `translation payload is optional in format six and forbidden in older archives`() = runTest {
        val repository = repository(
            AppSettingsRepository(store("translations-app.preferences_pb")),
            ReaderPreferencesRepository(store("translations-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val settings = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val base = mapOf(
            "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
            "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
            "companion.json" to """{"format":1,"application":"liseur","recaps":[]}""".toByteArray(),
        )
        val empty = """{"format":1,"application":"liseur","books":[]}""".toByteArray()
        for (version in listOf(4, 5, 6)) {
            val file = archive("translations-$version.zip", settings,
                base + ("translations.json" to empty), format = version)
            val result = repository.inspect(Uri.fromFile(file))
            if (version == 6) assertTrue(result is SettingsBackupInspection.Ready)
            else assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), result)
        }
        val noTranslations = archive("optional-translations.zip", settings, base, format = 6)
        assertTrue(repository.inspect(Uri.fromFile(noTranslations)) is SettingsBackupInspection.Ready)
        val malformed = archive("bad-translations.zip", settings,
            base + ("translations.json" to """{"format":1,"application":"liseur","books":[{}]}""".toByteArray()),
            format = 6)
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
            repository.inspect(Uri.fromFile(malformed)))
    }

    @Test
    fun `ZIP round trip restores companion recaps across pages and changed URLs without replacing existing recaps`() = runTest {
        val app = AppSettingsRepository(store("companion-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("companion-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val source = "file:///source.epub"
        val destination = "file:///destination.epub"
        db.bookDao().upsert(book(source))
        val sha = "a".repeat(64)
        val rows = (0..54).map { unit ->
            ChapterRecapRow(source, "unit-$unit", unit, sha, "server", "model", Recaps.FORMAT,
                ChapterRecap(listOf("Chapter $unit summary"), emptyList()).encode(), 100, 200)
        }
        rows.forEach { companionDb.recaps().putRecap(it) }
        companionDb.recaps().putPart(RecapPartRow(source, "unfinished", 0, 2, sha, "server", "model", Recaps.FORMAT, "{}", 100))
        companionDb.recaps().enqueue(RecapQueueRow(source, "queued", 55, sha, 100))
        val deps = StorySummaries.encodeDeps(rows.take(3).map { SummaryDep(it.unit, it.unitKey, true) })
        val summary = StorySummaryRow(source, "c".repeat(64), StorySummaries.TOP, 0, 2, sha, Recaps.FORMAT,
            StorySummaries.FORMAT, deps, "So far, three chapters.", 100, 200)
        companionDb.recaps().putSummary(summary)
        companionDb.recaps().putSummary(summary.copy(key = "d".repeat(64), summaryFormat = StorySummaries.FORMAT + 1))
        val uri = Uri.fromFile(File(folder.root, "companion.zip"))
        assertEquals(SettingsBackupExportResult.Exported(0), repository.exportTo(uri))
        ZipFile(File(uri.path!!)).use { zip ->
            val contents = zip.getInputStream(zip.getEntry("companion.json")).bufferedReader().readText()
            assertEquals(55, JSONObject(contents).getJSONArray("recaps").length())
            assertEquals(1, JSONObject(contents).getJSONArray("summaries").length())
            assertFalse(contents.contains("unfinished"))
            assertFalse(contents.contains("queued"))
        }
        companionDb.recaps().forgetRecaps(listOf(source))
        companionDb.recaps().forgetParts(listOf(source))
        companionDb.recaps().forgetQueued(listOf(source))
        companionDb.recaps().forgetSummaries(listOf(source))
        db.bookDao().deleteByUrls(listOf(source))
        db.bookDao().upsert(book(destination))
        val existing = rows.first().copy(bookUrl = destination, json = ChapterRecap.EMPTY.encode(), savedAt = 300)
        companionDb.recaps().putRecap(existing)
        val preview = repository.inspect(uri) as SettingsBackupInspection.Ready
        assertEquals(55, preview.preview.companionRecapCount)
        val restored = repository.restore(preview.archiveId) as SettingsBackupRestoreResult.Restored
        assertEquals(54, restored.companionRecapsAdded)
        assertEquals(existing, companionDb.recaps().recap(destination, existing.unitKey))
        assertEquals(rows.drop(1).map { it.copy(bookUrl = destination) }.toSet(),
            companionDb.recaps().recaps(destination).filterNot { it.unitKey == existing.unitKey }.toSet())
        assertEquals(summary.copy(bookUrl = destination), companionDb.recaps().summary(destination, summary.key))
        assertEquals(0, (restore(repository, uri) as SettingsBackupRestoreResult.Restored).companionRecapsAdded)
        val identity = RecapIdentity(sha, "another-server", "another-model", Recaps.FORMAT)
        assertEquals(55, companionStore.forBook(destination, sha).recaps(identity).size)
        val differentSha = "b".repeat(64)
        assertTrue(companionStore.forBook(destination, differentSha).recaps(identity.copy(contentSha = differentSha)).isEmpty())
    }

    @Test
    fun `malformed companion data is rejected before changing settings`() = runTest {
        val app = AppSettingsRepository(store("bad-companion-app.preferences_pb"))
        app.setThemeMode(ThemeMode.DARK)
        val reader = ReaderPreferencesRepository(store("bad-companion-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val settings = JSONObject().put("app", JSONObject().put("theme_mode", "light")).put("reader", JSONObject())
        val file = archive("bad-companion.zip", settings, mapOf(
            "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
            "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
            "companion.json" to """{"format":1,"application":"liseur","recaps":[{"book_id":"book"}]}""".toByteArray(),
        ), format = 4)
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repository.inspect(Uri.fromFile(file)))
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
    }

    @Test
    fun `companion restore skips missing ambiguous and colliding book matches`() = runTest {
        val sha = "a".repeat(64)
        fun recap(book: String, title: String) = com.chmouel.liseur.domain.BackedUpCompanionRecap(
            ChapterRecapRow(book, "unit", 0, sha, "server", "model", Recaps.FORMAT, ChapterRecap.EMPTY.encode(), 100, 200),
            title, "An author",
        )
        db.bookDao().upsert(book("destination").copy(title = "Matching"))
        db.bookDao().upsert(book("duplicate-1").copy(title = "Ambiguous"))
        db.bookDao().upsert(book("duplicate-2").copy(title = "Ambiguous"))
        val backup = companionBackup()
        assertEquals(0, backup.restore(CompanionBackupContents(listOf(
            recap("missing", "Missing"), recap("ambiguous", "Ambiguous"),
            recap("source-1", "Matching"), recap("source-2", "Matching"),
        ))))
        assertEquals(0, companionDb.recaps().recapCount())
        assertEquals(0, backup.restore(CompanionBackupContents(listOf(recap("destination", "Matching").let {
            it.copy(recap = it.recap.copy(format = Recaps.FORMAT + 1))
        }))))
    }

    @Test
    fun `companion export keeps the most recently used recaps within its budget and still exports everything else`() = runTest {
        val app = AppSettingsRepository(store("budget-app.preferences_pb"))
        app.setThemeMode(ThemeMode.DARK)
        val reader = ReaderPreferencesRepository(store("budget-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        db.bookDao().upsert(book("book"))
        db.readingProgressDao().upsert(ReadingProgress("book", "{}", 0.4, updatedAt = 12))
        db.annotationDao().upsert(BookAnnotation("note", "book", AnnotationKind.BOOK_NOTE.name, "", note = "Kept", createdAt = 1))
        // 30 recaps of about 600 kB each pass the 16 MiB companion budget.
        val budget = 16L * 1024 * 1024
        val summary = "résumé ".repeat(85_000)
        (0 until 30).forEach { unit ->
            companionDb.recaps().putRecap(ChapterRecapRow("book", "unit-$unit", unit, "a".repeat(64), "server", "model",
                Recaps.FORMAT, ChapterRecap(listOf(summary), emptyList()).encode(), 100, usedAt = unit.toLong()))
        }
        companionDb.recaps().putRecap(ChapterRecapRow("book", "old-format", 99, "a".repeat(64), "server", "model",
            Recaps.FORMAT + 1, ChapterRecap.EMPTY.encode(), 100, usedAt = 1_000))
        // Recaps fill the budget; the summary still has room.
        companionDb.recaps().putSummary(StorySummaryRow("book", "c".repeat(64), StorySummaries.TOP, 0, 0, "a".repeat(64),
            Recaps.FORMAT, StorySummaries.FORMAT, StorySummaries.encodeDeps(listOf(SummaryDep(0, "unit-0", true))),
            "So far.", 100, 0))
        val uri = Uri.fromFile(File(folder.root, "budget.zip"))
        assertEquals(SettingsBackupExportResult.Exported(0, 1), repository.exportTo(uri))
        ZipFile(File(uri.path!!)).use { zip ->
            val entry = zip.getEntry("companion.json")
            assertTrue(entry.size <= budget)
            val recaps = JSONObject(zip.getInputStream(entry).bufferedReader().readText()).getJSONArray("recaps")
            assertTrue(recaps.length() in 1 until 30)
            assertEquals((29 downTo 30 - recaps.length()).toList(), List(recaps.length()) { recaps.getJSONObject(it).getInt("unit") })
            assertEquals(1, JSONObject(zip.getInputStream(entry).bufferedReader().readText()).getJSONArray("summaries").length())
            fun text(path: String) = zip.getInputStream(zip.getEntry(path)).bufferedReader().readText()
            assertEquals(1, JSONObject(text("positions.json")).getJSONArray("positions").length())
            assertTrue(text("annotations.json").contains("Kept"))
            assertTrue(text("settings.json").contains("dark"))
        }

        val tiny = folder.newFile("bounded-companion.json")
        companionBackup().writeContents(tiny, 100)
        assertEquals(0, JSONObject(tiny.readText()).getJSONArray("recaps").length())
        assertEquals(0, JSONObject(tiny.readText()).getJSONArray("summaries").length())
    }

    @Test
    fun `companion export without a companion store writes no recaps and opens nothing`() = runTest {
        val backup = CompanionBackupRepository({ error("store opened") }, db.bookDao(), storeExists = { false })
        val file = folder.newFile("no-companion.json")
        backup.writeContents(file, 100)
        assertEquals(0, JSONObject(file.readText()).getJSONArray("recaps").length())
    }

    @Test
    fun `companion inspection rejects oversized payloads`() = runTest {
        val app = AppSettingsRepository(store("large-companion-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("large-companion-reader.preferences_pb"))
        val settings = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val file = archive("large-companion.zip", settings, mapOf(
            "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
            "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
            "companion.json" to ByteArray(16 * 1024 * 1024 + 1) { ' '.code.toByte() },
        ), format = 4)
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.TOO_LARGE),
            repository(app, reader, UserFontRepository(context, this, loadCheck = { true })).inspect(Uri.fromFile(file)))
    }

    @Test
    fun `ZIP restore replaces a newer reading position without copying sync bookkeeping`() = runTest {
        val app = AppSettingsRepository(store("position-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("position-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val url = "file:///source/book.epub"
        val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4,"totalProgression":0.4}}"""
        val saved = ReadingProgress(url, locator, 0.4, updatedAt = 100, readAt = 80,
            localRevision = 20, ackedRevision = 19, agreedAccount = "source-account")
        db.readingProgressDao().upsert(saved)
        val archive = Uri.fromFile(File(folder.root, "position.zip"))
        assertEquals(SettingsBackupExportResult.Exported(0), repository.exportTo(archive))
        val current = saved.copy(locatorJson = locator.replace("0.4", "0.8"), totalProgression = 0.8,
            updatedAt = 1000, readAt = 1000, localRevision = 4, ackedRevision = 3,
            positionRevision = 2, agreedAccount = "destination-account", agreedProgression = 0.8)
        db.readingProgressDao().upsert(current)
        val preview = repository.inspect(archive) as SettingsBackupInspection.Ready
        assertEquals(1, preview.preview.positionCount)
        val restored = repository.restore(preview.archiveId) as SettingsBackupRestoreResult.Restored
        assertEquals(1, restored.positionsRestored)
        val actual = db.readingProgressDao().get(url)!!
        assertEquals(locator, actual.locatorJson)
        assertEquals(0.4, actual.totalProgression!!, 0.0)
        assertEquals(80L, actual.readAt)
        assertTrue(actual.updatedAt > current.updatedAt)
        assertEquals(5L, actual.localRevision)
        assertEquals(3L, actual.positionRevision)
        assertEquals(3L, actual.ackedRevision)
        assertEquals("destination-account", actual.agreedAccount)
        assertEquals(0.8, actual.agreedProgression!!, 0.0)
        assertTrue(url in requested)
    }

    @Test
    fun `position backup matches a book imported at another URL`() = runTest {
        val app = AppSettingsRepository(store("mapped-position-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("mapped-position-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val original = "file:///old/book.epub"
        val destination = "file:///new/book.epub"
        fun book(url: String) = Book(url = url, title = "Same book", author = "Author", coverPath = null,
            source = null, addedAt = 0, lastOpenedAt = null)
        db.bookDao().upsert(book(original))
        val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml"}"""
        db.readingProgressDao().upsert(ReadingProgress(original, locator, 0.3, updatedAt = 100))
        val archive = Uri.fromFile(File(folder.root, "mapped-position.zip"))
        repository.exportTo(archive)
        db.bookDao().deleteByUrls(listOf(original))
        db.readingProgressDao().forget(original)
        db.bookDao().upsert(book(destination))
        val restored = restore(repository, archive) as SettingsBackupRestoreResult.Restored
        assertEquals(1, restored.positionsRestored)
        assertEquals(locator, db.readingProgressDao().get(destination)?.locatorJson)
        assertEquals(100L, db.readingProgressDao().get(destination)?.readAt)
        assertTrue(destination in requested)
    }

    @Test
    fun `older ZIP versions reject payloads introduced in newer versions`() = runTest {
        val app = AppSettingsRepository(store("version-payload-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("version-payload-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val payload = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val annotations = encodeAnnotationBackup(emptyList()).toByteArray()
        val positions = """{"format":1,"application":"liseur","positions":[]}""".toByteArray()
        val cases = listOf(
            1 to mapOf("annotations.json" to annotations),
            1 to mapOf("positions.json" to positions),
            2 to mapOf("annotations.json" to annotations, "positions.json" to positions),
            3 to mapOf("annotations.json" to annotations, "positions.json" to positions,
                "companion.json" to """{"format":1,"application":"liseur","recaps":[]}""".toByteArray()),
        )
        cases.forEachIndexed { index, (version, entries) ->
            val file = archive("old-payload-$index.zip", payload, entries, format = version)
            assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repository.inspect(Uri.fromFile(file)))
        }
    }

    @Test
    fun `combined reading payload size is rejected before JSON decoding`() = runTest {
        val app = AppSettingsRepository(store("combined-limit-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("combined-limit-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val payload = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val entry = ByteArray(3 * 1024 * 1024) { ' '.code.toByte() }
        val file = archive("combined-limit.zip", payload, mapOf(
            "annotations.json" to entry, "positions.json" to entry,
        ), format = 3)
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.TOO_LARGE), repository.inspect(Uri.fromFile(file)))
    }

    @Test
    fun `malformed position rejects the backup before changing settings`() = runTest {
        val app = AppSettingsRepository(store("bad-position-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("bad-position-reader.preferences_pb"))
        app.setThemeMode(ThemeMode.DARK)
        val payload = JSONObject().put("app", JSONObject().put("theme_mode", "light")).put("reader", JSONObject())
        val positions = """{"format":1,"application":"liseur","positions":[{"book_id":"book","locator":"broken","read_at":10}]}"""
        val annotations = encodeAnnotationBackup(emptyList())
        val archive = archive("bad-position.zip", payload, mapOf(
            "positions.json" to positions.toByteArray(), "annotations.json" to annotations.toByteArray(),
        ), format = 3)
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repository.inspect(Uri.fromFile(archive)))
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
    }

    @Test
    fun `export prepares a complete archive before opening the destination`() = runTest {
        val app = AppSettingsRepository(store("prepared-export-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("prepared-export-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val target = File(folder.root, "prepared.zip")

        val preparation = repository.prepareExport()
        assertTrue(preparation is SettingsBackupPreparation.Prepared)
        val prepared = (preparation as SettingsBackupPreparation.Prepared).export
        assertFalse(target.exists())
        ZipFile(prepared.file).use { zip -> assertTrue(zip.getEntry("manifest.json") != null) }

        assertEquals(
            SettingsBackupExportResult.Exported(0),
            repository.writeExport(prepared, Uri.fromFile(target)),
        )
        assertTrue(target.isFile)
        assertFalse(prepared.file.exists())
    }

    @Test
    fun `a prepared archive keeps its counts until the destination is written`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val fontBytes = SfntFixtures.sfnt(names = listOf(SfntFixtures.name(1, "Prepared Export Font")))
        assertTrue(fonts.import(Uri.fromFile(writeFont(fontBytes)), "prepared-export.ttf") is FontImportResult.Imported)
        db.bookDao().upsert(book("counted"))
        db.annotationDao().upsert(BookAnnotation("counted-note", "counted", AnnotationKind.BOOK_NOTE.name, "", note = "Kept", createdAt = 1))
        val repository = SettingsBackupRepository(
            context,
            AppSettingsRepository(store("counted-app.preferences_pb")),
            ReaderPreferencesRepository(store("counted-reader.preferences_pb")),
            fonts,
            AnnotationBackupRepository(context, db.annotationDao(), db.bookDao(), requested::add),
            ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requested::add),
            companionBackup(),
            exportConnections = { liseurSyncConnections() },
            prepareConnectionRestore = { _, _ -> suspend {} },
        )
        val target = File(folder.root, "counted.zip")

        val prepared = (repository.prepareExport("backup password".toCharArray()) as SettingsBackupPreparation.Prepared).export
        assertEquals(1, prepared.fonts)
        assertEquals(1, prepared.annotations)
        assertTrue(prepared.liseurSyncNeedsPairing)
        assertEquals(
            SettingsBackupExportResult.Exported(1, 1, liseurSyncNeedsPairing = true),
            repository.writeExport(prepared, Uri.fromFile(target)),
        )
        assertTrue(target.isFile)
        assertFalse(prepared.file.exists())
    }

    @Test
    fun `only stale prepared archives are swept from the cache`() = runTest {
        val repository = repository(
            AppSettingsRepository(store("sweep-app.preferences_pb")),
            ReaderPreferencesRepository(store("sweep-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val stale = System.currentTimeMillis() - 25 * 60 * 60 * 1000
        // The archive a picker never answered, aged past the cutoff.
        val staleArchive = (repository.prepareExport() as SettingsBackupPreparation.Prepared).export.file
        assertTrue(staleArchive.setLastModified(stale))
        val staleStaging = listOf(
            "annotations-export-987654321.json",
            "positions-export-987654322.json",
            "companion-export-987654323.json",
            "translations-export-987654324.json",
        ).map { name -> File(context.cacheDir, name).apply { writeText("old"); setLastModified(stale) } }
        val awaited = File(context.cacheDir, "settings-export-1234567891.zip").apply { writeText("new") }
        val unrelated = File(context.cacheDir, "liseur-backup-2020-01-01.zip")
            .apply { writeText("keep"); setLastModified(stale) }

        repository.discardProcessInspections()

        assertFalse(staleArchive.exists())
        staleStaging.forEach { assertFalse(it.name, it.exists()) }
        assertTrue(awaited.exists())
        assertTrue(unrelated.exists())

        // A prepare sweeps as well, so the next picker cannot trip over an abandoned archive.
        val planted = File(context.cacheDir, "settings-export-1234567899.zip")
            .apply { writeText("old"); setLastModified(stale) }
        repository.discardPreparedExport((repository.prepareExport() as SettingsBackupPreparation.Prepared).export)
        assertFalse(planted.exists())
    }

    @Test
    fun `a cache that cannot hold the archive reports a storage failure`() = runTest {
        // A cache directory that does not exist fails the staged writes before any picker.
        val unusable = object : ContextWrapper(context) {
            override fun getCacheDir() = File(folder.root, "missing-cache")
        }
        val repository = SettingsBackupRepository(
            unusable,
            AppSettingsRepository(store("storage-app.preferences_pb")),
            ReaderPreferencesRepository(store("storage-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
            AnnotationBackupRepository(context, db.annotationDao(), db.bookDao(), requested::add),
            ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requested::add),
            companionBackup(),
        )

        assertEquals(SettingsBackupPreparation.Failed(SettingsBackupFailure.STORAGE), repository.prepareExport())
    }

    @Test
    fun `a failed copy removes the prepared archive as well as the incomplete document`() = runTest {
        val docs = BookExportRepositoryTest.FakeDocs
        docs.directory = folder.newFolder("failed-prepared-copy")
        docs.names.clear()
        docs.deleted.clear()
        docs.failWrites.clear()
        docs.names["backup"] = "backup.zip"
        docs.failWrites += "backup.zip"
        File(docs.directory, "backup").writeText("partial")
        org.robolectric.Robolectric.buildContentProvider(BookExportRepositoryTest.FakeDocs::class.java)
            .create("test.bookexport.documents")
        val target = DocumentsContract.buildDocumentUri("test.bookexport.documents", "backup")
        val app = AppSettingsRepository(store("failed-prepared-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("failed-prepared-reader.preferences_pb"))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val prepared = (repository.prepareExport() as SettingsBackupPreparation.Prepared).export

        assertEquals(
            SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS),
            repository.writeExport(prepared, target),
        )
        assertFalse(prepared.file.exists())
        assertEquals(listOf("backup"), docs.deleted)
        assertFalse(File(docs.directory, "backup").exists())
    }

    @Test
    fun `failed ZIP writes delete the newly created document`() = runTest {
        val docs = BookExportRepositoryTest.FakeDocs
        docs.directory = folder.newFolder("failed-zip")
        docs.names.clear()
        docs.deleted.clear()
        docs.failWrites.clear()
        docs.names["backup"] = "backup.zip"
        docs.failWrites += "backup.zip"
        File(docs.directory, "backup").writeText("partial")
        org.robolectric.Robolectric.buildContentProvider(BookExportRepositoryTest.FakeDocs::class.java)
            .create("test.bookexport.documents")
        val target = DocumentsContract.buildDocumentUri("test.bookexport.documents", "backup")
        val app = AppSettingsRepository(store("failed-export-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("failed-export-reader.preferences_pb"))
        assertEquals(SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS),
            repository(app, reader, UserFontRepository(context, this, loadCheck = { true })).exportTo(target))
        assertEquals(listOf("backup"), docs.deleted)
        assertFalse(File(docs.directory, "backup").exists())
    }

    @Test
    fun `an exported archive restores app and reader settings`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val sourceStore = store("source-app.preferences_pb")
        sourceStore.edit { it[stringPreferencesKey("account_token")] = "private" }
        val sourceApp = AppSettingsRepository(sourceStore)
        val sourceReader = ReaderPreferencesRepository(store("source-reader.preferences_pb"))
        sourceApp.setThemeMode(ThemeMode.DARK)
        val arrangement = com.chmouel.liseur.reader.annotations.SelectionActionLayout.default
            .moveTo(com.chmouel.liseur.reader.annotations.SelectionAction.COPY.id, 0, toHidden = false)
            .withStyle(com.chmouel.liseur.reader.annotations.SelectionAction.COPY.id, com.chmouel.liseur.reader.annotations.SelectionActionStyle.LABEL)
            .withLabel(com.chmouel.liseur.reader.annotations.SelectionAction.COPY.id, "Copier : 中文, 100%")
            .withIcon(com.chmouel.liseur.reader.annotations.SelectionAction.COPY.id, com.chmouel.liseur.reader.annotations.SelectionActionIcon.STAR)
            .withWebsite("custom_6ed9c95b-9a2f-4f26-a573-d7356b0cd68e", "Référence : 中文, 100%", "https://example.org/?q={text}&source=book", null)
        sourceApp.setSelectionActionLayout(arrangement)
        sourceApp.setTapSideWidth(15)
        sourceReader.setFontSize(1.75)
        sourceReader.setHyphens(true)

        val backup = File(folder.root, "settings.zip")
        val uri = Uri.fromFile(backup)
        val source = repository(sourceApp, sourceReader, fonts)

        assertEquals(SettingsBackupExportResult.Exported(0), source.exportTo(uri))
        val inspected = source.inspect(uri) as SettingsBackupInspection.Ready
        assertEquals(SettingsBackupPreview(settingCount = 5, fontCount = 0), inspected.preview)
        source.discardInspection(inspected.archiveId)

        val settings = ZipFile(backup).use { zip ->
            zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().use {
                JSONObject(it.readText())
            }
        }
        assertFalse(settings.getJSONObject("app").has("account_token"))

        val restoredApp = AppSettingsRepository(store("restored-app.preferences_pb"))
        val restoredReader = ReaderPreferencesRepository(store("restored-reader.preferences_pb"))
        val restored = repository(restoredApp, restoredReader, fonts)

        assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0), restore(restored, uri))
        assertEquals(ThemeMode.DARK, restoredApp.settings.first().themeMode)
        assertEquals(arrangement, restoredApp.settings.first().selectionActionLayout)
        assertEquals(15, restoredApp.settings.first().tapSideWidth)
        assertEquals(1.75, restoredReader.prefs.first().fontSize, 0.0)
        assertEquals(true, restoredReader.prefs.first().hyphens)
    }

    @Test
    fun `volume key modes are backed up and legacy Boolean archives restore`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val sourceApp = AppSettingsRepository(store("volume-mode-source-app.preferences_pb"))
        sourceApp.setVolumeKeyMode(VolumeKeyMode.REVERSED)
        val sourceReader = ReaderPreferencesRepository(store("volume-mode-source-reader.preferences_pb"))
        val backup = File(folder.root, "volume-mode.zip")
        assertEquals(
            SettingsBackupExportResult.Exported(0),
            repository(sourceApp, sourceReader, fonts).exportTo(Uri.fromFile(backup)),
        )
        val exported = ZipFile(backup).use { zip ->
            JSONObject(
                zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().use {
                    it.readText()
                },
            ).getJSONObject("app")
        }
        assertEquals("reversed", exported.getString("volume_keys_mode"))
        assertFalse(exported.has("volume_keys_turn_pages"))

        val targetApp = AppSettingsRepository(store("volume-mode-target-app.preferences_pb"))
        val targetReader = ReaderPreferencesRepository(store("volume-mode-target-reader.preferences_pb"))
        val targetRepository = repository(targetApp, targetReader, fonts)
        assertEquals(
            SettingsBackupRestoreResult.Restored(0, 0, 0),
            restore(targetRepository, Uri.fromFile(backup)),
        )
        assertEquals(VolumeKeyMode.REVERSED, targetApp.settings.first().volumeKeyMode)

        val legacy = archive(
            "legacy-volume-mode.zip",
            JSONObject()
                .put("app", JSONObject().put("volume_keys_turn_pages", false))
                .put("reader", JSONObject()),
        )

        assertEquals(
            SettingsBackupRestoreResult.Restored(0, 0, 0),
            restore(targetRepository, Uri.fromFile(legacy)),
        )
        assertEquals(VolumeKeyMode.OFF, targetApp.settings.first().volumeKeyMode)
    }

    @Test
    fun `old swapped tap zones restore as the left side and the right side restores`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("tap-zones-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("tap-zones-reader.preferences_pb"))
        for ((id, expected) in listOf("swapped" to TapZones.LEFT, "right" to TapZones.RIGHT)) {
            val backup = archive(
                "tap-zones-$id.zip",
                JSONObject().put("app", JSONObject().put("tap_zones", id)).put("reader", JSONObject()),
            )
            assertEquals(
                SettingsBackupRestoreResult.Restored(0, 0, 0),
                restore(repository(app, reader, fonts), Uri.fromFile(backup)),
            )
            assertEquals(expected, app.settings.first().tapZones)
        }
    }

    @Test
    fun `remembered voices are backed up and restored`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val sourceApp = AppSettingsRepository(store("voices-source.preferences_pb"))
        val english = VoicePreference("openai", "http://kokoro/v1/", "kokoro", "en", "af_bella")
        val french = VoicePreference("openai", "http://kokoro/v1/", "kokoro", "fr", "ff_siwis")
        sourceApp.editReadAloudVoice { remember(english); remember(french); true }

        val backup = File(folder.root, "voices.zip")
        val uri = Uri.fromFile(backup)
        val source = repository(sourceApp, ReaderPreferencesRepository(store("voices-reader.preferences_pb")), fonts)
        assertEquals(SettingsBackupExportResult.Exported(0), source.exportTo(uri))

        val restoredApp = AppSettingsRepository(store("voices-restored.preferences_pb"))
        val restored = repository(restoredApp, ReaderPreferencesRepository(store("voices-restored-reader.preferences_pb")), fonts)
        assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0), restore(restored, uri))
        assertEquals(setOf(english, french), restoredApp.settings.first().voicePreferences.toSet())
    }

    @Test
    fun `export skips stored settings that would make its archive invalid`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val appStore = store("invalid-source-app.preferences_pb")
        appStore.edit {
            it[stringPreferencesKey("theme_mode")] = "future-theme"
            it[booleanPreferencesKey("dynamic_color")] = false
        }
        val readerStore = store("invalid-source-reader.preferences_pb")
        readerStore.edit { it[doublePreferencesKey("font_size")] = 999.0 }
        val repository = repository(
            AppSettingsRepository(appStore),
            ReaderPreferencesRepository(readerStore),
            fonts,
        )
        val backup = File(folder.root, "sanitized-settings.zip")

        assertEquals(
            SettingsBackupExportResult.Exported(0),
            repository.exportTo(Uri.fromFile(backup)),
        )
        val inspection = repository.inspect(Uri.fromFile(backup)) as SettingsBackupInspection.Ready
        assertEquals(SettingsBackupPreview(settingCount = 1, fontCount = 0), inspection.preview)
        val settings = ZipFile(backup).use { zip ->
            JSONObject(
                zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().use {
                    it.readText()
                },
            )
        }
        assertFalse(settings.getJSONObject("app").has("theme_mode"))
        assertEquals(false, settings.getJSONObject("app").getBoolean("dynamic_color"))
        assertFalse(settings.getJSONObject("reader").has("font_size"))
    }

    @Test
    fun `export restores a legacy page turn choice over a modern choice`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val sourceApp = AppSettingsRepository(store("legacy-turn-app.preferences_pb"))
        val sourceReaderStore = store("legacy-turn-reader.preferences_pb")
        sourceReaderStore.edit { it[booleanPreferencesKey("page_turn_animation")] = false }
        val backup = File(folder.root, "legacy-page-turn.zip")
        assertEquals(
            SettingsBackupExportResult.Exported(0),
            repository(sourceApp, ReaderPreferencesRepository(sourceReaderStore), fonts)
                .exportTo(Uri.fromFile(backup)),
        )

        val targetApp = AppSettingsRepository(store("modern-turn-app.preferences_pb"))
        val targetReader = ReaderPreferencesRepository(store("modern-turn-reader.preferences_pb"))
        targetReader.setPageTurnStyle(PageTurnStyle.SLIDE)
        assertEquals(
            SettingsBackupRestoreResult.Restored(0, 0, 0),
            restore(repository(targetApp, targetReader, fonts), Uri.fromFile(backup)),
        )
        assertEquals(PageTurnStyle.NONE, targetReader.prefs.first().pageTurnStyle)
    }

    @Test
    fun `an exported custom font is restored with its content identity`() = runTest {
        val sourceFonts = UserFontRepository(context, this, loadCheck = { true })
        sourceFonts.awaitReady()
        val fontBytes = SfntFixtures.sfnt(names = listOf(SfntFixtures.name(1, "Backup Font")))
        assertTrue(sourceFonts.import(Uri.fromFile(writeFont(fontBytes)), "backup.ttf") is FontImportResult.Imported)

        val app = AppSettingsRepository(store("font-backup-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("font-backup-reader.preferences_pb"))
        val backup = File(folder.root, "font-settings.zip")
        assertEquals(
            SettingsBackupExportResult.Exported(1),
            repository(app, reader, sourceFonts).exportTo(Uri.fromFile(backup)),
        )

        val digest = sha256(fontBytes)
        assertTrue(File(context.filesDir, "fonts/$digest.ttf").delete())
        val targetFonts = UserFontRepository(context, this, loadCheck = { true })
        targetFonts.awaitReady()
        val result = restore(repository(app, reader, targetFonts), Uri.fromFile(backup))

        assertEquals(SettingsBackupRestoreResult.Restored(1, 0, 0), result)
        assertTrue(targetFonts.registry().contains("user:$digest"))
        assertEquals(fontBytes.toList(), File(context.filesDir, "fonts/$digest.ttf").readBytes().toList())
    }

    @Test
    fun `nameless custom fonts keep their picked display names in a backup`() = runTest {
        val sourceFonts = UserFontRepository(context, this, loadCheck = { true })
        sourceFonts.awaitReady()
        val firstFont = SfntFixtures.sfnt(names = emptyList())
        val secondFont = SfntFixtures.sfnt(names = emptyList(), weightClass = 700)
        assertTrue(
            sourceFonts.import(Uri.fromFile(writeFont(firstFont)), "OpenDyslexic-Regular.ttf") is
                FontImportResult.Imported,
        )
        assertTrue(
            sourceFonts.import(Uri.fromFile(writeFont(secondFont)), "Noto-Alt-Regular.ttf") is
                FontImportResult.Imported,
        )

        val app = AppSettingsRepository(store("nameless-font-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("nameless-font-reader.preferences_pb"))
        val backup = File(folder.root, "nameless-font-settings.zip")
        assertEquals(
            SettingsBackupExportResult.Exported(2),
            repository(app, reader, sourceFonts).exportTo(Uri.fromFile(backup)),
        )

        listOf(firstFont, secondFont).forEach { bytes ->
            val digest = sha256(bytes)
            assertTrue(File(context.filesDir, "fonts/$digest.ttf").delete())
        }
        val targetFonts = UserFontRepository(context, this, loadCheck = { true })
        targetFonts.awaitReady()
        assertEquals(
            SettingsBackupRestoreResult.Restored(2, 0, 0),
            restore(repository(app, reader, targetFonts), Uri.fromFile(backup)),
        )
        assertEquals(
            setOf("OpenDyslexic-Regular", "Noto-Alt-Regular"),
            targetFonts.backupFonts().map { it.displayName }.toSet(),
        )
    }

    @Test
    fun `a damaged archive is rejected without changing settings`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("app.preferences_pb"))
        app.setThemeMode(ThemeMode.LIGHT)
        val reader = ReaderPreferencesRepository(store("reader.preferences_pb"))
        val backup = File(folder.root, "damaged.zip").apply { writeText("not a zip archive") }
        val repository = repository(app, reader, fonts)

        assertTrue(repository.inspect(Uri.fromFile(backup)) is SettingsBackupInspection.Failed)
        assertTrue(restore(repository, Uri.fromFile(backup)) is SettingsBackupRestoreResult.Failed)
        assertEquals(ThemeMode.LIGHT, app.settings.first().themeMode)
    }

    @Test
    fun `restore uses the archive that was previewed`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("preview-app.preferences_pb"))
        app.setThemeMode(ThemeMode.LIGHT)
        val reader = ReaderPreferencesRepository(store("preview-reader.preferences_pb"))
        val backup = File(folder.root, "preview.zip")
        val repository = repository(app, reader, fonts)
        val abandoned = File(context.cacheDir, "settings-backup-abandoned-${System.nanoTime()}")
        assertTrue(abandoned.mkdirs())
        val original = JSONObject()
            .put("app", JSONObject().put("theme_mode", "dark"))
            .put("reader", JSONObject())
        val replacement = JSONObject()
            .put("app", JSONObject().put("theme_mode", "light"))
            .put("reader", JSONObject())
        archive("preview-original.zip", original).copyTo(backup)

        val inspected = repository.inspect(Uri.fromFile(backup)) as SettingsBackupInspection.Ready
        assertFalse(abandoned.exists())
        repository.discardProcessInspections()
        assertFalse(File(context.cacheDir, "settings-backup-${inspected.archiveId}").exists())
        val stagedRoots = context.cacheDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("settings-backup-") }
            .orEmpty()
        assertTrue(stagedRoots.isEmpty())
        val retainedInspection = repository.inspect(Uri.fromFile(backup)) as SettingsBackupInspection.Ready
        archive("preview-replacement.zip", replacement).copyTo(backup, overwrite = true)
        assertEquals(
            SettingsBackupRestoreResult.Restored(0, 0, 0),
            repository.restore(retainedInspection.archiveId),
        )
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
    }

    @Test
    fun `restore rejects a font whose filename digest does not match its contents`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("invalid-font-app.preferences_pb"))
        app.setThemeMode(ThemeMode.LIGHT)
        val reader = ReaderPreferencesRepository(store("invalid-font-reader.preferences_pb"))
        val payload = JSONObject()
            .put("app", JSONObject().put("theme_mode", "dark"))
            .put("reader", JSONObject())
        val fontBytes = "not a font".toByteArray()
        val fontPath = "fonts/${"a".repeat(64)}.ttf"
        val backup = archive("invalid-font.zip", payload, mapOf(fontPath to fontBytes))
        val repository = repository(app, reader, fonts)

        assertEquals(
            SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
            restore(repository, Uri.fromFile(backup)),
        )
        assertEquals(ThemeMode.LIGHT, app.settings.first().themeMode)
    }

    @Test
    fun `restore rejects numeric settings outside the reader controls`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("numeric-app.preferences_pb"))
        app.setThemeMode(ThemeMode.LIGHT)
        val reader = ReaderPreferencesRepository(store("numeric-reader.preferences_pb"))
        val repository = repository(app, reader, fonts)
        val invalidReaderSettings = listOf(
            JSONObject().put("font_size", 999),
            JSONObject().put("brightness", -2),
            JSONObject().put("auto_scroll_speed", 2.5),
        )

        invalidReaderSettings.forEachIndexed { index, readerSettings ->
            val payload = JSONObject()
                .put("app", JSONObject().put("theme_mode", "dark"))
                .put("reader", readerSettings)
            val backup = archive("numeric-$index.zip", payload)

            assertEquals(
                SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
                restore(repository, Uri.fromFile(backup)),
            )
            assertEquals(ThemeMode.LIGHT, app.settings.first().themeMode)
        }
    }

    @Test
    fun `restore rejects a backup for another application`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("wrong-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("wrong-app-reader.preferences_pb"))
        val payload = JSONObject()
            .put("app", JSONObject().put("theme_mode", "dark"))
            .put("reader", JSONObject())
        val backup = archive("wrong-app.zip", payload, application = "another-app")

        assertEquals(
            SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
            restore(repository(app, reader, fonts), Uri.fromFile(backup)),
        )
        assertEquals(ThemeMode.SYSTEM, app.settings.first().themeMode)
    }

    @Test
    fun `inspection counts only settings that will be restored`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("unknown-setting-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("unknown-setting-reader.preferences_pb"))
        val payload = JSONObject()
            .put("app", JSONObject().put("theme_mode", "dark").put("future_setting", true))
            .put("reader", JSONObject().put("future_reader_setting", 12))
        val backup = archive("unknown-settings.zip", payload)

        val inspection = repository(app, reader, fonts).inspect(Uri.fromFile(backup))

        assertEquals(
            SettingsBackupPreview(settingCount = 1, fontCount = 0),
            (inspection as SettingsBackupInspection.Ready).preview,
        )
    }

    @Test
    fun `inspection rejects coerced and fractional format identifiers`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("invalid-format-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("invalid-format-reader.preferences_pb"))
        val repository = repository(app, reader, fonts)
        val payload = JSONObject()
            .put("app", JSONObject())
            .put("reader", JSONObject())

        listOf("1", 1.5).forEachIndexed { index, format ->
            val backup = archive("invalid-format-$index.zip", payload, format = format)
            assertEquals(
                SettingsBackupInspection.Failed(SettingsBackupFailure.UNSUPPORTED_VERSION),
                repository.inspect(Uri.fromFile(backup)),
            )
        }
    }

    @Test
    fun `inspection rejects coerced manifest entry fields`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("invalid-entry-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("invalid-entry-reader.preferences_pb"))
        val repository = repository(app, reader, fonts)
        val payload = JSONObject()
            .put("app", JSONObject())
            .put("reader", JSONObject())
        val settingsSize = payload.toString().toByteArray().size
        val invalidFields: List<Map<String, Any>> = listOf(
            mapOf("size" to settingsSize.toString()),
            mapOf("size" to settingsSize + 0.5),
            mapOf("path" to 123),
            mapOf("sha256" to 123),
        )

        invalidFields.forEachIndexed { index, overrides ->
            val backup = archive(
                "invalid-entry-$index.zip",
                payload,
                manifestEntryOverrides = overrides,
            )
            assertEquals(
                SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
                repository.inspect(Uri.fromFile(backup)),
            )
        }
    }

    @Test
    fun `restore rejects an invalid dictionary URL`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("invalid-dictionary.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("invalid-dictionary-reader.preferences_pb"))
        val payload = JSONObject()
            .put("app", JSONObject().put("dictionary_base_url", "http://example.com"))
            .put("reader", JSONObject())
        val backup = archive("invalid-dictionary.zip", payload)

        assertEquals(
            SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
            restore(repository(app, reader, fonts), Uri.fromFile(backup)),
        )
        assertEquals(
            com.chmouel.liseur.domain.DictionaryUrl.DEFAULT_BASE_URL,
            app.settings.first().dictionaryBaseUrl,
        )
    }

    @Test
    fun `restore rejects encoded settings with unknown values`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("invalid-encoded-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("invalid-encoded-reader.preferences_pb"))
        val invalidAppSettings = listOf(
            JSONObject().put("library_filters", "future_filter"),
            JSONObject().put("volume_keys_mode", "sideways"),
            JSONObject().put(
                "highlight_tints_offered",
                org.json.JSONArray().put("MAGENTA"),
            ),
            JSONObject().put("highlight_tint_default", "MAGENTA"),
            JSONObject().put("tap_side_width", 17),
            JSONObject().put("tap_zones", "both_forward"),
        )

        invalidAppSettings.forEachIndexed { index, invalidSettings ->
            val payload = JSONObject()
                .put("app", invalidSettings)
                .put("reader", JSONObject())
            val backup = archive("invalid-encoded-$index.zip", payload)
            assertEquals(
                SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
                restore(repository(app, reader, fonts), Uri.fromFile(backup)),
            )
        }
    }

    @Test
    fun `restore reports when only app settings were written`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("partial-app.preferences_pb"))
        app.setThemeMode(ThemeMode.LIGHT)
        val readerStore = store("partial-reader.preferences_pb")
        val reader = ReaderPreferencesRepository(FailingDataStore(readerStore))
        val payload = JSONObject()
            .put("app", JSONObject().put("theme_mode", "dark"))
            .put("reader", JSONObject().put("font_size", 1.5))
        val backup = archive("partial.zip", payload)

        assertEquals(
            SettingsBackupRestoreResult.PartiallyRestored(),
            restore(repository(app, reader, fonts), Uri.fromFile(backup)),
        )
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        assertEquals(
            ReaderPrefs.DEFAULT_FONT_SIZE,
            ReaderPreferencesRepository(readerStore).prefs.first().fontSize,
            0.0,
        )
    }

    @Test
    fun `one ZIP restores annotations alongside settings without overwriting existing marks`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("combined-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("combined-reader.preferences_pb"))
        app.setThemeMode(ThemeMode.DARK)
        db.bookDao().upsert(book("source-book"))
        val marks = AnnotationKind.entries.map { kind ->
            BookAnnotation(
                id = kind.name,
                bookId = "source-book",
                kind = kind.name,
                locatorJson = if (kind == AnnotationKind.BOOK_NOTE) "" else "{\"href\":\"chapter.xhtml\",\"type\":\"application/xhtml+xml\"}",
                text = "A passage",
                note = "A note",
                tint = "YELLOW",
                createdAt = 123,
                noteCreatedAt = 124,
                noteUpdatedAt = 125000,
                updatedAt = 126000,
            )
        }
        marks.forEach { db.annotationDao().upsert(it) }
        val uri = Uri.fromFile(File(folder.root, "combined.zip"))
        val repository = repository(app, reader, fonts)
        assertEquals(SettingsBackupExportResult.Exported(0, 4), repository.exportTo(uri))
        ZipFile(File(uri.path!!)).use { zip ->
            assertTrue(zip.getEntry("annotations.json") != null)
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            assertEquals(7, manifest.getInt("format"))
        }

        db.annotationDao().deleteForBook("source-book")
        db.bookDao().upsert(book("target-book"))
        // Remove the source identity so matching uses the title and author on this device.
        db.bookDao().deleteByUrls(listOf("source-book"))
        val edited = marks.first().copy(bookId = "target-book", note = "A newer local note")
        db.annotationDao().upsert(edited)
        app.setThemeMode(ThemeMode.LIGHT)

        val ready = repository.inspect(uri) as SettingsBackupInspection.Ready
        assertEquals(SettingsBackupPreview(1, 0, 4, 1, 1), ready.preview)
        assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0, 3, 1), repository.restore(ready.archiveId))
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        assertEquals(edited, db.annotationDao().byId(edited.id))
        marks.drop(1).forEach { mark ->
            assertEquals(mark.copy(bookId = "target-book"), db.annotationDao().byId(mark.id))
        }
        assertEquals(listOf("target-book"), requested)
        requested.clear()
        assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0, 0, 4), restore(repository, uri))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `the same restore action accepts legacy annotation JSON and preserves settings`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("legacy-annotation-app.preferences_pb"))
        app.setThemeMode(ThemeMode.DARK)
        val reader = ReaderPreferencesRepository(store("legacy-annotation-reader.preferences_pb"))
        reader.setFontSize(1.75)
        val mark = BookAnnotation("orphan", "missing-book", AnnotationKind.BOOK_NOTE.name, "", note = "Keep me", createdAt = 123)
        val file = File(folder.root, "legacy-annotations.json").apply {
            val json = JSONObject(encodeAnnotationBackup(listOf(BackedUpBook("missing-book", null, null, listOf(mark)))))
            json.getJSONArray("books").getJSONObject(0).getJSONArray("annotations").getJSONObject(0).remove("updated_at")
            writeText(json.toString())
        }
        val repository = repository(app, reader, fonts)
        val ready = repository.inspect(Uri.fromFile(file)) as SettingsBackupInspection.Ready
        assertEquals(SettingsBackupPreview(0, 0, 1, 1, 0), ready.preview)
        file.writeText("The picked file changed after preview")
        assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0, 1, 0), repository.restore(ready.archiveId))
        assertEquals(mark.copy(updatedAt = 123000), db.annotationDao().byId("orphan"))
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        assertEquals(1.75, reader.prefs.first().fontSize, 0.0)
    }

    @Test
    fun `invalid annotations reject the whole backup before settings change`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("invalid-annotations-app.preferences_pb"))
        app.setThemeMode(ThemeMode.LIGHT)
        val reader = ReaderPreferencesRepository(store("invalid-annotations-reader.preferences_pb"))
        val payload = JSONObject().put("app", JSONObject().put("theme_mode", "dark")).put("reader", JSONObject())
        val file = archive("invalid-annotations.zip", payload, mapOf("annotations.json" to "broken".toByteArray()), format = 2)
        assertEquals(
            SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
            restore(repository(app, reader, fonts), Uri.fromFile(file)),
        )
        assertEquals(ThemeMode.LIGHT, app.settings.first().themeMode)
        assertTrue(db.annotationDao().all().isEmpty())
    }

    @Test
    fun `version two requires its annotation payload`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("missing-annotations-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("missing-annotations-reader.preferences_pb"))
        val payload = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val file = archive("missing-annotations.zip", payload, format = 2)
        assertEquals(
            SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
            repository(app, reader, fonts).inspect(Uri.fromFile(file)),
        )
    }

    @Test
    fun `older settings backups preserve destination annotations positions and companion recaps`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("version-one-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("version-one-reader.preferences_pb"))
        val mark = BookAnnotation("existing", "local-book", AnnotationKind.BOOK_NOTE.name, "", note = "Keep me", createdAt = 123)
        db.annotationDao().upsert(mark)
        val payload = JSONObject().put("app", JSONObject().put("theme_mode", "dark")).put("reader", JSONObject())
        val position = ReadingProgress("local-book", "{}", 0.4, updatedAt = 12)
        db.readingProgressDao().upsert(position)
        val recap = ChapterRecapRow("local-book", "chapter", 0, "a".repeat(64), "server", "model", Recaps.FORMAT,
            ChapterRecap.EMPTY.encode(), 100, 200)
        companionDb.recaps().putRecap(recap)
        for (version in 1..3) {
            val entries = linkedMapOf<String, ByteArray>()
            if (version >= 2) entries["annotations.json"] = encodeAnnotationBackup(emptyList()).toByteArray()
            if (version >= 3) entries["positions.json"] = """{"format":1,"application":"liseur","positions":[]}""".toByteArray()
            val file = archive("version-$version.zip", payload, entries, format = version)
            assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0), restore(repository(app, reader, fonts), Uri.fromFile(file)))
            assertEquals(recap, companionDb.recaps().recap("local-book", "chapter"))
        }
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        assertEquals(mark, db.annotationDao().byId(mark.id))
        assertEquals(position, db.readingProgressDao().get("local-book"))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `annotation storage failure is reported as a partial restore`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("annotation-failure-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("annotation-failure-reader.preferences_pb"))
        val mark = BookAnnotation("incoming", "local-book", AnnotationKind.BOOK_NOTE.name, "", note = "Keep me", createdAt = 123)
        val payload = JSONObject().put("app", JSONObject().put("theme_mode", "dark")).put("reader", JSONObject())
        val json = encodeAnnotationBackup(listOf(BackedUpBook(mark.bookId, null, null, listOf(mark))))
        val file = archive("annotation-failure.zip", payload, mapOf("annotations.json" to json.toByteArray()), format = 2)
        val failingDao = object : BookAnnotationDao by db.annotationDao() {
            override suspend fun insertMissing(annotations: List<BookAnnotation>): List<Long> = throw IOException("storage failed")
        }
        val repository = SettingsBackupRepository(
            context, app, reader, fonts,
            AnnotationBackupRepository(context, failingDao, db.bookDao(), requested::add),
            ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requested::add),
            companion = companionBackup(),
        )
        assertEquals(SettingsBackupRestoreResult.PartiallyRestored(), restore(repository, Uri.fromFile(file)))
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        assertTrue(db.annotationDao().all().isEmpty())
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `companion storage failure is reported as a partial restore after settings and positions`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("companion-failure-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("companion-failure-reader.preferences_pb"))
        db.bookDao().upsert(book("local-book"))
        val payload = JSONObject().put("app", JSONObject().put("theme_mode", "dark")).put("reader", JSONObject())
        val positions = """{"format":1,"application":"liseur","positions":[{"book_id":"local-book","locator":null,"progression":0.5,"read_at":10}]}"""
        val recap = JSONObject().put("book_id", "local-book").put("unit_key", "chapter").put("unit", 0)
            .put("content_sha", "a".repeat(64)).put("server_id", "server").put("model", "model")
            .put("recap_format", Recaps.FORMAT).put("json", ChapterRecap.EMPTY.encode()).put("saved_at", 1).put("used_at", 2)
        val companion = JSONObject().put("format", 1).put("application", "liseur")
            .put("recaps", org.json.JSONArray().put(recap))
        val file = archive("companion-failure.zip", payload, mapOf(
            "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
            "positions.json" to positions.toByteArray(),
            "companion.json" to companion.toString().toByteArray(),
        ), format = 4)
        val repository = SettingsBackupRepository(
            context, app, reader, fonts,
            AnnotationBackupRepository(context, db.annotationDao(), db.bookDao(), requested::add),
            ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requested::add),
            companion = CompanionBackupRepository({ throw IOException("storage failed") }, db.bookDao(), storeExists = { true }),
        )
        assertEquals(SettingsBackupRestoreResult.PartiallyRestored(), restore(repository, Uri.fromFile(file)))
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        assertEquals(0.5, db.readingProgressDao().get("local-book")!!.totalProgression!!, 0.0)
    }

    private fun companionBackup() = CompanionBackupRepository({ companionStore }, db.bookDao(), storeExists = { true })

    private fun liseurSyncConnections() = JSONObject()
        .put("version", 1)
        .put(
            "account",
            AccountBackup.encode(
                RemoteServer(
                    kind = ServerKind.LISEUR_SYNC,
                    baseUrl = "https://library.example",
                    username = "reader",
                    passwordCipher = null,
                    apiKeyCipher = null,
                    accountId = "device",
                    userId = null,
                    koboTokenCipher = null,
                    canDownload = true,
                    addedAt = 10,
                    catalogSyncedAt = null,
                    positionSyncedAt = null,
                    syncToken = null,
                ),
            ),
        )
        .put("kosync", JSONObject.NULL)
        .put("keys", JSONObject())
        .put("gemini", JSONObject.NULL)

    private fun book(url: String) = Book(
        url = url, title = "A book", author = "An author", coverPath = null, source = null,
        addedAt = 0, lastOpenedAt = null,
    )

    @Test
    fun `an unreadable saved credential fails the encrypted export with its own reason`() = runTest {
        val app = AppSettingsRepository(store("unreadable-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("unreadable-reader.preferences_pb"))
        val repository = SettingsBackupRepository(
            context, app, reader, UserFontRepository(context, this, loadCheck = { true }),
            AnnotationBackupRepository(context, db.annotationDao(), db.bookDao(), requested::add),
            ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requested::add),
            companionBackup(),
            exportConnections = { throw com.chmouel.liseur.data.remote.UnreadableCredentialException() },
        )
        val uri = Uri.fromFile(File(folder.root, "unreadable.zip"))
        assertEquals(
            SettingsBackupExportResult.Failed(SettingsBackupFailure.UNREADABLE_CREDENTIAL),
            repository.exportTo(uri, "backup password".toCharArray()),
        )
    }

    @Test
    fun `encrypted restore authenticates before writes and can retry without inspecting again`() = runTest {
        val app = AppSettingsRepository(store("encrypted-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("encrypted-reader.preferences_pb"))
        val payload = JSONObject().put("version", 1).put("account", JSONObject.NULL)
            .put("kosync", JSONObject.NULL).put("keys", JSONObject()).put("gemini", "secret-gemini")
        var applied = 0
        var failAfterWrite = false
        var failPreparation = false
        val repository = SettingsBackupRepository(
            context, app, reader, UserFontRepository(context, this, loadCheck = { true }),
            AnnotationBackupRepository(context, db.annotationDao(), db.bookDao(), requested::add),
            ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requested::add),
            companionBackup(),
            exportConnections = { payload },
            prepareConnectionRestore = { restored, values ->
                if (failPreparation) throw IOException("Keystore unavailable")
                suspend {
                assertEquals("secret-gemini", restored.getString("gemini"))
                applied++
                app.restoreBackupValues(values)
                if (failAfterWrite) throw IOException("Storage failed")
                }
            },
        )
        app.setThemeMode(ThemeMode.DARK)
        val uri = Uri.fromFile(File(folder.root, "encrypted.zip"))
        assertEquals(
            SettingsBackupExportResult.Exported(0, 0),
            repository.exportTo(uri, "backup password".toCharArray()),
        )
        ZipFile(File(uri.path!!)).use { zip ->
            val encrypted = zip.getInputStream(zip.getEntry("connections.enc.json")).readBytes().toString(Charsets.UTF_8)
            assertFalse(encrypted.contains("secret-gemini"))
        }
        app.setThemeMode(ThemeMode.LIGHT)
        val ready = repository.inspect(uri) as SettingsBackupInspection.Ready
        assertTrue(ready.preview.includesConnections)
        assertEquals(0, applied)
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("settings-backup-") }.forEach { directory ->
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                assertFalse(file.readText().contains("secret-gemini"))
            }
        }
        val cancelled = repository.inspect(uri) as SettingsBackupInspection.Ready
        repository.discardInspection(cancelled.archiveId)
        assertEquals(0, applied)
        assertEquals(SettingsBackupRestoreResult.Failed(SettingsBackupFailure.PASSWORD), repository.restore(ready.archiveId))
        assertEquals(SettingsBackupRestoreResult.Failed(SettingsBackupFailure.PASSWORD),
            repository.restore(ready.archiveId, "wrong".toCharArray()))
        assertEquals(0, applied)
        assertEquals(ThemeMode.LIGHT, app.settings.first().themeMode)
        assertEquals(
            SettingsBackupRestoreResult.Restored(0, 0, 0),
            repository.restore(ready.archiveId, "backup password".toCharArray()),
        )
        assertEquals(1, applied)
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        val plainUri = Uri.fromFile(File(folder.root, "plain.zip"))
        assertEquals(SettingsBackupExportResult.Exported(0), repository.exportTo(plainUri))
        ZipFile(File(plainUri.path!!)).use { assertEquals(null, it.getEntry("connections.enc.json")) }
        assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0), restore(repository, plainUri))
        assertEquals(1, applied)
        failAfterWrite = true
        app.setThemeMode(ThemeMode.LIGHT)
        val retry = repository.inspect(uri) as SettingsBackupInspection.Ready
        assertEquals(SettingsBackupRestoreResult.PartiallyRestored(),
            repository.restore(retry.archiveId, "backup password".toCharArray()))
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        failPreparation = true
        app.setThemeMode(ThemeMode.LIGHT)
        val unprepared = repository.inspect(uri) as SettingsBackupInspection.Ready
        assertEquals(SettingsBackupRestoreResult.Failed(SettingsBackupFailure.RESTORE),
            repository.restore(unprepared.archiveId, "backup password".toCharArray()))
        assertEquals(ThemeMode.LIGHT, app.settings.first().themeMode)
    }

    @Test
    fun `only backups containing a liseur sync account need a new pairing`() = runTest {
        val app = AppSettingsRepository(store("pairing-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("pairing-reader.preferences_pb"))
        val repository = SettingsBackupRepository(
            context,
            app,
            reader,
            UserFontRepository(context, this, loadCheck = { true }),
            AnnotationBackupRepository(context, db.annotationDao(), db.bookDao(), requested::add),
            ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requested::add),
            companionBackup(),
            exportConnections = { liseurSyncConnections() },
            prepareConnectionRestore = { _, _ -> suspend {} },
        )
        val uri = Uri.fromFile(File(folder.root, "pairing.zip"))

        assertEquals(
            SettingsBackupExportResult.Exported(0, 0, liseurSyncNeedsPairing = true),
            repository.exportTo(uri, "backup password".toCharArray()),
        )
        val preview = repository.inspect(uri) as SettingsBackupInspection.Ready
        assertTrue(preview.preview.includesConnections)
        assertEquals(
            SettingsBackupRestoreResult.Restored(0, 0, 0, liseurSyncNeedsPairing = true),
            repository.restore(preview.archiveId, "backup password".toCharArray()),
        )
    }

    @Test
    fun `invalid decrypted connections are rejected before writes`() = runTest {
        val app = AppSettingsRepository(store("invalid-encrypted-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("invalid-encrypted-reader.preferences_pb"))
        val settings = JSONObject().put("app", JSONObject().put("theme_mode", "dark").put("ai_servers", "[]"))
            .put("reader", JSONObject())
        val invalid = JSONObject().put("version", 1).put("account", JSONObject.NULL)
            .put("kosync", JSONObject.NULL).put("keys", JSONObject()).put("gemini", 123)
        val encrypted = BackupEncryption.encrypt(invalid.toString().toByteArray(), "password".toCharArray())
        val uri = Uri.fromFile(archive("invalid-connections.zip", settings, mapOf(
            "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
            "positions.json" to "{\"format\":1,\"application\":\"liseur\",\"positions\":[]}".toByteArray(),
            "companion.json" to "{\"format\":1,\"application\":\"liseur\",\"recaps\":[]}".toByteArray(),
            "connections.enc.json" to encrypted,
        ), format = 5))
        val repository = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val ready = repository.inspect(uri) as SettingsBackupInspection.Ready
        assertEquals(SettingsBackupRestoreResult.Failed(SettingsBackupFailure.INVALID_ARCHIVE),
            repository.restore(ready.archiveId, "password".toCharArray()))
        assertEquals(ThemeMode.SYSTEM, app.settings.first().themeMode)
    }

    @Test
    fun `format seven round trip adds a missing book and reattaches its position, marks and sessions`() = runTest {
        val epubFile = folder.newFile("roundtrip.epub").also { writeEpub(it, title = "Roundtrip Book", author = "Roundtrip Author") }
        val sha = sha256(epubFile.readBytes())
        val archivedUrl = "calibre:uuid-roundtrip"
        db.bookDao().upsert(
            Book(
                url = archivedUrl,
                title = "Roundtrip Book",
                author = "Roundtrip Author",
                coverPath = null,
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
                localUri = Uri.fromFile(epubFile).toString(),
            ),
        )
        val locator = """{"href":"OEBPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5,"totalProgression":0.5}}"""
        db.readingProgressDao().upsert(ReadingProgress(archivedUrl, locator, 0.5, updatedAt = 100, readAt = 90))
        val mark = BookAnnotation("mark-1", archivedUrl, AnnotationKind.BOOK_NOTE.name, "", note = "Note on roundtrip", createdAt = 50)
        db.annotationDao().upsert(mark)
        db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = archivedUrl,
                startedAt = 1000,
                endedAt = 2000,
                lastCheckpointAt = 2000,
                durationMs = 900,
                idleMs = 100,
                startProgression = 0.1,
                endProgression = 0.5,
            ),
        )

        val app = AppSettingsRepository(store("v7-roundtrip-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("v7-roundtrip-reader.preferences_pb"))
        val repo = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val zipFile = File(folder.root, "v7-roundtrip.zip")
        val uri = Uri.fromFile(zipFile)

        val exportResult = repo.exportTo(uri, includeSessions = true, bookUrls = setOf(archivedUrl))
        assertEquals(
            SettingsBackupExportResult.Exported(
                fonts = 0,
                annotations = 1,
                books = 1,
                sessions = 1,
                bookBytes = epubFile.length(),
            ),
            exportResult,
        )

        // Wipe DB and source file
        db.bookDao().deleteByUrls(listOf(archivedUrl))
        db.readingProgressDao().forget(archivedUrl)
        db.annotationDao().delete(mark)
        db.readingSessionDao().deleteForBook(archivedUrl)
        assertTrue(epubFile.delete())

        val preview = (repo.inspect(uri) as SettingsBackupInspection.Ready).preview
        assertEquals(1, preview.bookCount)
        assertEquals(0, preview.booksInLibrary)
        assertEquals(1, preview.booksToImport)
        assertEquals(1, preview.matchedAnnotationBooks)
        assertEquals(1, preview.sessionCount)

        val inspectReady = repo.inspect(uri) as SettingsBackupInspection.Ready
        val restored = repo.restore(inspectReady.archiveId) as SettingsBackupRestoreResult.Restored
        assertEquals(1, restored.booksImported)
        assertEquals(0, restored.booksAlreadyPresent)
        assertEquals(0, restored.bookFailures)
        assertEquals(1, restored.annotationsAdded)
        assertEquals(1, restored.positionsRestored)
        assertEquals(1, restored.sessionsAdded)

        val targetBookFile = File(File(context.filesDir, "books"), "$sha.epub")
        assertTrue(targetBookFile.isFile)
        val expectedUrl = Uri.fromFile(targetBookFile).toString()
        assertEquals(listOf(expectedUrl), db.bookDao().allOnce().map { it.url })
        assertEquals(0.5, db.readingProgressDao().get(expectedUrl)?.totalProgression)
        assertEquals(expectedUrl, db.annotationDao().byId("mark-1")?.bookId)
        val session = db.readingSessionDao().allOnce().single()
        assertEquals(expectedUrl, session.bookUrl)
        assertTrue(session.importedAt != null)
    }

    @Test
    fun `a failed book import keeps its data under the archived URL`() = runTest {
        val twin = folder.newFile("twin.epub").also {
            writeEpub(it, title = "Twin", author = "Twin Author", id = "urn:uuid:twin")
        }
        val broken = folder.newFile("broken-twin.epub").also { it.writeText("not an epub") }
        val twinUrl = "calibre:twin"
        val brokenUrl = "calibre:broken-twin"
        db.bookDao().upsert(
            book(twinUrl).copy(
                title = "Twin", author = "Twin Author",
                downloadState = DownloadState.DOWNLOADED, localUri = Uri.fromFile(twin).toString(),
            ),
        )
        db.bookDao().upsert(
            book(brokenUrl).copy(
                title = "Twin", author = "Twin Author",
                downloadState = DownloadState.DOWNLOADED, localUri = Uri.fromFile(broken).toString(),
            ),
        )
        val locator = """{"href":"OEBPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":%s,"totalProgression":%s}}"""
        db.readingProgressDao().upsert(ReadingProgress(twinUrl, locator.format(0.25, 0.25), 0.25, updatedAt = 100, readAt = 90))
        db.readingProgressDao().upsert(ReadingProgress(brokenUrl, locator.format(0.75, 0.75), 0.75, updatedAt = 100, readAt = 90))

        val repo = repository(
            AppSettingsRepository(store("failed-claim-app.preferences_pb")),
            ReaderPreferencesRepository(store("failed-claim-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val zipUri = Uri.fromFile(File(folder.root, "failed-claim.zip"))
        repo.exportTo(zipUri, bookUrls = setOf(twinUrl, brokenUrl))

        // The destination has the exact twin only, and the other book will not open.
        db.bookDao().deleteByUrls(listOf(brokenUrl))
        assertTrue(broken.delete())

        val ready = repo.inspect(zipUri) as SettingsBackupInspection.Ready
        assertEquals(1, ready.preview.booksInLibrary)
        assertEquals(1, ready.preview.booksToImport)
        val restored = repo.restore(ready.archiveId) as SettingsBackupRestoreResult.Restored
        assertEquals(0, restored.booksImported)
        assertEquals(1, restored.bookFailures)
        assertEquals(0.25, db.readingProgressDao().get(twinUrl)?.totalProgression)
        assertEquals(0.75, db.readingProgressDao().get(brokenUrl)?.totalProgression)
    }

    @Test
    fun `a book entry larger than its declared size is rejected before it fills the cache`() = runTest {
        val repo = repository(
            AppSettingsRepository(store("under-declared-app.preferences_pb")),
            ReaderPreferencesRepository(store("under-declared-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val settings = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val epubBytes = "epub-bytes".toByteArray()
        val sha = sha256(epubBytes)
        val file = archive(
            "under-declared.zip",
            settings,
            linkedMapOf(
                "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
                "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
                "companion.json" to """{"format":1,"application":"liseur","recaps":[]}""".toByteArray(),
                "books.json" to """{"format":1,"application":"liseur","books":[{"book_id":"b1","title":"T","author":null,"sha256":"$sha","size":0}]}""".toByteArray(),
                "books/$sha.epub" to epubBytes,
            ),
            format = 7,
        )
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repo.inspect(Uri.fromFile(file)))
    }

    @Test
    fun `a book already in the library by URL or title and author is matched and never imported`() = runTest {
        val epub1 = folder.newFile("match1.epub").also { writeEpub(it, title = "Book One", author = "Author One", id = "id-1") }
        val epub2 = folder.newFile("match2.epub").also { writeEpub(it, title = "Book Two", author = "Author Two", id = "id-2") }
        db.bookDao().upsert(
            Book(
                url = "calibre:1",
                title = "Book One",
                author = "Author One",
                coverPath = null,
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
                localUri = Uri.fromFile(epub1).toString(),
            ),
        )
        db.bookDao().upsert(
            Book(
                url = "calibre:2",
                title = "Book Two",
                author = "Author Two",
                coverPath = null,
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
                localUri = Uri.fromFile(epub2).toString(),
            ),
        )

        val app = AppSettingsRepository(store("match-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("match-reader.preferences_pb"))
        val repo = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val uri = Uri.fromFile(File(folder.root, "matched.zip"))
        repo.exportTo(uri, bookUrls = setOf("calibre:1", "calibre:2"))

        // Replace calibre:2 with a local URL having the same title & author
        db.bookDao().deleteByUrls(listOf("calibre:2"))
        db.bookDao().upsert(
            Book(
                url = "file:///different/book2.epub",
                title = "Book Two",
                author = "Author Two",
                coverPath = null,
                source = null,
                addedAt = 2,
                lastOpenedAt = null,
            ),
        )

        val ready = repo.inspect(uri) as SettingsBackupInspection.Ready
        assertEquals(2, ready.preview.bookCount)
        assertEquals(2, ready.preview.booksInLibrary)
        assertEquals(0, ready.preview.booksToImport)

        val restored = repo.restore(ready.archiveId) as SettingsBackupRestoreResult.Restored
        assertEquals(0, restored.booksImported)
        assertEquals(2, restored.booksAlreadyPresent)
        assertFalse(File(context.filesDir, "books").exists() && File(context.filesDir, "books").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `restoring the same archive twice adds no book and no session`() = runTest {
        val epub = folder.newFile("twice.epub").also { writeEpub(it, title = "Twice Book", author = "Author") }
        val archivedUrl = "calibre:twice"
        db.bookDao().upsert(
            Book(
                url = archivedUrl,
                title = "Twice Book",
                author = "Author",
                coverPath = null,
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                downloadState = DownloadState.DOWNLOADED,
                localUri = Uri.fromFile(epub).toString(),
            ),
        )
        db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = archivedUrl,
                startedAt = 500,
                endedAt = 1000,
                lastCheckpointAt = 1000,
                durationMs = 500,
                startProgression = 0.0,
                endProgression = 0.2,
            ),
        )

        val app = AppSettingsRepository(store("twice-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("twice-reader.preferences_pb"))
        val repo = repository(app, reader, UserFontRepository(context, this, loadCheck = { true }))
        val uri = Uri.fromFile(File(folder.root, "twice.zip"))
        repo.exportTo(uri, includeSessions = true, bookUrls = setOf(archivedUrl))

        db.bookDao().deleteByUrls(listOf(archivedUrl))
        db.readingSessionDao().deleteForBook(archivedUrl)
        assertTrue(epub.delete())

        val first = restore(repo, uri) as SettingsBackupRestoreResult.Restored
        assertEquals(1, first.booksImported)
        assertEquals(1, first.sessionsAdded)

        val second = restore(repo, uri) as SettingsBackupRestoreResult.Restored
        assertEquals(0, second.booksImported)
        assertEquals(1, second.booksAlreadyPresent)
        assertEquals(0, second.sessionsAdded)
        assertEquals(1, second.sessionsAlreadyPresent)
        assertEquals(1, db.bookDao().allOnce().size)
        assertEquals(1, db.readingSessionDao().allOnce().size)
    }

    @Test
    fun `books and sessions are rejected in archives older than format seven`() = runTest {
        val repo = repository(
            AppSettingsRepository(store("v6-reject-app.preferences_pb")),
            ReaderPreferencesRepository(store("v6-reject-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val settings = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val base = mapOf(
            "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
            "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
            "companion.json" to """{"format":1,"application":"liseur","recaps":[]}""".toByteArray(),
        )
        val sessionsJson = """{"format":1,"application":"liseur","books":[]}""".toByteArray()
        val epubBytes = "epub-bytes".toByteArray()
        val sha = sha256(epubBytes)
        val booksJson = """{"format":1,"application":"liseur","books":[{"book_id":"b1","title":"T","author":null,"sha256":"$sha","size":${epubBytes.size}}]}""".toByteArray()

        val sessionsInV6 = archive("sessions-v6.zip", settings, base + ("sessions.json" to sessionsJson), format = 6)
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repo.inspect(Uri.fromFile(sessionsInV6)))

        val booksInV6 = archive("books-v6.zip", settings, base + ("books.json" to booksJson) + ("books/$sha.epub" to epubBytes), format = 6)
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repo.inspect(Uri.fromFile(booksInV6)))
    }

    @Test
    fun `a book entry whose name is not its digest is rejected`() = runTest {
        val repo = repository(
            AppSettingsRepository(store("bad-book-digest-app.preferences_pb")),
            ReaderPreferencesRepository(store("bad-book-digest-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val settings = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val epubBytes = "epub-bytes".toByteArray()
        val wrongSha = "f".repeat(64)
        val booksJson = """{"format":1,"application":"liseur","books":[{"book_id":"b1","title":"T","author":null,"sha256":"$wrongSha","size":${epubBytes.size}}]}""".toByteArray()
        val file = archive(
            "bad-book-digest.zip",
            settings,
            linkedMapOf(
                "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
                "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
                "companion.json" to """{"format":1,"application":"liseur","recaps":[]}""".toByteArray(),
                "books.json" to booksJson,
                "books/$wrongSha.epub" to epubBytes,
            ),
            format = 7,
        )
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repo.inspect(Uri.fromFile(file)))
    }

    @Test
    fun `a book file without a record, or a record without its file, is rejected`() = runTest {
        val repo = repository(
            AppSettingsRepository(store("orphan-book-app.preferences_pb")),
            ReaderPreferencesRepository(store("orphan-book-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val settings = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val epubBytes = "epub-bytes".toByteArray()
        val sha = sha256(epubBytes)
        val otherSha = "e".repeat(64)
        val base = linkedMapOf(
            "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
            "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
            "companion.json" to """{"format":1,"application":"liseur","recaps":[]}""".toByteArray(),
        )
        val recordWithoutFile = archive(
            "record-without-file.zip",
            settings,
            base + ("books.json" to """{"format":1,"application":"liseur","books":[{"book_id":"b1","title":"T","author":null,"sha256":"$sha","size":${epubBytes.size}}]}""".toByteArray()),
            format = 7,
        )
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repo.inspect(Uri.fromFile(recordWithoutFile)))

        val fileWithoutRecord = archive(
            "file-without-record.zip",
            settings,
            base + ("books.json" to """{"format":1,"application":"liseur","books":[{"book_id":"b1","title":"T","author":null,"sha256":"$otherSha","size":${epubBytes.size}}]}""".toByteArray()) +
                ("books/$sha.epub" to epubBytes),
            format = 7,
        )
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repo.inspect(Uri.fromFile(fileWithoutRecord)))
    }

    @Test
    fun `a book entry before books json is rejected`() = runTest {
        val repo = repository(
            AppSettingsRepository(store("order-book-app.preferences_pb")),
            ReaderPreferencesRepository(store("order-book-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val settings = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val epubBytes = "epub-bytes".toByteArray()
        val sha = sha256(epubBytes)
        val booksJson = """{"format":1,"application":"liseur","books":[{"book_id":"b1","title":"T","author":null,"sha256":"$sha","size":${epubBytes.size}}]}""".toByteArray()
        val file = archive(
            "book-before-index.zip",
            settings,
            linkedMapOf(
                "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
                "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
                "companion.json" to """{"format":1,"application":"liseur","recaps":[]}""".toByteArray(),
                "books/$sha.epub" to epubBytes,
                "books.json" to booksJson,
            ),
            format = 7,
        )
        assertEquals(SettingsBackupInspection.Failed(SettingsBackupFailure.INVALID_ARCHIVE), repo.inspect(Uri.fromFile(file)))
    }

    @Test
    fun `a book over the per-book limit is too large`() = runTest {
        val epub = folder.newFile("big.epub").also { writeEpub(it) }
        val url = Uri.fromFile(epub).toString()
        db.bookDao().upsert(book(url).copy(downloadState = DownloadState.DOWNLOADED, localUri = url))

        val repo = repository(
            AppSettingsRepository(store("limit-book-app.preferences_pb")),
            ReaderPreferencesRepository(store("limit-book-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
            bookLimits = BookArchiveLimits(maxBookBytes = 100L),
        )
        assertEquals(
            SettingsBackupPreparation.Failed(SettingsBackupFailure.TOO_LARGE),
            repo.prepareExport(bookUrls = setOf(url)),
        )

        // Also during inspection
        val fullRepo = repository(
            AppSettingsRepository(store("limit-book-full-app.preferences_pb")),
            ReaderPreferencesRepository(store("limit-book-full-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val zipUri = Uri.fromFile(File(folder.root, "big.zip"))
        fullRepo.exportTo(zipUri, bookUrls = setOf(url))
        assertEquals(
            SettingsBackupInspection.Failed(SettingsBackupFailure.TOO_LARGE),
            repo.inspect(zipUri),
        )
    }

    @Test
    fun `too little cache space for a missing book is a storage failure`() = runTest {
        val epub = folder.newFile("space.epub").also { writeEpub(it) }
        val url = "calibre:space"
        db.bookDao().upsert(book(url).copy(downloadState = DownloadState.DOWNLOADED, localUri = Uri.fromFile(epub).toString()))
        val fullRepo = repository(
            AppSettingsRepository(store("space-full-app.preferences_pb")),
            ReaderPreferencesRepository(store("space-full-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val zipUri = Uri.fromFile(File(folder.root, "space.zip"))
        fullRepo.exportTo(zipUri, bookUrls = setOf(url))

        db.bookDao().deleteByUrls(listOf(url))

        val lowSpaceRepo = repository(
            AppSettingsRepository(store("space-low-app.preferences_pb")),
            ReaderPreferencesRepository(store("space-low-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
            bookLimits = BookArchiveLimits(usableSpace = { 1024L }),
        )
        assertEquals(
            SettingsBackupInspection.Failed(SettingsBackupFailure.STORAGE),
            lowSpaceRepo.inspect(zipUri),
        )
    }

    @Test
    fun `matched books are hashed but not staged during inspection`() = runTest {
        val epub = folder.newFile("matched-not-staged.epub").also { writeEpub(it) }
        val sha = sha256(epub.readBytes())
        val url = Uri.fromFile(epub).toString()
        db.bookDao().upsert(book(url).copy(downloadState = DownloadState.DOWNLOADED, localUri = url))

        val repo = repository(
            AppSettingsRepository(store("nostage-app.preferences_pb")),
            ReaderPreferencesRepository(store("nostage-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val zipUri = Uri.fromFile(File(folder.root, "nostage.zip"))
        repo.exportTo(zipUri, bookUrls = setOf(url))

        val ready = repo.inspect(zipUri) as SettingsBackupInspection.Ready
        assertEquals(1, ready.preview.bookCount)
        assertEquals(1, ready.preview.booksInLibrary)
        assertEquals(0, ready.preview.booksToImport)

        val stagingDirs = context.cacheDir.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(ready.archiveId) }
        val indexJson = JSONObject(File(stagingDirs.single(), "inspection.json").readText()).getJSONArray("entries")
        val stagedPaths = List(indexJson.length()) { indexJson.getJSONObject(it).getString("path") }
        assertTrue("books.json" in stagedPaths)
        assertFalse("books/$sha.epub" in stagedPaths)
        repo.discardInspection(ready.archiveId)
    }

    @Test
    fun `prepared archive lists books without holding their bytes and the destination receives them`() = runTest {
        val epub = folder.newFile("streamed.epub").also { writeEpub(it) }
        val sha = sha256(epub.readBytes())
        val url = Uri.fromFile(epub).toString()
        db.bookDao().upsert(book(url).copy(downloadState = DownloadState.DOWNLOADED, localUri = url))

        val repo = repository(
            AppSettingsRepository(store("stream-app.preferences_pb")),
            ReaderPreferencesRepository(store("stream-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val prepared = (repo.prepareExport(bookUrls = setOf(url)) as SettingsBackupPreparation.Prepared).export
        assertEquals(1, prepared.books)
        assertEquals(epub.length(), prepared.bookBytes)

        ZipFile(prepared.file).use { zip ->
            assertTrue(zip.getEntry("books.json") != null)
            assertEquals(null, zip.getEntry("books/$sha.epub"))
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            val entries = manifest.getJSONArray("entries")
            val paths = List(entries.length()) { entries.getJSONObject(it).getString("path") }
            assertTrue("books/$sha.epub" in paths)
        }

        val target = File(folder.root, "streamed-out.zip")
        val exported = repo.writeExport(prepared, Uri.fromFile(target))
        assertEquals(
            SettingsBackupExportResult.Exported(0, books = 1, bookBytes = epub.length()),
            exported,
        )
        ZipFile(target).use { zip ->
            val bookEntry = zip.getEntry("books/$sha.epub")
            assertTrue(bookEntry != null)
            assertEquals(epub.readBytes().toList(), zip.getInputStream(bookEntry).readBytes().toList())
        }
    }

    @Test
    fun `a book changed between preparing and saving fails the save and removes the destination`() = runTest {
        val epub = folder.newFile("changing.epub").also { writeEpub(it) }
        val url = Uri.fromFile(epub).toString()
        db.bookDao().upsert(book(url).copy(downloadState = DownloadState.DOWNLOADED, localUri = url))

        val repo = repository(
            AppSettingsRepository(store("changed-app.preferences_pb")),
            ReaderPreferencesRepository(store("changed-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val prepared = (repo.prepareExport(bookUrls = setOf(url)) as SettingsBackupPreparation.Prepared).export
        epub.writeText("tampered bytes after prepare")

        val docs = BookExportRepositoryTest.FakeDocs
        docs.directory = folder.newFolder("changed-book-save")
        docs.names.clear()
        docs.deleted.clear()
        docs.failWrites.clear()
        docs.names["backup"] = "backup.zip"
        File(docs.directory, "backup").writeText("partial")
        org.robolectric.Robolectric.buildContentProvider(BookExportRepositoryTest.FakeDocs::class.java)
            .create("test.bookexport.documents")
        val target = DocumentsContract.buildDocumentUri("test.bookexport.documents", "backup")

        assertEquals(
            SettingsBackupExportResult.Failed(SettingsBackupFailure.FILE_ACCESS),
            repo.writeExport(prepared, target),
        )
        assertFalse(prepared.file.exists())
        assertEquals(listOf("backup"), docs.deleted)
    }

    @Test
    fun `an unreadable selected book is skipped and counted`() = runTest {
        val missingFile = File(folder.root, "does-not-exist.epub")
        val url = Uri.fromFile(missingFile).toString()
        db.bookDao().upsert(book(url).copy(downloadState = DownloadState.DOWNLOADED, localUri = url))

        val repo = repository(
            AppSettingsRepository(store("skip-app.preferences_pb")),
            ReaderPreferencesRepository(store("skip-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val prepared = (repo.prepareExport(bookUrls = setOf(url)) as SettingsBackupPreparation.Prepared).export
        assertEquals(0, prepared.books)
        assertEquals(1, prepared.booksSkipped)
        ZipFile(prepared.file).use { zip ->
            assertEquals(null, zip.getEntry("books.json"))
        }
        repo.discardPreparedExport(prepared)
    }

    @Test
    fun `sessions export carries only closed sessions without ids or upload state`() = runTest {
        db.bookDao().upsert(book("b1"))
        db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = "b1",
                startedAt = 100,
                endedAt = 200,
                lastCheckpointAt = 200,
                durationMs = 90,
                idleMs = 10,
                startProgression = 0.1,
                endProgression = 0.2,
                uploadedAt = 999,
            ),
        )
        db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = "b1",
                startedAt = 300,
                endedAt = null,
                lastCheckpointAt = 350,
                durationMs = 50,
            ),
        )

        val repo = repository(
            AppSettingsRepository(store("sessions-export-app.preferences_pb")),
            ReaderPreferencesRepository(store("sessions-export-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        val zipFile = File(folder.root, "sessions-export.zip")
        val result = repo.exportTo(Uri.fromFile(zipFile), includeSessions = true)
        assertEquals(SettingsBackupExportResult.Exported(0, sessions = 1), result)

        ZipFile(zipFile).use { zip ->
            val text = zip.getInputStream(zip.getEntry("sessions.json")).bufferedReader().readText()
            assertFalse(text.contains("uploaded_at"))
            assertFalse(text.contains("last_checkpoint_at"))
            val books = JSONObject(text).getJSONArray("books")
            assertEquals(1, books.length())
            val sessions = books.getJSONObject(0).getJSONArray("sessions")
            assertEquals(1, sessions.length())
            val s = sessions.getJSONObject(0)
            assertEquals(100L, s.getLong("started_at"))
            assertEquals(200L, s.getLong("ended_at"))
            assertEquals(90L, s.getLong("duration_ms"))
            assertEquals(10L, s.getLong("idle_ms"))
            assertFalse(s.has("id"))
        }
    }

    @Test
    fun `restored sessions are marked imported and dedupe on book and start`() = runTest {
        val repo = repository(
            AppSettingsRepository(store("sessions-dedupe-app.preferences_pb")),
            ReaderPreferencesRepository(store("sessions-dedupe-reader.preferences_pb")),
            UserFontRepository(context, this, loadCheck = { true }),
        )
        db.bookDao().upsert(book("b1"))
        db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = "b1",
                startedAt = 100,
                endedAt = 200,
                lastCheckpointAt = 200,
                durationMs = 80,
            ),
        )
        val settings = JSONObject().put("app", JSONObject()).put("reader", JSONObject())
        val sessionsJson = """{
            "format":1,
            "application":"liseur",
            "books":[{
                "book_id":"b1",
                "title":"A book",
                "author":"An author",
                "sessions":[
                    {"started_at":100,"ended_at":250,"duration_ms":100},
                    {"started_at":500,"ended_at":600,"duration_ms":90},
                    {"started_at":500,"ended_at":700,"duration_ms":150}
                ]
            }]
        }""".toByteArray()
        val file = archive(
            "sessions-dedupe.zip",
            settings,
            mapOf(
                "annotations.json" to encodeAnnotationBackup(emptyList()).toByteArray(),
                "positions.json" to """{"format":1,"application":"liseur","positions":[]}""".toByteArray(),
                "companion.json" to """{"format":1,"application":"liseur","recaps":[]}""".toByteArray(),
                "sessions.json" to sessionsJson,
            ),
            format = 7,
        )
        val restored = restore(repo, Uri.fromFile(file)) as SettingsBackupRestoreResult.Restored
        assertEquals(1, restored.sessionsAdded)
        assertEquals(2, restored.sessionsAlreadyPresent)
        val all = db.readingSessionDao().allOnce().sortedBy { it.startedAt }
        assertEquals(2, all.size)
        assertEquals(80L, all[0].durationMs)
        assertEquals(null, all[0].importedAt)
        assertEquals(90L, all[1].durationMs)
        assertTrue(all[1].importedAt != null)
    }

    @Test
    fun `archive limits stay under four GiB without ZIP64`() {
        val maxArchiveBytes = SettingsBackupRepository.MAX_TOTAL_BYTES + SettingsBackupRepository.MAX_BOOKS_TOTAL_BYTES
        assertTrue(maxArchiveBytes < 4L * 1024 * 1024 * 1024)
        assertTrue(SettingsBackupRepository.MAX_ARCHIVE_ENTRIES < 65_535)
    }

    private fun writeEpub(
        target: File,
        title: String = "A Test Book",
        author: String = "An Author",
        id: String = "urn:uuid:0f6a7e2c-1111-4222-8333-444455556666",
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
                    <dc:identifier id="id">$id</dc:identifier>
                    <dc:creator>$author</dc:creator>
                    <dc:title>$title</dc:title>
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

    private fun repository(
        app: AppSettingsRepository,
        reader: ReaderPreferencesRepository,
        fonts: UserFontRepository,
        bookLimits: BookArchiveLimits = BookArchiveLimits(),
    ) = SettingsBackupRepository(
        context, app, reader, fonts,
        AnnotationBackupRepository(context, db.annotationDao(), db.bookDao(), requested::add),
        ReadingPositionBackupRepository(
            db.readingProgressDao(),
            db.bookDao(),
            requested::add,
            inTransaction = { work -> db.withTransaction { work() } },
        ),
        companion = companionBackup(),
        sessions = ReadingSessionBackupRepository(
            db.readingSessionDao(),
            db.bookDao(),
            inTransaction = { work -> db.withTransaction { work() } },
        ),
        books = BookBackupRepository(context, db.bookDao(), localLibrary::importBackupBook),
        bookLimits = bookLimits,
    )

    private suspend fun restore(
        repository: SettingsBackupRepository,
        uri: Uri,
    ): SettingsBackupRestoreResult =
        when (val inspection = repository.inspect(uri)) {
            is SettingsBackupInspection.Ready -> repository.restore(inspection.archiveId)
            is SettingsBackupInspection.Failed ->
                SettingsBackupRestoreResult.Failed(inspection.failure)
        }

    private fun writeFont(bytes: ByteArray): File =
        File(folder.root, "custom-font.ttf").apply { writeBytes(bytes) }

    private fun archive(
        name: String,
        settings: JSONObject,
        additionalEntries: Map<String, ByteArray> = emptyMap(),
        application: String = "liseur",
        format: Any = 1,
        manifestEntryOverrides: Map<String, Any> = emptyMap(),
    ): File {
        val entries = linkedMapOf("settings.json" to settings.toString().toByteArray())
        entries.putAll(additionalEntries)
        val manifest = JSONObject()
            .put("format", format)
            .put("application", application)
            .put(
                "entries",
                org.json.JSONArray().also { manifestEntries ->
                    entries.forEach { (path, bytes) ->
                        manifestEntries.put(
                            JSONObject()
                                .put("path", path)
                                .put("size", bytes.size)
                                .put("sha256", sha256(bytes))
                                .apply {
                                    manifestEntryOverrides.forEach { (key, value) -> put(key, value) }
                                },
                        )
                    }
                },
            )
        return File(folder.root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun store(name: String) =
        PreferenceDataStoreFactory.create { folder.newFile(name) }

    private class FailingDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = delegate.data

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw IOException("simulated store failure")
    }
}
