package com.chmouel.liseur

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import com.chmouel.liseur.data.library.BackupResult
import com.chmouel.liseur.domain.ResumeCandidate
import com.chmouel.liseur.domain.shouldResume
import com.chmouel.liseur.reader.ReaderActivity
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.listSaver
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.ui.stats.BookReadingStatsScreen
import com.chmouel.liseur.ui.stats.ReadingStatsScreen
import com.chmouel.liseur.ui.stats.ReadingStatsViewModel
import com.chmouel.liseur.ui.library.LibraryScreen
import com.chmouel.liseur.ui.library.LibraryViewModel
import com.chmouel.liseur.ui.settings.ServerAccountScreen
import com.chmouel.liseur.ui.settings.SyncServerScreen
import com.chmouel.liseur.ui.settings.SyncServerViewModel
import com.chmouel.liseur.ui.settings.ServerAccountViewModel
import com.chmouel.liseur.ui.settings.LicencesScreen
import com.chmouel.liseur.ui.settings.SettingsScreen
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.ProvideEInk
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ThemeMode
import com.chmouel.liseur.ui.theme.LiseurTheme
import com.chmouel.liseur.ui.theme.dynamicColorAvailable
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState

class MainActivity : ComponentActivity() {

    /**
     * Whether we still have to decide between the library and the book you
     * were reading. The splash screen stays up while we do, which takes one
     * small database read, so nobody sees the library flash past.
     */
    private var deciding = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { deciding }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only a genuinely cold start resumes a book: coming back from the
        // reader must land on the library, not bounce straight back in.
        if (savedInstanceState != null) deciding = false

        setContent {
            val settings by container.appSettings.settings
                .collectAsState(initial = AppSettings())
            ProvideEInk(settings.eInkMode) {
                LiseurTheme(
                    darkTheme = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> isSystemInDarkTheme()
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                    },
                    // A palette lifted from a wallpaper is chosen for how
                    // its colours sit together, which is exactly what a
                    // greyscale screen throws away — hence the monochrome
                    // override below, which takes precedence.
                    dynamicColor = settings.dynamicColor,
                    monochrome = LocalEInk.current,
                ) {
                    LiseurApp(settings)
                }
            }
        }

        if (deciding) {
            lifecycleScope.launch {
                resumeLastBook()
                deciding = false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Being here means the library is what you left the app on.
        lifecycleScope.launch { container.sessionState.setLeftFromReader(false) }
    }

    private suspend fun resumeLastBook() {
        val container = container
        if (!container.appSettings.current().resumeLastBook) return
        val leftFromReader = container.sessionState.leftFromReader()
        val book = container.database.bookDao().mostRecentlyOpened()
        val candidate = book?.openableUrl?.let { fileUrl ->
            ResumeCandidate(
                identity = book.url,
                fileUrl = fileUrl,
                totalProgression = container.database.readingProgressDao()
                    .get(book.url)
                    ?.totalProgression,
                finished = book.finished,
            )
        }
        if (!shouldResume(candidate, leftFromReader) || candidate == null) return
        // No animation: as far as the reader is concerned the app simply
        // opened on their book, and a cross-fade from a library they never
        // asked for would give the game away.
        startActivity(
            ReaderActivity.intent(this, candidate.fileUrl, candidate.identity),
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
    }
}

private enum class Screen {
    LIBRARY,
    SETTINGS,
    SERVER_ACCOUNT,
    SYNC_SERVER,
    LICENCES,
    STATS,
    BOOK_STATS,
}

/**
 * Which book the per-book statistics are about.
 *
 * A second piece of state beside [Screen] because the enum carries no
 * arguments, and the alternative — a navigation library, routes, a
 * back stack — is a lot of machinery for this small set of screens.
 */
private data class StatsTarget(val bookUrl: String, val title: String)

private const val SOURCE_URL = "https://github.com/chmouel/liseur"

@Composable
private fun LiseurApp(settings: AppSettings) {
    var screen by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
    // The server screen is reached from two places now, and Back has to
    // go back to whichever one it was, not to the one it usually is.
    var accountReturnsTo by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    var statsBook by rememberSaveable(stateSaver = StatsTargetSaver) {
        mutableStateOf<StatsTarget?>(null)
    }
    var bookStatsReturnsTo by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { context.container.appSettings }
    val annotationBackup = rememberAnnotationBackup()

    when (screen) {
        Screen.LIBRARY -> LibraryRoute(
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenStats = { screen = Screen.STATS },
            onOpenBookStats = { book ->
                statsBook = StatsTarget(book.url, book.displayTitle)
                bookStatsReturnsTo = Screen.LIBRARY
                screen = Screen.BOOK_STATS
            },
            onConnectServer = {
                accountReturnsTo = Screen.LIBRARY
                screen = Screen.SERVER_ACCOUNT
            },
        )

        Screen.STATS -> {
            BackHandler { screen = Screen.LIBRARY }
            val model: ReadingStatsViewModel = viewModel(
                factory = ReadingStatsViewModel.factory(),
            )
            val statsState by model.state.collectAsStateWithLifecycle()
            ReadingStatsScreen(
                state = statsState,
                onOpenBook = { book ->
                    statsBook = StatsTarget(book.bookUrl, book.title)
                    bookStatsReturnsTo = Screen.STATS
                    screen = Screen.BOOK_STATS
                },
                onBack = { screen = Screen.LIBRARY },
            )
        }

        Screen.BOOK_STATS -> {
            val target = statsBook
            // Nothing to show a book's reading for. Only reachable if the
            // saved state came back without the book, which Android is
            // allowed to do; going home beats an empty screen.
            if (target == null) {
                LaunchedEffect(Unit) { screen = Screen.LIBRARY }
            } else {
                val back = { screen = bookStatsReturnsTo }
                BackHandler { back() }
                val model: ReadingStatsViewModel = viewModel(
                    factory = ReadingStatsViewModel.factory(),
                )
                val bookStatsState by remember(model, target.bookUrl) { model.forBook(target.bookUrl) }
                    .collectAsStateWithLifecycle()
                BookReadingStatsScreen(
                    title = target.title,
                    state = bookStatsState,
                    onBack = back,
                )
            }
        }

        Screen.SETTINGS -> {
            BackHandler { screen = Screen.LIBRARY }
            SettingsScreen(
                settings = settings,
                dynamicColorAvailable = dynamicColorAvailable,
                onThemeMode = { scope.launch { repository.setThemeMode(it) } },
                onDynamicColor = { scope.launch { repository.setDynamicColor(it) } },
                onVolumeKeys = { scope.launch { repository.setVolumeKeysTurnPages(it) } },
                onEInkMode = { scope.launch { repository.setEInkMode(it) } },
                onResumeLastBook = { scope.launch { repository.setResumeLastBook(it) } },
                onDictionaryLookup = {
                    scope.launch { repository.setDictionaryLookupEnabled(it) }
                },
                onDictionaryBaseUrl = {
                    scope.launch { repository.setDictionaryBaseUrl(it) }
                },
                onOpenAccount = {
                    accountReturnsTo = Screen.SETTINGS
                    screen = Screen.SERVER_ACCOUNT
                },
                onOpenSyncServer = { screen = Screen.SYNC_SERVER },
                onExportAnnotations = annotationBackup.export,
                onImportAnnotations = annotationBackup.restore,
                onOpenSource = { context.openLink(SOURCE_URL.toUri()) },
                onOpenLicences = { screen = Screen.LICENCES },
                onBack = { screen = Screen.LIBRARY },
            )
        }

        Screen.SERVER_ACCOUNT -> {
            BackHandler { screen = accountReturnsTo }
            ServerAccountRoute(onBack = { screen = accountReturnsTo })
        }

        Screen.SYNC_SERVER -> {
            BackHandler { screen = Screen.SETTINGS }
            SyncServerRoute(onBack = { screen = Screen.SETTINGS })
        }

        Screen.LICENCES -> {
            BackHandler { screen = Screen.SETTINGS }
            LicencesScreen(onBack = { screen = Screen.SETTINGS })
        }
    }
}

/** The two ways marks leave and return: a file written, a file read. */
private class AnnotationBackupActions(val export: () -> Unit, val restore: () -> Unit)

/**
 * Wires saving and restoring marks to the system file picker.
 *
 * A file the user chooses, rather than somewhere of our own, because the
 * whole point is to move it: onto another phone, into a backup, out of
 * Liseur entirely if they would rather keep their reading elsewhere.
 */
@Composable
private fun rememberAnnotationBackup(): AnnotationBackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember(context) { context.container.annotationBackup }
    // The application context on purpose: the message is put together in
    // a callback that outlives the composition, and reading resources
    // off the composition's own context there is a leak waiting to be.
    val app = remember(context) { context.applicationContext }

    fun report(result: BackupResult) {
        val message = when (result) {
            is BackupResult.Exported -> app.getString(
                R.string.export_annotations_done,
                result.annotations,
                result.books,
            )
            BackupResult.NothingToExport -> app.getString(R.string.export_annotations_empty)
            is BackupResult.Imported -> if (result.added == 0) {
                app.getString(R.string.import_annotations_none)
            } else {
                app.getString(R.string.import_annotations_done, result.added)
            }
            is BackupResult.Failed -> result.reason
                ?.let { app.getString(R.string.annotations_backup_failed, it) }
                ?: app.getString(R.string.annotations_backup_failed_unknown)
        }
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { scope.launch { report(backup.exportTo(it)) } } }

    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { scope.launch { report(backup.importFrom(it)) } } }

    return remember(save, open) {
        AnnotationBackupActions(
            export = { save.launch("liseur-highlights.json") },
            // Not filtered to application/json: files copied between
            // devices arrive labelled all sorts of things, and being told
            // your own backup cannot be picked is maddening.
            restore = { open.launch(arrayOf("*/*")) },
        )
    }
}

/** Opening a link must never take the app down with it. */
private fun android.content.Context.openLink(uri: Uri) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun ServerAccountRoute(
    onBack: () -> Unit,
    viewModel: ServerAccountViewModel = viewModel(factory = ServerAccountViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ServerAccountScreen(
        state = state,
        onKindChange = viewModel::setKind,
        onUrlChange = viewModel::setUrl,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onApiKeyChange = viewModel::setApiKey,
        onConnect = viewModel::connect,
        onRetryCapabilities = viewModel::retryCapabilities,
        onKoboToken = viewModel::setKoboToken,
        onDisconnect = viewModel::disconnect,
        onSyncNow = viewModel::syncPositions,
        onBack = onBack,
    )
}

@Composable
private fun SyncServerRoute(
    onBack: () -> Unit,
    viewModel: SyncServerViewModel = viewModel(factory = SyncServerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SyncServerScreen(
        state = state,
        onSignInChange = viewModel::setSignIn,
        onUrlChange = viewModel::setUrl,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onTokenChange = viewModel::setToken,
        onWantInsights = viewModel::setWantInsights,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onSyncNow = viewModel::syncNow,
        onBack = onBack,
    )
}

@Composable
private fun LibraryRoute(
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenBookStats: (com.chmouel.liseur.data.db.Book) -> Unit,
    onConnectServer: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Coming back from the reader, or from a file manager where a book was
    // just dropped into a watched folder, the library should already know.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshIfStale()
                Lifecycle.Event.ON_STOP -> viewModel.forgetPendingOpen()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openBook = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.importBook(uri)
            context.startActivity(ReaderActivity.intent(context, uri.toString()))
        }
    }

    val addFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    // A book tapped while it was still on the server opens by itself once
    // the download lands; walking away from the library calls it off.
    LaunchedEffect(viewModel) {
        viewModel.openRequests.collect { book ->
            viewModel.forgetPendingOpen()
            book.openableUrl?.let {
                context.startActivity(ReaderActivity.intent(context, it, book.url))
            }
        }
    }
    LibraryScreen(
        state = state,
        onOpenBook = { openBook.launch(arrayOf("application/epub+zip")) },
        onAddFolder = { addFolder.launch(null) },
        onBookSelected = { book ->
            book.openableUrl?.let {
                context.startActivity(ReaderActivity.intent(context, it, book.url))
            }
        },
        onOpenSettings = onOpenSettings,
        onOpenStats = onOpenStats,
        onOpenBookStats = onOpenBookStats,
        onConnectServer = onConnectServer,
        onDownload = viewModel::download,
        onCancelDownload = viewModel::cancelDownload,
        onRemoveDownload = viewModel::removeDownload,
        onSetFinished = viewModel::setFinished,
        onSetArchived = viewModel::setArchived,
        onDeleteLocal = viewModel::deleteLocalBook,
        onDeleteFromServer = viewModel::deleteFromServer,
        deleteFailures = viewModel.deleteFailures,
        onRefresh = viewModel::refreshAll,
        onSetSort = viewModel::setSort,
        onToggleSortDirection = viewModel::toggleSortDirection,
        onDownloadAndOpen = viewModel::downloadAndOpen,
        failedOpens = viewModel.failedOpens,
        onPendingOpenHandled = viewModel::forgetPendingOpen,
        onSearchQueryChange = viewModel::setSearchQuery,
        onSetFilter = viewModel::setFilter,
        onSetSearchActive = viewModel::setSearchActive,
    )
}

/**
 * Keeps the book the statistics are about across a process death.
 *
 * Two strings, saved as a list, because a `data class` is not something
 * a Bundle can hold on its own and a parcelable for two fields would be
 * more ceremony than the fields are worth.
 */
private val StatsTargetSaver = listSaver<StatsTarget?, String>(
    save = { target -> target?.let { listOf(it.bookUrl, it.title) } ?: emptyList() },
    restore = { saved -> saved.takeIf { it.size == 2 }?.let { StatsTarget(it[0], it[1]) } },
)
