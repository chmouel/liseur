package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.ReaderPrefs
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
        archive("preview-replacement.zip", replacement).copyTo(backup, overwrite = true)
        assertEquals(
            SettingsBackupRestoreResult.Restored(0, 0, 0),
            repository.restore(inspected.archiveId),
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

    private fun repository(
        app: AppSettingsRepository,
        reader: ReaderPreferencesRepository,
        fonts: UserFontRepository,
    ) = SettingsBackupRepository(context, app, reader, fonts)

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
    ): File {
        val entries = linkedMapOf("settings.json" to settings.toString().toByteArray())
        entries.putAll(additionalEntries)
        val manifest = JSONObject()
            .put("format", 1)
            .put("application", "liseur")
            .put(
                "entries",
                org.json.JSONArray().also { manifestEntries ->
                    entries.forEach { (path, bytes) ->
                        manifestEntries.put(
                            JSONObject()
                                .put("path", path)
                                .put("size", bytes.size)
                                .put("sha256", sha256(bytes)),
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
