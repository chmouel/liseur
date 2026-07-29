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
import com.chmouel.liseur.domain.ResumeCandidate
import com.chmouel.liseur.domain.shouldResume
import com.chmouel.liseur.reader.ReaderActivity
import kotlinx.coroutines.launch
import com.chmouel.liseur.ui.library.LibraryScreen
import com.chmouel.liseur.ui.library.LibraryViewModel
import com.chmouel.liseur.ui.settings.CalibreAccountScreen
import com.chmouel.liseur.ui.settings.CalibreAccountViewModel
import com.chmouel.liseur.ui.settings.LicencesScreen
import com.chmouel.liseur.ui.settings.SettingsScreen
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
            LiseurTheme(
                darkTheme = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = settings.dynamicColor,
            ) {
                LiseurApp(settings)
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

private enum class Screen { LIBRARY, SETTINGS, CALIBRE_ACCOUNT, LICENCES }

private const val SOURCE_URL = "https://github.com/chmouel/liseur"

@Composable
private fun LiseurApp(settings: AppSettings) {
    var screen by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { context.container.appSettings }

    when (screen) {
        Screen.LIBRARY -> LibraryRoute(onOpenSettings = { screen = Screen.SETTINGS })

        Screen.SETTINGS -> {
            BackHandler { screen = Screen.LIBRARY }
            SettingsScreen(
                settings = settings,
                dynamicColorAvailable = dynamicColorAvailable,
                onThemeMode = { scope.launch { repository.setThemeMode(it) } },
                onDynamicColor = { scope.launch { repository.setDynamicColor(it) } },
                onVolumeKeys = { scope.launch { repository.setVolumeKeysTurnPages(it) } },
                onResumeLastBook = { scope.launch { repository.setResumeLastBook(it) } },
                onOpenAccount = { screen = Screen.CALIBRE_ACCOUNT },
                onOpenSource = { context.openLink(SOURCE_URL.toUri()) },
                onOpenLicences = { screen = Screen.LICENCES },
                onBack = { screen = Screen.LIBRARY },
            )
        }

        Screen.CALIBRE_ACCOUNT -> {
            BackHandler { screen = Screen.SETTINGS }
            CalibreAccountRoute(onBack = { screen = Screen.SETTINGS })
        }

        Screen.LICENCES -> {
            BackHandler { screen = Screen.SETTINGS }
            LicencesScreen(onBack = { screen = Screen.SETTINGS })
        }
    }
}

/** Opening a link must never take the app down with it. */
private fun android.content.Context.openLink(uri: Uri) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun CalibreAccountRoute(
    onBack: () -> Unit,
    viewModel: CalibreAccountViewModel = viewModel(factory = CalibreAccountViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CalibreAccountScreen(
        state = state,
        onUrlChange = viewModel::setUrl,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onConnect = viewModel::connect,
        onRetryCapabilities = viewModel::retryCapabilities,
        onKoboToken = viewModel::setKoboToken,
        onDisconnect = viewModel::disconnect,
        onSyncNow = viewModel::syncPositions,
        onBack = onBack,
    )
}

@Composable
private fun LibraryRoute(
    onOpenSettings: () -> Unit,
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
        onDownload = viewModel::download,
        onCancelDownload = viewModel::cancelDownload,
        onRemoveDownload = viewModel::removeDownload,
        onSetFinished = viewModel::setFinished,
        onDeleteLocal = viewModel::deleteLocalBook,
        onDeleteFromServer = viewModel::deleteFromServer,
        deleteFailures = viewModel.deleteFailures,
        onRefresh = viewModel::refresh,
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
