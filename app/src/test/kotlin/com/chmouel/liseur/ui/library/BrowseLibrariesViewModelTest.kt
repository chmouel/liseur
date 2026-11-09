package com.chmouel.liseur.ui.library

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.opds.FreeCatalog
import com.chmouel.liseur.data.remote.BrowseCatalogRepository
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.data.remote.ServerCapabilities
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.ServerSetup
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the free-catalog chooser does with a tap.
 *
 * The catalog is a network away, so the interesting behaviour is all
 * around the wait: a second tap, a failure, a dismissal. Each test here
 * pins one of those, because each one is a way to end up with two
 * connections to the same catalog or none at all.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseLibrariesViewModelTest {

    private lateinit var db: LiseurDatabase
    private lateinit var setup: FakeSetup
    private lateinit var models: ViewModelStore
    private var modelCount = 0

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
        }
        setup = FakeSetup()
        models = ViewModelStore()
    }

    @After
    fun close() {
        models.clear()
        db.close()
    }

    private fun model(): BrowseLibrariesViewModel {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val removal = BookRemoval(
            db.bookDao(),
            db.readingSessionDao(),
            db.syncPeerStateDao(),
            db.workIdentityDao(),
            db.readingProgressDao(),
            db.annotationDao(),
            db.annotationSyncDao(),
        )
        val model = BrowseLibrariesViewModel(
            BrowseCatalogRepository(
                servers = db.remoteServerDao(),
                books = db.bookDao(),
                orbitBindings = db.bookOrbitBindingDao(),
                removal = removal,
                downloads = BookDownloadRepository(
                    context,
                    db.bookDao(),
                    removal,
                    CoroutineScope(Dispatchers.Unconfined),
                ),
                router = RemoteRouter(
                    serverDao = db.remoteServerDao(),
                    catalogs = emptyMap(),
                    files = emptyMap(),
                    positions = emptyMap(),
                ),
                setups = mapOf(ServerKind.CUSTOM to setup),
                orbitCatalog = { error("not a BookOrbit catalog") },
                inTransaction = { it() },
                metadata = db.bookMetadataDao(),
            ),
        )
        // Kept so the view model's own coroutines are cancelled before the
        // test dispatcher is uninstalled; one still collecting Room flows
        // would race the reset.
        models.put("browse-${modelCount++}", model)
        return model
    }

    private fun withMain(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    /** Waits for the saved list the chooser matches against. */
    private suspend fun ready(model: BrowseLibrariesViewModel) {
        model.state.first { it.savedLoaded }
    }

    @Test
    fun `a catalog already saved is reopened instead of probed again`() = withMain {
        db.remoteServerDao().upsert(saved(FreeCatalog.MANYBOOKS.address))
        val model = model()
        ready(model)
        model.openFreeCatalogs()
        model.connectPreset(FreeCatalog.MANYBOOKS)
        runCurrent()

        assertEquals(0, setup.calls)
        assertEquals(2L, model.state.value.serverId)
        assertFalse(model.state.value.chooserOpen)
        assertNull(model.state.value.connectingPreset)
        assertEquals(1, db.remoteServerDao().browseServers().size)
    }

    @Test
    fun `a second tap while the first is still connecting starts nothing`() = withMain {
        setup.gate = CompletableDeferred()
        val model = model()
        ready(model)
        model.openFreeCatalogs()
        model.connectPreset(FreeCatalog.GUTENBERG)
        runCurrent()
        model.connectPreset(FreeCatalog.GUTENBERG)
        model.connectPreset(FreeCatalog.TEXTOS_INFO)
        setup.gate?.complete(Unit)
        model.state.first { it.serverId != null }

        assertEquals(1, setup.calls)
        assertEquals(FreeCatalog.GUTENBERG.address, db.remoteServerDao().browseServers().single().catalogUrl)
        assertFalse(model.state.value.chooserOpen)
    }

    @Test
    fun `a failed catalog keeps the chooser open and retries in place`() = withMain {
        setup.result = SetupResult.Failure(SetupFailure.Unreachable("No answer", httpMayWork = false))
        val model = model()
        ready(model)
        model.openFreeCatalogs()
        model.connectPreset(FreeCatalog.TEXTOS_INFO)
        runCurrent()

        assertTrue(model.state.value.chooserOpen)
        assertEquals(FreeCatalog.TEXTOS_INFO, model.state.value.presetFailure)
        assertNull(model.state.value.serverId)
        assertTrue(db.remoteServerDao().browseServers().isEmpty())

        setup.result = SetupResult.Success(capabilities(FreeCatalog.TEXTOS_INFO.address))
        model.connectPreset(FreeCatalog.TEXTOS_INFO)
        model.state.first { it.serverId != null }

        assertFalse(model.state.value.chooserOpen)
        assertNull(model.state.value.presetFailure)
        val saved = db.remoteServerDao().browseServers().single()
        assertEquals(FreeCatalog.TEXTOS_INFO.address, saved.catalogUrl)
        assertEquals(saved.id, model.state.value.serverId)
    }

    @Test
    fun `dismissing the chooser cancels the connection it started`() = withMain {
        setup.gate = CompletableDeferred()
        val model = model()
        ready(model)
        model.openFreeCatalogs()
        model.connectPreset(FreeCatalog.GUTENBERG)
        runCurrent()
        model.closeChooser()
        setup.gate?.complete(Unit)
        runCurrent()

        assertFalse(model.state.value.chooserOpen)
        assertNull(model.state.value.connectingPreset)
        assertNull(model.state.value.serverId)
        assertTrue(db.remoteServerDao().browseServers().isEmpty())
        assertFalse(model.state.value.canGoBack)
    }

    @Test
    fun `a chooser opened from the library leaves it when dismissed empty`() = withMain {
        val model = model()
        ready(model)
        model.openFreeCatalogs(fromLibrary = true)
        assertTrue(model.state.value.chooserFromLibrary)
        model.closeChooser()

        assertTrue(model.state.value.leaveForLibrary)
        model.leaveForLibraryHandled()
        assertFalse(model.state.value.leaveForLibrary)
    }

    @Test
    fun `the screen's own chooser stays on the saved catalogs when dismissed`() = withMain {
        val model = model()
        ready(model)
        model.openFreeCatalogs()
        model.closeChooser()

        assertFalse(model.state.value.leaveForLibrary)
    }

    @Test
    fun `picking a catalog from the library chooser does not leave the browser`() = withMain {
        db.remoteServerDao().upsert(saved(FreeCatalog.GALLICA.address))
        val model = model()
        ready(model)
        model.openFreeCatalogs(fromLibrary = true)
        model.connectPreset(FreeCatalog.GALLICA)
        runCurrent()

        assertFalse(model.state.value.leaveForLibrary)
        assertEquals(2L, model.state.value.serverId)
    }

    @Test
    fun `a catalog picked from the form opens instead of leaving the form behind`() = withMain {
        db.remoteServerDao().upsert(saved(FreeCatalog.GUTENBERG.address))
        val model = model()
        ready(model)
        model.openForm()
        model.openFreeCatalogs()
        model.connectPreset(FreeCatalog.GUTENBERG)
        runCurrent()

        assertFalse(model.state.value.formOpen)
        assertFalse(model.state.value.chooserOpen)
        assertEquals(2L, model.state.value.serverId)
    }

    @Test
    fun `back closes the chooser before the form under it`() = withMain {
        val model = model()
        ready(model)
        model.openForm()
        model.openFreeCatalogs()

        assertTrue(model.back())
        assertFalse(model.state.value.chooserOpen)
        assertTrue(model.state.value.formOpen)

        assertTrue(model.back())
        assertFalse(model.state.value.formOpen)
    }

    private fun saved(address: String) = RemoteServer(
        id = 2L,
        kind = ServerKind.CUSTOM,
        baseUrl = address,
        username = "",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 0L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
        catalogUrl = address,
    )

    private fun capabilities(address: String) = ServerCapabilities(
        baseUrl = address,
        canDownload = true,
        accountId = null,
        displayName = "free",
        hasBooks = true,
    )

    /**
     * Answers at once, unless a test sets a [gate] so it can decide what
     * happens while the connection is still in flight.
     */
    private class FakeSetup : ServerSetup {
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0
        var result: SetupResult = SetupResult.Success(
            ServerCapabilities(
                baseUrl = FreeCatalog.GUTENBERG.address,
                canDownload = true,
                accountId = null,
                displayName = "free",
                hasBooks = true,
            ),
        )

        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult {
            calls++
            gate?.await()
            return result
        }
    }
}
