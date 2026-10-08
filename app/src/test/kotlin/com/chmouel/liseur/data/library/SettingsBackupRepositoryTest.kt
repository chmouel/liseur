package com.chmouel.liseur.data.library

import android.content.Context
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
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.domain.BackedUpBook
import com.chmouel.liseur.domain.encodeAnnotationBackup
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.VoicePreference
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.PageTurnStyle
import com.chmouel.liseur.data.settings.ThemeMode
import com.chmouel.liseur.data.settings.UserFontRepository
import com.chmouel.liseur.data.settings.ImportResult as FontImportResult
import com.chmouel.liseur.data.settings.fonts.SfntFixtures
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    private val requested = mutableListOf<String>()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
    }

    @After
    fun close() {
        db.close()
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
        sourceReader.setFontSize(1.75)
        sourceReader.setHyphens(true)

        val backup = File(folder.root, "settings.zip")
        val uri = Uri.fromFile(backup)
        val source = repository(sourceApp, sourceReader, fonts)

        assertEquals(SettingsBackupExportResult.Exported(0), source.exportTo(uri))
        val inspected = source.inspect(uri) as SettingsBackupInspection.Ready
        assertEquals(SettingsBackupPreview(settingCount = 3, fontCount = 0), inspected.preview)
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
        assertEquals(1.75, restoredReader.prefs.first().fontSize, 0.0)
        assertEquals(true, restoredReader.prefs.first().hyphens)
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
            JSONObject().put(
                "highlight_tints_offered",
                org.json.JSONArray().put("MAGENTA"),
            ),
            JSONObject().put("highlight_tint_default", "MAGENTA"),
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
            SettingsBackupRestoreResult.PartiallyRestored,
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
            assertEquals(3, manifest.getInt("format"))
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
    fun `version one settings backups preserve destination annotations and positions`() = runTest {
        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()
        val app = AppSettingsRepository(store("version-one-app.preferences_pb"))
        val reader = ReaderPreferencesRepository(store("version-one-reader.preferences_pb"))
        val mark = BookAnnotation("existing", "local-book", AnnotationKind.BOOK_NOTE.name, "", note = "Keep me", createdAt = 123)
        db.annotationDao().upsert(mark)
        val payload = JSONObject().put("app", JSONObject().put("theme_mode", "dark")).put("reader", JSONObject())
        val position = ReadingProgress("local-book", "{}", 0.4, updatedAt = 12)
        db.readingProgressDao().upsert(position)
        val file = archive("version-one.zip", payload)
        assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0), restore(repository(app, reader, fonts), Uri.fromFile(file)))
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
        )
        assertEquals(SettingsBackupRestoreResult.PartiallyRestored, restore(repository, Uri.fromFile(file)))
        assertEquals(ThemeMode.DARK, app.settings.first().themeMode)
        assertTrue(db.annotationDao().all().isEmpty())
        assertTrue(requested.isEmpty())
    }

    private fun book(url: String) = Book(
        url = url, title = "A book", author = "An author", coverPath = null, source = null,
        addedAt = 0, lastOpenedAt = null,
    )

    private fun repository(
        app: AppSettingsRepository,
        reader: ReaderPreferencesRepository,
        fonts: UserFontRepository,
    ) = SettingsBackupRepository(
        context, app, reader, fonts,
        AnnotationBackupRepository(context, db.annotationDao(), db.bookDao(), requested::add),
        ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requested::add),
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
