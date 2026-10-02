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
        assertEquals(
            SettingsBackupInspection.Ready(SettingsBackupPreview(settingCount = 3, fontCount = 0)),
            source.inspect(uri),
        )

        val settings = ZipFile(backup).use { zip ->
            zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().use {
                JSONObject(it.readText())
            }
        }
        assertFalse(settings.getJSONObject("app").has("account_token"))

        val restoredApp = AppSettingsRepository(store("restored-app.preferences_pb"))
        val restoredReader = ReaderPreferencesRepository(store("restored-reader.preferences_pb"))
        val restored = repository(restoredApp, restoredReader, fonts)

        assertEquals(SettingsBackupRestoreResult.Restored(0, 0, 0), restored.restore(uri))
        assertEquals(ThemeMode.DARK, restoredApp.settings.first().themeMode)
        assertEquals(1.75, restoredReader.prefs.first().fontSize, 0.0)
        assertEquals(true, restoredReader.prefs.first().hyphens)
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
        assertTrue(repository.restore(Uri.fromFile(backup)) is SettingsBackupRestoreResult.Failed)
        assertEquals(ThemeMode.LIGHT, app.settings.first().themeMode)
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
            repository.restore(Uri.fromFile(backup)),
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
                repository.restore(Uri.fromFile(backup)),
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
            repository(app, reader, fonts).restore(Uri.fromFile(backup)),
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
