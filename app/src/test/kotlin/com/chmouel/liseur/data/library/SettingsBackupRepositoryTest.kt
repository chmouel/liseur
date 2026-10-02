package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.ThemeMode
import com.chmouel.liseur.data.settings.UserFontRepository
import java.io.File
import java.util.zip.ZipFile
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

        assertEquals(SettingsBackupResult.Exported(0), source.exportTo(uri))
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

        assertEquals(SettingsBackupResult.Restored(0, 0, 0), restored.restore(uri))
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
        assertTrue(repository.restore(Uri.fromFile(backup)) is SettingsBackupResult.Failed)
        assertEquals(ThemeMode.LIGHT, app.settings.first().themeMode)
    }

    private fun repository(
        app: AppSettingsRepository,
        reader: ReaderPreferencesRepository,
        fonts: UserFontRepository,
    ) = SettingsBackupRepository(context, app, reader, fonts)

    private fun store(name: String) =
        PreferenceDataStoreFactory.create { folder.newFile(name) }
}
