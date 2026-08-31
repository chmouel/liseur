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
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import com.chmouel.liseur.data.library.BackupResult
import com.chmouel.liseur.data.library.BackupSummary
import com.chmouel.liseur.data.library.Inspection
import com.chmouel.liseur.domain.ResumeCandidate
import com.chmouel.liseur.domain.shouldResume
import com.chmouel.liseur.reader.ReaderActivity
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.listSaver
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.ui.reading.FineTypographyActions
import com.chmouel.liseur.ui.stats.BookReadingStatsScreen
import com.chmouel.liseur.ui.stats.ReadingStatsScreen
import com.chmouel.liseur.ui.stats.ReadingStatsViewModel
import com.chmouel.liseur.ui.library.BookActionsSheet
import com.chmouel.liseur.ui.library.ConfirmLocalDeleteDialog
import com.chmouel.liseur.ui.library.ConfirmRemoveDownloadDialog
import com.chmouel.liseur.ui.library.ConfirmServerDeleteDialog
import com.chmouel.liseur.ui.library.LibraryScreen
import com.chmouel.liseur.ui.library.SeriesPickerSheet
import com.chmouel.liseur.ui.library.SeriesScreen
import com.chmouel.liseur.ui.library.LibraryViewModel
import com.chmouel.liseur.ui.settings.ServerAccountScreen
import com.chmouel.liseur.ui.settings.ServerAccountViewModel
import com.chmouel.liseur.ui.settings.AboutScreen
import com.chmouel.liseur.ui.settings.LicencesScreen
import com.chmouel.liseur.ui.settings.SettingsScreen
import com.chmouel.liseur.ui.settings.ReadingAppearanceScreen
import com.chmouel.liseur.ui.settings.ReadingNavigationScreen
import com.chmouel.liseur.ui.settings.AnnotationBackupUi
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.ProvideEInk
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.ui.theme.LiseurTheme
import com.chmouel.liseur.ui.theme.dynamicColorAvailable
import com.chmouel.liseur.ui.theme.isDark
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.runtime.collectAsState
import com.chmouel.liseur.domain.SeriesShelf

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
                    darkTheme = settings.themeMode.isDark(),
                    // E-paper needs stable colours rather than a palette
                    // lifted from the wallpaper. The central e-ink policy
                    // below therefore takes precedence over dynamic colour.
                    dynamicColor = settings.dynamicColor,
                    eInk = LocalEInk.current,
                    colorEInk = settings.colorEInk,
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
    READING_APPEARANCE,
    READING_NAVIGATION,
    SERVER_ACCOUNT,
    LICENCES,
    ABOUT,
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
private const val SPONSOR_URL = "https://github.com/sponsors/chmouel"

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
    val readerPreferences = remember(context) { context.container.readerPreferences }
    val readerPrefs by readerPreferences.prefs.collectAsStateWithLifecycle(ReaderPrefs())
    val appIsDark = settings.themeMode.isDark()
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
            // The view model outlives the configuration change that a
            // language switch is, so the week's first day is pushed in
            // from here, where the locale is observable state.
            val weekStart = localeWeekStart(LocalLocale.current.platformLocale)
            LaunchedEffect(model, weekStart) { model.setWeekStart(weekStart) }
            LaunchedEffect(model) { model.refreshServerInsights() }
            val statsState by model.state.collectAsStateWithLifecycle()
            ReadingStatsScreen(
                state = statsState,
                onOpenBook = { book ->
                    statsBook = StatsTarget(book.bookUrl, book.title)
                    bookStatsReturnsTo = Screen.STATS
                    screen = Screen.BOOK_STATS
                },
                onBack = { screen = Screen.LIBRARY },
                onSelectRange = model::selectRange,
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
                val weekStart = localeWeekStart(LocalLocale.current.platformLocale)
                LaunchedEffect(model, weekStart) { model.setWeekStart(weekStart) }
                LaunchedEffect(model, target.bookUrl) { model.refreshServerInsights() }
                val bookStatsState by remember(model, target.bookUrl) { model.forBook(target.bookUrl) }
                    .collectAsStateWithLifecycle()
                val serverInsights by remember(model, target.bookUrl) {
                    model.serverEstimateFor(target.bookUrl)
                }.collectAsStateWithLifecycle()
                val statsRange by model.range.collectAsStateWithLifecycle()
                BookReadingStatsScreen(
                    title = target.title,
                    state = bookStatsState,
                    onBack = back,
                    serverInsights = serverInsights,
                    range = statsRange,
                )
            }
        }

        Screen.SETTINGS -> {
            BackHandler { screen = Screen.LIBRARY }
            SettingsScreen(
                settings = settings,
                readingThemeChoice = readerPrefs.themeChoice,
                dynamicColorAvailable = dynamicColorAvailable,
                onThemeMode = { scope.launch { repository.setThemeMode(it) } },
                onDynamicColor = { scope.launch { repository.setDynamicColor(it) } },
                onGroupSeries = { grouped ->
                    scope.launch {
                        repository.editLibraryFilters { it.copy(groupBySeries = grouped) }
                    }
                },
                onOpenAccount = {
                    accountReturnsTo = Screen.SETTINGS
                    screen = Screen.SERVER_ACCOUNT
                },
                onOpenReadingAppearance = { screen = Screen.READING_APPEARANCE },
                onOpenReadingNavigation = { screen = Screen.READING_NAVIGATION },
                backup = annotationBackup,
                connections = context.container.connections,
                onOpenAbout = { screen = Screen.ABOUT },
                onBack = { screen = Screen.LIBRARY },
            )
        }

        Screen.READING_NAVIGATION -> {
            val back = { screen = Screen.SETTINGS }
            BackHandler { back() }
            ReadingNavigationScreen(
                settings = settings,
                vendorName = context.container.eInkDisplay.vendor,
                onVolumeKeys = { scope.launch { repository.setVolumeKeysTurnPages(it) } },
                onTapZones = { scope.launch { repository.setTapZones(it) } },
                onResumeLastBook = { scope.launch { repository.setResumeLastBook(it) } },
                onScrollMode = { scope.launch { repository.setScrollMode(it) } },
                onKeepScreenOn = { scope.launch { repository.setKeepScreenOn(it) } },
                onEInkMode = { scope.launch { repository.setEInkMode(it) } },
                onColorEInk = { scope.launch { repository.setColorEInk(it) } },
                onVendorRefresh = { scope.launch { repository.setVendorRefresh(it) } },
                onDefinitionTarget = {
                    scope.launch { repository.setDefinitionTarget(it) }
                },
                onDictionaryLookup = {
                    scope.launch { repository.setDictionaryLookupEnabled(it) }
                },
                onDictionaryBaseUrl = {
                    scope.launch { repository.setDictionaryBaseUrl(it) }
                },
                onBack = back,
            )
        }

        Screen.READING_APPEARANCE -> {
            val back = { screen = Screen.SETTINGS }
            BackHandler { back() }
            ReadingAppearanceScreen(
                prefs = readerPrefs,
                appIsDark = appIsDark,
                onTheme = { scope.launch { readerPreferences.setTheme(it) } },
                onFont = { scope.launch { readerPreferences.setFont(it) } },
                onFontSize = { scope.launch { readerPreferences.setFontSize(it) } },
                onLineHeight = { scope.launch { readerPreferences.setLineHeight(it) } },
                onPageMargins = { scope.launch { readerPreferences.setPageMargins(it) } },
                onBrightness = { scope.launch { readerPreferences.setBrightness(it) } },
                onColumnMode = { scope.launch { readerPreferences.setColumnMode(it) } },
                onFooterMode = { scope.launch { readerPreferences.setFooterMode(it) } },
                onPageTurnAnimation = {
                    scope.launch { readerPreferences.setPageTurnAnimation(it) }
                },
                fineTypography = FineTypographyActions(
                    onTextAlignChanged = { scope.launch { readerPreferences.setTextAlign(it) } },
                    onHyphensChanged = { scope.launch { readerPreferences.setHyphens(it) } },
                    onFontWeightChanged = { scope.launch { readerPreferences.setFontWeight(it) } },
                    onLetterSpacingChanged = {
                        scope.launch { readerPreferences.setLetterSpacing(it) }
                    },
                    onWordSpacingChanged = {
                        scope.launch { readerPreferences.setWordSpacing(it) }
                    },
                    onParagraphSpacingChanged = {
                        scope.launch { readerPreferences.setParagraphSpacing(it) }
                    },
                ),
                onBack = back,
            )
        }

        Screen.SERVER_ACCOUNT -> {
            BackHandler { screen = accountReturnsTo }
            ServerAccountRoute(onBack = { screen = accountReturnsTo })
        }

        Screen.ABOUT -> {
            BackHandler { screen = Screen.SETTINGS }
            AboutScreen(
                onBack = { screen = Screen.SETTINGS },
                onOpenSource = { context.openLink(SOURCE_URL.toUri()) },
                onOpenSponsor = { context.openLink(SPONSOR_URL.toUri()) },
                onOpenLicences = { screen = Screen.LICENCES },
            )
        }

        Screen.LICENCES -> {
            BackHandler { screen = Screen.ABOUT }
            LicencesScreen(onBack = { screen = Screen.ABOUT })
        }
    }
}

/**
 * Everything the settings card needs to show about saving and restoring
 * marks, as state rather than as events.
 *
 * The card is a picture of this: what an export would carry, what a
 * picked file would do, and how the last attempt went. Nothing here
 * Toasts, because a toast vanishes and the question it answered —
 * "did that work?" — usually occurs a few seconds later.
 *
 * Restoring is a two-step: the picker hands over a file, the file is
 * read and described, and only the reader's yes lets it touch the
 * library. The summary is kept current so the card can say what an
 * export would hold rather than being a leap.
 */
@Composable
private fun rememberAnnotationBackup(): AnnotationBackupUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember(context) { context.container.annotationBackup }
    // The application context on purpose: the message is put together in
    // a callback that outlives the composition, and reading resources
    // off the composition's own context there is a leak waiting to be.
    val app = remember(context) { context.applicationContext }

    var summary by remember { mutableStateOf<BackupSummary?>(null) }
    var pending by remember { mutableStateOf<Pair<Uri, Inspection>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch { summary = backup.exportPreview() }
    }
    LaunchedEffect(Unit) { refresh() }

    fun describe(result: BackupResult): String = when (result) {
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

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            scope.launch {
                status = describe(backup.exportTo(it))
                refresh()
            }
        }
    }

    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                when (val looked = backup.inspectBackup(it)) {
                    is Inspection.Ready -> pending = it to looked
                    is Inspection.Unreadable ->
                        status = app.getString(R.string.annotations_backup_failed, looked.reason)
                    is Inspection.Failed -> status = looked.reason
                        ?.let { r -> app.getString(R.string.annotations_backup_failed, r) }
                        ?: app.getString(R.string.annotations_backup_failed_unknown)
                }
            }
        }
    }

    return AnnotationBackupUi(
        summary = summary,
        pendingImport = pending?.second,
        status = status,
        export = { save.launch("liseur-highlights.json") },
        // Not filtered to application/json: files copied between
        // devices arrive labelled all sorts of things, and being told
        // your own backup cannot be picked is maddening.
        restore = { open.launch(arrayOf("*/*")) },
        confirmImport = run@{
            val file = pending ?: return@run
            pending = null
            scope.launch {
                status = describe(backup.importFrom(file.first))
                refresh()
            }
        },
        dismissImport = { pending = null },
    )
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
        onLiseurSyncSignInChange = viewModel::setLiseurSyncSignIn,
        onDeviceTokenChange = viewModel::setDeviceToken,
        onConnect = viewModel::connect,
        onRetryCapabilities = viewModel::retryCapabilities,
        onKoboToken = viewModel::setKoboToken,
        onSetUploadPolicy = viewModel::setUploadPolicy,
        onDisconnect = viewModel::disconnect,
        onSyncNow = viewModel::syncPositions,
        onAnswerConfirmation = viewModel::answerConfirmation,
        onAskDownloadAll = viewModel::askToDownloadAll,
        onDismissDownloadAll = viewModel::dismissDownloadAll,
        onDownloadAll = viewModel::downloadAll,
        onCancelDownloadAll = viewModel::cancelDownloadAll,
        onDismissBatch = viewModel::dismissBatch,
        onKosyncUrlChange = viewModel::setKosyncUrl,
        onKosyncUsernameChange = viewModel::setKosyncUsername,
        onKosyncPasswordChange = viewModel::setKosyncPassword,
        onKosyncRegisterChange = viewModel::setKosyncRegister,
        onKosyncConnect = viewModel::connectKosync,
        onKosyncDisconnect = viewModel::disconnectKosync,
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
    // Which series is open, if any. Kept here rather than in the screen
    // enum because it is a step inside the library rather than away from
    // it: the same view model, the same books, one level down.
    var openSeriesKey by rememberSaveable { mutableStateOf<String?>(null) }
    val liveSeries = openSeriesKey?.let { key -> state.series.firstOrNull { it.key == key } }
    val reorder by viewModel.reorder.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val renamingSeries by viewModel.renamingSeries.collectAsStateWithLifecycle()

    // A rename moves the shelf: it is keyed by name, so the old key
    // stops matching the moment the catalog refresh lands. Following it
    // is the difference between renaming a series and being thrown out
    // of it.
    LaunchedEffect(viewModel) {
        viewModel.renamedSeries.collect { key ->
            if (openSeriesKey != null) openSeriesKey = key
        }
    }

    // The lookup is by name, so a catalog refresh that renames the
    // series — or the last volume being refiled out of it — turns it
    // null and takes the screen with it. Harmless while reading; with an
    // unsaved draft on it, that is losing the reader's work without a
    // word. So the last shelf seen is held while the mode is open.
    val pinnedSeries = remember { mutableStateOf<SeriesShelf?>(null) }
    if (liveSeries != null) pinnedSeries.value = liveSeries
    // Pinned through a rename too: between the request and the refresh
    // that answers it, no shelf has either name.
    val openSeries = liveSeries
        ?: pinnedSeries.value?.takeIf { reorder != null || renamingSeries }

    LaunchedEffect(liveSeries == null, reorder != null) {
        if (liveSeries == null && reorder != null) {
            // Closing with the same message the failed commit uses,
            // rather than vanishing.
            viewModel.seriesWentAway()
            openSeriesKey = null
        }
    }

    var seriesSheetBook by remember { mutableStateOf<com.chmouel.liseur.data.db.Book?>(null) }
    var seriesServerDelete by remember { mutableStateOf<com.chmouel.liseur.data.db.Book?>(null) }
    var seriesLocalDelete by remember { mutableStateOf<com.chmouel.liseur.data.db.Book?>(null) }
    var seriesRemoveDownload by remember { mutableStateOf<com.chmouel.liseur.data.db.Book?>(null) }
    var seriesSheetRefile by remember { mutableStateOf<com.chmouel.liseur.data.db.Book?>(null) }
    val seriesExtras by viewModel.openSeriesExtras.collectAsStateWithLifecycle()

    if (openSeries != null) {
        // Yields to reorder mode's own handler, which cancels the draft
        // before anything leaves the screen.
        BackHandler(enabled = reorder == null) { openSeriesKey = null }
        SeriesScreen(
            shelf = openSeries,
            downloads = state.downloads,
            extras = seriesExtras,
            deleteFailures = viewModel.deleteFailures,
            canDownload = state.canDownload,
            onBack = { openSeriesKey = null },
            onVolumeSelected = { book ->
                val local = book.openableUrl
                if (local != null) {
                    context.startActivity(ReaderActivity.intent(context, local, book.url))
                } else {
                    viewModel.downloadAndOpen(book)
                }
            },
            onVolumeLongPress = { seriesSheetBook = it },
            onDownloadMissing = { viewModel.downloadMissing(openSeries) },
            onMarkSeriesRead = { viewModel.setSeriesFinished(openSeries, true) },
            onArchiveSeries = {
                viewModel.setSeriesArchived(openSeries, true)
                openSeriesKey = null
            },
            reorder = reorder,
            onStartReorder = { viewModel.startReorder(openSeries) },
            onMoveVolume = viewModel::moveVolume,
            onCommitReorder = viewModel::commitReorder,
            onCancelReorder = viewModel::cancelReorder,
            hasCustomNumbers = openSeries.volumes.any { it.book.indexOverridden },
            onClearCustomNumbers = { viewModel.clearCustomVolumeNumbers(openSeries) },
            canRename = state.canRenameSeries &&
                openSeries.volumes.all { it.book.seriesId != null },
            onRenameSeries = { name -> viewModel.renameSeries(openSeries, name) },
            onResetSeriesName = { viewModel.resetSeriesName(openSeries) },
            notice = notice,
            onNoticeShown = viewModel::noticeShown,
        )
        seriesSheetBook?.let { book ->
            BookActionsSheet(
                book = book,
                downloading = book.url in state.downloads,
                canDownload = state.canDownload,
                canDeleteFromServer = state.canDeleteFromServer,
                serverDeleteNeedsReconnect = state.serverDeleteNeedsReconnect,
                canUploadToServer = state.canUploadToServer,
                uploading = book.url in state.uploading,
                onDismiss = { seriesSheetBook = null },
                onDownload = { viewModel.download(book); seriesSheetBook = null },
                onCancelDownload = { viewModel.cancelDownload(book); seriesSheetBook = null },
                onRemoveDownload = { seriesRemoveDownload = book; seriesSheetBook = null },
                onSetFinished = { viewModel.setFinished(book, it); seriesSheetBook = null },
                onSetArchived = { viewModel.setArchived(book, it); seriesSheetBook = null },
                onOpenStats = { onOpenBookStats(book); seriesSheetBook = null },
                // Refiling from inside a series is chiefly how a volume
                // that landed on the wrong shelf gets off it, so it is
                // offered here as well as on the shelf.
                onEditSeries = { seriesSheetRefile = book; seriesSheetBook = null },
                onDeleteLocal = { seriesLocalDelete = book; seriesSheetBook = null },
                // The same warning the shelf puts in front of it. An
                // action offered here and answered nowhere would be a
                // button that quietly does nothing.
                onDeleteFromServer = {
                    seriesServerDelete = book
                    seriesSheetBook = null
                },
                onUploadToServer = {
                    viewModel.uploadToServer(book)
                    seriesSheetBook = null
                },
            )
        }
        seriesSheetRefile?.let { book ->
            SeriesPickerSheet(
                book = book,
                options = state.seriesOptions,
                canResetSharedSeries = state.canResetSharedSeries,
                onConfirm = { name, index ->
                    viewModel.setBookSeries(book, name, index)
                    seriesSheetRefile = null
                },
                onReset = {
                    viewModel.resetBookSeries(book)
                    seriesSheetRefile = null
                },
                onResetShared = {
                    viewModel.resetBookSharedSeries(book)
                    seriesSheetRefile = null
                },
                onDismiss = { seriesSheetRefile = null },
            )
        }
        seriesServerDelete?.let { book ->
            ConfirmServerDeleteDialog(
                book = book,
                canForgetReading = state.canForgetServerReading,
                onConfirm = { forgetReading ->
                    viewModel.deleteFromServer(book, forgetReading)
                    seriesServerDelete = null
                },
                onDismiss = { seriesServerDelete = null },
            )
        }
        seriesLocalDelete?.let { book ->
            ConfirmLocalDeleteDialog(
                book = book,
                onConfirm = {
                    viewModel.deleteLocalBook(book)
                    seriesLocalDelete = null
                },
                onDismiss = { seriesLocalDelete = null },
            )
        }
        seriesRemoveDownload?.let { book ->
            ConfirmRemoveDownloadDialog(
                book = book,
                onConfirm = {
                    viewModel.removeDownload(book)
                    seriesRemoveDownload = null
                },
                onDismiss = { seriesRemoveDownload = null },
            )
        }
        return
    }

    LibraryScreen(
        state = state,
        onAddBook = { openBook.launch(arrayOf("application/epub+zip")) },
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
        onUploadToServer = viewModel::uploadToServer,
        onUploadPending = viewModel::uploadPending,
        onUploadPendingAlways = viewModel::uploadPendingAlways,
        onDismissUploadPrompt = viewModel::dismissUploadPrompt,
        onSetSeries = viewModel::setBookSeries,
        onResetSeries = viewModel::resetBookSeries,
        onResetSharedSeries = viewModel::resetBookSharedSeries,
        deleteFailures = viewModel.deleteFailures,
        onRefresh = viewModel::refreshAll,
        onSetSort = viewModel::setSort,
        onToggleSortDirection = viewModel::toggleSortDirection,
        onDownloadAndOpen = viewModel::downloadAndOpen,
        failedOpens = viewModel.failedOpens,
        sentUp = viewModel.sentUp,
        onPendingOpenHandled = viewModel::forgetPendingOpen,
        onSearchQueryChange = viewModel::setSearchQuery,
        onToggleFilter = viewModel::toggleFilter,
        onSetGroupBySeries = viewModel::setGroupBySeries,
        onClearFilters = viewModel::clearFilters,
        onSetSearchActive = viewModel::setSearchActive,
        onSeriesSelected = { shelf ->
            viewModel.openSeries(shelf)
            openSeriesKey = shelf.key
        },
        notice = notice,
        onNoticeShown = viewModel::noticeShown,
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
