package com.chmouel.liseur.ui.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.companion.CompanionDatabase
import com.chmouel.liseur.companion.CompanionStore
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.AnnotationBackupRepository
import com.chmouel.liseur.data.library.BookBackupRepository
import com.chmouel.liseur.data.library.BookExportRepository
import com.chmouel.liseur.data.library.CompanionBackupRepository
import com.chmouel.liseur.data.library.ImportResult
import com.chmouel.liseur.data.library.ReadingPositionBackupRepository
import com.chmouel.liseur.data.library.ReadingSessionBackupRepository
import com.chmouel.liseur.data.library.SettingsBackupFailure
import com.chmouel.liseur.data.library.SettingsBackupRepository
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.UserFontRepository
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The archive the backup screen builds before the destination picker opens.
 *
 * The picked destination arrives in a callback that can be replayed after
 * recreation, while the archive itself only lives in the cache. These cover
 * what the screen promises around that handover: a request the picker can
 * open, a cancelled picker that leaves nothing behind, and a copy that fails
 * cleanly when the archive is no longer there.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsZipBackupUiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LiseurDatabase
    private lateinit var companionDb: CompanionDatabase
    private lateinit var companionStore: CompanionStore
    private val models = mutableListOf<SettingsBackupViewModel>()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        companionDb = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java).build()
        companionStore = CompanionStore(companionDb, present = { db.bookDao().presentUrls(it) })
    }

    @After
    fun close() {
        db.close()
        companionDb.close()
    }

    @Test
    fun `a prepared export asks for a destination and reports it launched`() = scenario {
        val model = model()

        model.prepareExport(null, includeTranslations = true)
        await { model.state.value.saveRequest != null }

        val name = model.state.value.saveRequest
        assertNotNull(name)
        assertTrue(name!!.startsWith("liseur-backup-"))
        await { !model.state.value.busy }
        assertEquals(1, preparedArchives().size)
        model.saveLaunched(name)
        assertNull(model.state.value.saveRequest)
    }

    @Test
    fun `a cancelled picker deletes the prepared archive`() = scenario {
        val model = model()

        model.prepareExport(null, includeTranslations = true)
        await { model.state.value.saveRequest != null }
        val prepared = preparedArchives().single()
        model.saveLaunched(model.state.value.saveRequest!!)

        model.writeExport(null)

        await { !prepared.exists() }
        assertFalse(prepared.exists())
        assertTrue(preparedArchives().isEmpty())
    }

    @Test
    fun `a second preparation discards the archive the first one left waiting`() = scenario {
        val model = model()

        model.prepareExport(null, includeTranslations = true)
        await { model.state.value.saveRequest != null }
        val first = preparedArchives().single()
        model.saveLaunched(model.state.value.saveRequest!!)

        model.prepareExport(null, includeTranslations = true)
        await { model.state.value.saveRequest != null }
        val second = preparedArchives().single()

        assertFalse(first.exists())
        assertEquals(listOf(second), preparedArchives())
    }

    @Test
    fun `a picked destination facing a missing archive fails without reading it`() = scenario {
        val model = model()

        model.prepareExport(null, includeTranslations = true)
        await { model.state.value.saveRequest != null }
        val prepared = preparedArchives().single()
        assertTrue(prepared.delete())
        model.saveLaunched(model.state.value.saveRequest!!)

        val target = File(folder.root, "destination.zip")
        model.writeExport(Uri.fromFile(target))

        await { model.state.value.status != null }
        assertEquals(SettingsBackupUiStatus.Failed(SettingsBackupFailure.FILE_ACCESS), model.state.value.status)
        assertFalse(target.exists())
        await { !model.state.value.busy }
    }

    @Test
    fun `a saved archive name the app did not generate is never deleted`() = scenario {
        val victim = File(context.filesDir, "settings-export-1.zip").apply { writeText("keep") }
        val cached = File(context.cacheDir, "keep.zip").apply { writeText("keep") }
        listOf(victim.absolutePath, "../files/${victim.name}", cached.name).forEach { name ->
            val model = model(SavedStateHandle(mapOf("prepared_export_name" to name)))

            model.close()
            await { !model.state.value.busy }
        }

        assertTrue(victim.exists())
        assertTrue(cached.exists())
    }

    @Test
    fun `a prepared archive survives the screen being destroyed behind the picker`() = scenario {
        val savedState = SavedStateHandle()
        val store = ViewModelStore()
        val first = ViewModelProvider(store, viewModelFactory { initializer { model(savedState) } })[SettingsBackupViewModel::class.java]
        first.prepareExport(null, includeTranslations = true)
        await { first.state.value.saveRequest != null }
        first.saveLaunched(first.state.value.saveRequest!!)

        store.clear()
        val restored = model(savedState)
        val target = File(folder.root, "destination.zip")
        restored.writeExport(Uri.fromFile(target))

        await { restored.state.value.status != null }
        assertTrue(restored.state.value.status is SettingsBackupUiStatus.Exported)
        assertTrue(target.length() > 0)
        await { preparedArchives().isEmpty() }
    }

    @Test
    fun `malformed saved archive values are ignored instead of crashing`() = scenario {
        val model = model(SavedStateHandle(mapOf("prepared_export_name" to 7, "prepared_export_fonts" to "many")))

        model.close()
        await { !model.state.value.busy }
    }

    @Test
    fun `book selection is passed to the export and cancelling stops it`() = scenario {
        val epub1 = folder.newFile("book1.epub").apply { writeBytes("epub-content-1".toByteArray()) }
        val epub2 = folder.newFile("book2.epub").apply { writeBytes("epub-content-2".toByteArray()) }
        val url1 = Uri.fromFile(epub1).toString()
        val url2 = Uri.fromFile(epub2).toString()
        db.bookDao().upsert(Book(url = url1, title = "Book 1", author = "Author", coverPath = null, source = null, addedAt = 1, lastOpenedAt = null, downloadState = DownloadState.DOWNLOADED, localUri = url1))
        db.bookDao().upsert(Book(url = url2, title = "Book 2", author = "Author", coverPath = null, source = null, addedAt = 2, lastOpenedAt = null, downloadState = DownloadState.DOWNLOADED, localUri = url2))

        val model = model()
        model.setIncludeBooks(true)
        await { model.state.value.bookCandidates != null }
        assertEquals(setOf(url1, url2), model.state.value.selectedBooks)

        // Deselect book 2
        model.toggleBook(url2)
        assertEquals(setOf(url1), model.state.value.selectedBooks)

        model.prepareExport(null, includeTranslations = true)
        await { model.state.value.saveRequest != null }
        model.saveLaunched(model.state.value.saveRequest!!)
        val target = File(folder.root, "selected-books.zip")
        model.writeExport(Uri.fromFile(target))
        await { model.state.value.status != null }

        val exported = model.state.value.status as SettingsBackupUiStatus.Exported
        assertEquals(1, exported.books)
        val sha1 = MessageDigest.getInstance("SHA-256").digest(epub1.readBytes()).joinToString("") { "%02x".format(it) }
        val sha2 = MessageDigest.getInstance("SHA-256").digest(epub2.readBytes()).joinToString("") { "%02x".format(it) }
        ZipFile(target).use { zip ->
            assertNotNull(zip.getEntry("books/$sha1.epub"))
            assertNull(zip.getEntry("books/$sha2.epub"))
        }

        // Also verify cancelArchive stops an active operation when archiveProgress is present
        model.prepareExport(null, includeTranslations = true)
        await { model.state.value.saveRequest != null }
        val prepared = preparedArchives().single()
        model.cancelArchive()
        model.writeExport(null)
        await { !prepared.exists() }
    }

    @Test
    fun `a restored prepared archive with books is written even when saved counts are missing`() = scenario {
        val epub = folder.newFile("survive.epub").apply { writeBytes("surviving-epub-bytes".toByteArray()) }
        val url = Uri.fromFile(epub).toString()
        db.bookDao().upsert(Book(url = url, title = "Survive", author = "Author", coverPath = null, source = null, addedAt = 1, lastOpenedAt = null, downloadState = DownloadState.DOWNLOADED, localUri = url))

        val savedState = SavedStateHandle()
        val first = model(savedState)
        first.setIncludeBooks(true)
        await { first.state.value.bookCandidates != null }
        first.prepareExport(null, includeTranslations = true)
        await { first.state.value.saveRequest != null }
        first.saveLaunched(first.state.value.saveRequest!!)

        // Simulate saved counts being missing, leaving only prepared_export_name
        val preparedName = checkNotNull(savedState.get<String>("prepared_export_name"))
        val strippedState = SavedStateHandle(mapOf("prepared_export_name" to preparedName))
        val restored = model(strippedState)
        val target = File(folder.root, "survived-books.zip")
        restored.writeExport(Uri.fromFile(target))

        await { restored.state.value.status != null }
        val status = restored.state.value.status as SettingsBackupUiStatus.Exported
        assertEquals(1, status.books)
        val sha = MessageDigest.getInstance("SHA-256").digest(epub.readBytes()).joinToString("") { "%02x".format(it) }
        ZipFile(target).use { zip ->
            assertNotNull(zip.getEntry("books.json"))
            assertNotNull(zip.getEntry("books/$sha.epub"))
        }
        await { preparedArchives().isEmpty() }
    }

    /** Runs [body] with Main on the unconfined test dispatcher, the way the app's ViewModels see it. */
    private fun scenario(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            // A model still collecting would hand Room's IO-thread emissions to a reset Main.
            models.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.model(savedState: SavedStateHandle = SavedStateHandle()): SettingsBackupViewModel {
        val repository = SettingsBackupRepository(
            context,
            AppSettingsRepository(PreferenceDataStoreFactory.create { folder.newFile("zip-app-${System.nanoTime()}.preferences_pb") }),
            ReaderPreferencesRepository(PreferenceDataStoreFactory.create { folder.newFile("zip-reader-${System.nanoTime()}.preferences_pb") }),
            UserFontRepository(context, this, loadCheck = { true }),
            AnnotationBackupRepository(context, db.annotationDao(), db.bookDao()),
            ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao()),
            companion = CompanionBackupRepository({ companionStore }, db.bookDao(), storeExists = { true }),
            sessions = ReadingSessionBackupRepository(db.readingSessionDao(), db.bookDao()),
            books = BookBackupRepository(context, db.bookDao()) { _, _ -> ImportResult.Failed },
        )
        return SettingsBackupViewModel(
            repository,
            BookExportRepository(context, db.bookDao()),
            savedState,
        ).also { models += it }
    }

    /** Waits for work the screen handed to a background dispatcher; the test scheduler drives Main only. */
    private suspend fun await(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        assertTrue(condition())
    }

    private fun preparedArchives() = context.cacheDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith("settings-export-") }
}
