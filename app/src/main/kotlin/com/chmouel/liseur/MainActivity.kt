package com.chmouel.liseur

import android.app.ActivityOptions
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import com.chmouel.liseur.domain.ResumeCandidate
import com.chmouel.liseur.domain.shouldResume
import com.chmouel.liseur.providers.ServicesEntry
import com.chmouel.liseur.providers.ServicesScreen
import com.chmouel.liseur.reader.ReaderActivity
import kotlinx.coroutines.launch
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.ui.reading.FineTypographyActions
import com.chmouel.liseur.ui.stats.BookReadingStatsScreen
import com.chmouel.liseur.ui.stats.ReadingStatsScreen
import com.chmouel.liseur.ui.stats.ReadingStatsViewModel
import com.chmouel.liseur.ui.library.BookActionsSheet
import com.chmouel.liseur.ui.library.BrowseLibrariesRoute
import com.chmouel.liseur.ui.library.ConfirmLocalDeleteDialog
import com.chmouel.liseur.ui.library.ConfirmRemoveDownloadDialog
import com.chmouel.liseur.ui.library.ConfirmRemoveFromLibraryDialog
import com.chmouel.liseur.ui.library.ConfirmServerDeleteDialog
import com.chmouel.liseur.ui.library.LibraryScreen
import com.chmouel.liseur.ui.library.SeriesPickerSheet
import com.chmouel.liseur.ui.library.SeriesScreen
import com.chmouel.liseur.ui.library.LibraryViewModel
import com.chmouel.liseur.ui.settings.ServerAccountScreen
import com.chmouel.liseur.ui.settings.ServerAccountViewModel
import com.chmouel.liseur.ui.settings.AboutScreen
import com.chmouel.liseur.ui.settings.AppLocales
import com.chmouel.liseur.ui.settings.LicencesScreen
import com.chmouel.liseur.ui.settings.SettingsScreen
import com.chmouel.liseur.ui.settings.SettingsBackupScreen
import com.chmouel.liseur.ui.settings.ReadingAppearanceScreen
import com.chmouel.liseur.ui.settings.HiddenBooksScreen
import com.chmouel.liseur.ui.settings.ReadingNavigationScreen
import com.chmouel.liseur.ui.settings.rememberSettingsZipBackup
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.ProvideEInk
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.ui.theme.LiseurTheme
import com.chmouel.liseur.ui.theme.SystemBarIcons
import com.chmouel.liseur.ui.theme.dynamicColorAvailable
import com.chmouel.liseur.ui.theme.isDark
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.runtime.collectAsState
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.ui.launch.LaunchRequests
import com.chmouel.liseur.ui.launch.LaunchTarget
import com.chmouel.liseur.ui.launch.LaunchResolution
import com.chmouel.liseur.ui.launch.LaunchRequest
import com.chmouel.liseur.ui.launch.LaunchViewModel
import androidx.activity.viewModels
import kotlinx.coroutines.Job
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.chmouel.liseur.ui.navigation.LaunchStack
import com.chmouel.liseur.ui.navigation.Route
import com.chmouel.liseur.ui.navigation.RouteBackStackSaver
import com.chmouel.liseur.ui.navigation.contentKey
import com.chmouel.liseur.ui.navigation.launchStack
import com.chmouel.liseur.ui.navigation.pop
import com.chmouel.liseur.ui.navigation.push

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLocales.overrideConfiguration(newBase)?.let(::applyOverrideConfiguration)
    }

    /**
     * Whether we still have to decide between the library and the book you
     * were reading. The splash screen stays up while we do, which takes one
     * small database read, so nobody sees the library flash past.
     */
    private var deciding = true
    private var resumeJob: Job? = null
    private val launchModel: LaunchViewModel by viewModels { LaunchViewModel.Factory(container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { deciding || launchModel.resolving.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptShortcut(intent)
        // Restore an unfinished shortcut before deciding whether ordinary startup may resume.
        launchModel

        // Only a genuinely cold start resumes a book: coming back from the
        // reader must land on the library, not bounce straight back in.
        if (savedInstanceState != null) deciding = false
        if (LaunchRequests.shared.pending.value != null) deciding = false

        setContent {
            val settings by container.appSettings.settings
                .collectAsState(initial = AppSettings())
            val appIsDark = settings.themeMode.isDark()
            // enableEdgeToEdge() above picked the icon colour from the
            // configuration this activity was created in and will not be
            // asked again, so a theme switched in Settings would otherwise
            // leave dark icons on a dark bar until something recreated us.
            SystemBarIcons(dark = appIsDark)
            ProvideEInk(settings.eInkMode) {
                LiseurTheme(
                    darkTheme = appIsDark,
                    // E-paper needs stable colours rather than a palette
                    // lifted from the wallpaper. The central e-ink policy
                    // below therefore takes precedence over dynamic colour.
                    dynamicColor = settings.dynamicColor,
                    eInk = LocalEInk.current,
                    colorEInk = settings.colorEInk,
                ) {
                    val resolution by launchModel.ready.collectAsStateWithLifecycle()
                    LiseurApp(
                        settings,
                        launch = resolution,
                        onLaunchHandled = launchModel::handled,
                    )
                }
            }
        }

        if (deciding) {
            resumeJob = lifecycleScope.launch {
                try {
                    resumeLastBook()
                } finally {
                    deciding = false
                }
            }
        }
        lifecycleScope.launch {
            LaunchRequests.shared.pending.collect { request ->
                if (request != null) {
                    resumeJob?.cancel()
                    deciding = false
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShortcut(intent)
    }

    private fun acceptShortcut(intent: Intent) {
        LaunchTarget.fromAction(intent.action)?.let {
            resumeJob?.cancel()
            deciding = false
            LaunchRequests.shared.shortcut(it)
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
        val candidate = book?.openableUri()?.let { fileUrl ->
            ResumeCandidate(
                identity = book.url,
                fileUrl = fileUrl,
                totalProgression = container.database.readingProgressDao()
                    .get(book.url)
                    ?.totalProgression,
                finished = book.finished,
            )
        }
        if (!shouldResume(candidate, leftFromReader) || candidate == null ||
            LaunchRequests.shared.pending.value != null
        ) return
        // No animation: as far as the reader is concerned the app simply
        // opened on their book, and a cross-fade from a library they never
        // asked for would give the game away.
        startActivity(
            ReaderActivity.intent(this, candidate.fileUrl, candidate.identity),
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
    }
}

private const val SOURCE_URL = "https://github.com/chmouel/liseur"
private const val SPONSOR_URL = "https://github.com/sponsors/chmouel"

@Composable
private fun LiseurApp(
    settings: AppSettings,
    launch: LaunchResolution? = null,
    onLaunchHandled: (LaunchRequest) -> Unit = {},
) {
    // The screens open, library at the bottom. Back closes the top one, so
    // the server screen returns to whichever screen opened it, and book
    // statistics to the shelf, the series or the overall statistics.
    val backStack = rememberSaveable(saver = RouteBackStackSaver) {
        mutableStateListOf<Route>(Route.Library)
    }
    var libraryLaunchId by rememberSaveable { mutableStateOf(0L) }
    var openGutenberg by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(launch?.request?.id) {
        val resolved = launch ?: return@LaunchedEffect
        val request = resolved.request
        if (!LaunchRequests.shared.owns(request)) return@LaunchedEffect
        val stack = launchStack(
            if (request.target == LaunchTarget.STATS) LaunchStack.STATS else LaunchStack.LIBRARY,
        )
        // In one step, so the display never sees an empty stack.
        Snapshot.withMutableSnapshot {
            backStack.clear()
            backStack.addAll(stack)
        }
        libraryLaunchId++
        openGutenberg = false
        if (resolved.error != null) {
            android.widget.Toast.makeText(context, resolved.error, android.widget.Toast.LENGTH_LONG).show()
        }
        if (resolved.fileUrl != null && resolved.book != null) {
            context.startActivity(ReaderActivity.intent(context, resolved.fileUrl, resolved.book.url))
        }
        if (request.bookUrl == null) onLaunchHandled(request)
    }
    val scope = rememberCoroutineScope()
    val repository = remember(context) { context.container.appSettings }
    val readerPreferences = remember(context) { context.container.readerPreferences }
    val readerPrefs by readerPreferences.prefs.collectAsStateWithLifecycle(ReaderPrefs())
    val appIsDark = settings.themeMode.isDark()
    val settingsZipBackup = rememberSettingsZipBackup(
        active = backStack.lastOrNull() == Route.SettingsBackup,
    )
    // Closing the backup screen first cancels whatever it was doing, the
    // same whether it is closed by its arrow or by Back.
    val back: () -> Unit = {
        if (backStack.lastOrNull() == Route.SettingsBackup) settingsZipBackup.close()
        backStack.pop()
    }
    val eInk = LocalEInk.current

    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberTopEntryBackDecorator(backStack),
        ),
        // E-paper repaints every frame of an animation; screens there
        // change in one step, as they always have.
        transitionSpec = if (eInk) {
            { NoTransition }
        } else {
            defaultTransitionSpec()
        },
        popTransitionSpec = if (eInk) {
            { NoTransition }
        } else {
            defaultPopTransitionSpec()
        },
        predictivePopTransitionSpec = if (eInk) {
            { _ -> NoTransition }
        } else {
            defaultPredictivePopTransitionSpec()
        },
        entryProvider = { route ->
            NavEntry(route, contentKey = route.contentKey) {
                when (route) {
                    Route.Library -> androidx.compose.runtime.key(libraryLaunchId) {
                        LibraryRoute(
                            widgetRequest = launch?.request?.takeIf { !it.shortcut && it.bookUrl != null },
                            onWidgetHandled = { launch?.request?.let(onLaunchHandled) },
                            onOpenSettings = { backStack.push(Route.Settings) },
                            onOpenStats = { backStack.push(Route.Stats) },
                            onOpenBookStats = { book ->
                                backStack.push(Route.BookStats(book.url, book.displayTitle))
                            },
                            onConnectServer = { backStack.push(Route.ServerAccount) },
                            onBrowseLibraries = { backStack.push(Route.BrowseLibraries) },
                            onStartWithFreeBooks = {
                                openGutenberg = true
                                backStack.push(Route.BrowseLibraries)
                            },
                        )
                    }

                    Route.BrowseLibraries -> BrowseLibrariesRoute(
                        onExit = back,
                        openGutenberg = openGutenberg,
                        onGutenbergOpened = { openGutenberg = false },
                    )

                    Route.Stats -> {
                        val model: ReadingStatsViewModel = viewModel(
                            factory = ReadingStatsViewModel.factory(),
                        )
                        // The view model outlives the configuration change that a
                        // language switch is, so the week's first day is pushed in
                        // from here, where the locale is observable state.
                        val weekStart = localeWeekStart(LocalLocale.current.platformLocale)
                        LaunchedEffect(model, weekStart) { model.setWeekStart(weekStart) }
                        LiveStatsEffect(model)
                        val statsState by model.state.collectAsStateWithLifecycle()
                        ReadingStatsScreen(
                            state = statsState,
                            onOpenBook = { book ->
                                // Only a book this device has can be opened. A row
                                // the server counted and this library has no file
                                // for carries no tap target at all (ADR-0021), so
                                // this is belt and braces rather than a path taken.
                                book.bookUrl?.let { url ->
                                    backStack.push(Route.BookStats(url, book.title))
                                }
                            },
                            onBack = back,
                            onSelectRange = model::selectRange,
                        )
                    }

                    is Route.BookStats -> {
                        run {
                            val target = route
                            val model: ReadingStatsViewModel = viewModel(
                                factory = ReadingStatsViewModel.factory(),
                            )
                            val weekStart = localeWeekStart(LocalLocale.current.platformLocale)
                            LaunchedEffect(model, weekStart) { model.setWeekStart(weekStart) }
                            LiveStatsEffect(model)
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

                    Route.Settings -> {
                        // Through the ViewModel so that removing a folder, which
                        // walks SAF and can take a while, is not cancelled by a
                        // rotation part-way through.
                        val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
                        SettingsScreen(
                            settings = settings,
                            readingThemeChoice = readerPrefs.themeChoice,
                            dynamicColorAvailable = dynamicColorAvailable,
                            onThemeMode = { scope.launch { repository.setThemeMode(it) } },
                            onDynamicColor = { scope.launch { repository.setDynamicColor(it) } },
                            onOpenAccount = { backStack.push(Route.ServerAccount) },
                            onOpenReadingAppearance = { backStack.push(Route.ReadingAppearance) },
                            onOpenReadingNavigation = { backStack.push(Route.ReadingNavigation) },
                            onOpenSettingsBackup = { backStack.push(Route.SettingsBackup) },
                            onOpenHiddenBooks = { backStack.push(Route.HiddenBooks) },
                            libraryFolders = library.libraryFolders,
                            onRemoveFolder = { library.removeFolder(it) },
                            server = context.container.remoteAccount.server,
                            onOpenAbout = { backStack.push(Route.About) },
                            onBack = back,
                            flavorReadingRows = {
                                context.container.readAloud.SettingsEntry(onClick = { backStack.push(Route.ReadAloud) })
                                context.container.translate.SettingsEntry(onClick = { backStack.push(Route.Translation) })
                                ServicesEntry(onClick = { backStack.push(Route.Services) })
                            },
                        )
                    }

                    Route.ReadAloud -> {
                        context.container.readAloud.SettingsScreen(onBack = back)
                    }

                    Route.Translation -> {
                        context.container.translate.SettingsScreen(onBack = back)
                    }

                    Route.Services -> {
                        ServicesScreen(
                            connections = context.container.serverConnections,
                            accounts = context.container.serviceAccounts,
                            onBack = back,
                        )
                    }

                    Route.SettingsBackup -> {
                        SettingsBackupScreen(
                            backup = settingsZipBackup,
                            onBack = back,
                        )
                    }

                    Route.HiddenBooks -> {
                        val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
                        val hidden by library.hidden.collectAsStateWithLifecycle(emptyList())
                        HiddenBooksScreen(
                            hidden = hidden,
                            onUnhide = { library.unhide(it.url) },
                            onBack = back,
                        )
                    }

                    Route.ReadingNavigation -> {
                        ReadingNavigationScreen(
                            settings = settings,
                            pageTurnStyle = readerPrefs.pageTurnStyle,
                            vendorName = context.container.eInkDisplay.vendor,
                            onVolumeKeys = { scope.launch { repository.setVolumeKeysTurnPages(it) } },
                            onTapZones = { scope.launch { repository.setTapZones(it) } },
                            onPinchToResize = { scope.launch { repository.setPinchToResize(it) } },
                            onPageTurnStyle = { scope.launch { readerPreferences.setPageTurnStyle(it) } },
                            onResumeLastBook = { scope.launch { repository.setResumeLastBook(it) } },
                            onScrollMode = { scope.launch { repository.setScrollMode(it) } },
                            onLockFooter = { scope.launch { repository.setLockFooterOn(it) } },
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

                    Route.ReadingAppearance -> {
                        val activity = LocalActivity.current
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
                            onFooterField = { slot, field ->
                                scope.launch { readerPreferences.setFooterField(slot, field) }
                            },
                            highlightPalette = settings.highlightPalette,
                            onHighlightTintToggled = { scope.launch { repository.toggleHighlightTint(it) } },
                            onHighlightDefaultTint = {
                                scope.launch { repository.setHighlightDefaultTint(it) }
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
                            appLanguage = remember { AppLocales.current(context) },
                            onAppLanguage = { language -> activity?.let { AppLocales.apply(it, language) } },
                            onBack = back,
                        )
                    }

                    Route.ServerAccount -> {
                        ServerAccountRoute(onBack = back)
                    }

                    Route.About -> {
                        AboutScreen(
                            onBack = back,
                            onOpenSource = { context.openLink(SOURCE_URL.toUri()) },
                            onOpenSponsor = { context.openLink(SPONSOR_URL.toUri()) },
                            onOpenLicences = { backStack.push(Route.Licences) },
                        )
                    }

                    Route.Licences -> {
                        LicencesScreen(onBack = back)
                    }
                }
            }
        },
    )
}

/** A screen change with no animation, for e-paper. */
private val NoTransition: ContentTransform = EnterTransition.None togetherWith ExitTransition.None

/**
 * Lets only the screen on top answer Back.
 *
 * Screens below the top one are still composed while a transition or a
 * predictive-back preview runs, and their own Back handlers — the library's
 * search, a series in reorder mode, the catalog browser's folders — would
 * otherwise still be listening. Each entry gets a child dispatcher that is
 * switched off unless that entry is the top of the stack, and `BackHandler`
 * finds its dispatcher through the same composition local.
 */
@Composable
private fun rememberTopEntryBackDecorator(backStack: List<Route>): NavEntryDecorator<Route> =
    remember(backStack) {
        NavEntryDecorator { entry ->
            val onTop = entry.contentKey == backStack.lastOrNull()?.contentKey
            val owner = rememberNavigationEventDispatcherOwner(enabled = onTop)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                entry.Content()
            }
        }
    }


/** Opening a link must never take the app down with it. */
private fun android.content.Context.openLink(uri: Uri) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun LiveStatsEffect(model: ReadingStatsViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    DisposableEffect(model, context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.clockChanged()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.observeLiveInsights()
        }
    }
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
        onEditAddress = viewModel::editAddress,
        onCancelEditAddress = viewModel::cancelEditAddress,
        onCancelDownloadAll = viewModel::cancelDownloadAll,
        onDismissBatch = viewModel::dismissBatch,
        onKosyncUrlChange = viewModel::setKosyncUrl,
        onKosyncUsernameChange = viewModel::setKosyncUsername,
        onKosyncPasswordChange = viewModel::setKosyncPassword,
        onKosyncRegisterChange = viewModel::setKosyncRegister,
        onKosyncConnect = viewModel::connectKosync,
        onKosyncDisconnect = viewModel::disconnectKosync,
        onLocalNetworkRequestLaunched = viewModel::onLocalNetworkRequestLaunched,
        onLocalNetworkResult = viewModel::onLocalNetworkResult,
        onAskLocalNetworkAgain = viewModel::askLocalNetworkAgain,
        onRefreshLocalNetworkAccess = viewModel::refreshLocalNetworkAccess,
        onBack = onBack,
    )
}


@Composable
private fun LibraryRoute(
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenBookStats: (com.chmouel.liseur.data.db.Book) -> Unit,
    onConnectServer: () -> Unit,
    onBrowseLibraries: () -> Unit,
    onStartWithFreeBooks: () -> Unit,
    widgetRequest: LaunchRequest? = null,
    onWidgetHandled: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigationGeneration = remember { LaunchRequests.shared.latestId }
    var readerStartsSeen by remember { mutableLongStateOf(LaunchRequests.shared.readerStarts) }
    var widgetShelfBook by remember { mutableStateOf<com.chmouel.liseur.data.db.Book?>(null) }
    LaunchedEffect(widgetRequest?.id) {
        // Drop the previous tap's book so only this request can reach the shelf.
        widgetShelfBook = null
        val request = widgetRequest ?: return@LaunchedEffect
        val url = request.bookUrl ?: return@LaunchedEffect
        val book = context.container.database.bookDao().getByUrl(url)
        if (!LaunchRequests.shared.owns(request)) return@LaunchedEffect
        if (book != null && !book.hidden && !book.archived) {
            widgetShelfBook = book
        } else {
            onWidgetHandled()
        }
    }

    // Coming back from the reader, or from a file manager where a book was
    // just dropped into a watched folder, the library should already know.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> readerStartsSeen = LaunchRequests.shared.readerStarts
                Lifecycle.Event.ON_RESUME -> viewModel.refreshIfStale()
                Lifecycle.Event.ON_STOP -> viewModel.forgetPendingOpen()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.forgetPendingOpen()
        }
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
            // The reader is not opened here any more. What was picked
            // may be a book the library already has under the other
            // spelling SAF gives one file, and opening the URI that was
            // picked recorded the reading against an entry the shelf
            // could not show (issue #147). The answer comes back below.
            viewModel.importBook(uri)
        }
    }

    // A book that really was new: opened where the library now keeps it.
    val openImported by viewModel.openImported.collectAsStateWithLifecycle()
    LaunchedEffect(openImported) {
        val open = openImported ?: return@LaunchedEffect
        viewModel.openImportedHandled()
        context.startActivity(
            ReaderActivity.intent(context, open.url, open.bookUrl ?: open.url),
        )
    }

    val alreadyShelved by viewModel.alreadyShelved.collectAsStateWithLifecycle()
    alreadyShelved?.let { book ->
        AlertDialog(
            onDismissRequest = { viewModel.alreadyShelvedHandled() },
            title = { Text(stringResource(R.string.already_shelved_title)) },
            text = { Text(stringResource(R.string.already_shelved_message, book.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.alreadyShelvedHandled()
                    book.openableUri()?.let {
                        context.startActivity(ReaderActivity.intent(context, it, book.url))
                    }
                }) {
                    Text(stringResource(R.string.already_shelved_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.alreadyShelvedHandled() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val addFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    // A book tapped while it was still on the server opens by itself once
    // the download lands; walking away from the library calls it off.
    // Only while the library is the screen in front: under Navigation 3 it
    // stays composed while it animates out or is previewed behind a Back
    // gesture, and neither of those is a moment to open a book.
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.openRequests.collect { book ->
                viewModel.forgetPendingOpen()
                if (LaunchRequests.shared.latestId != navigationGeneration) return@collect
                // A reader opened from a widget while the library waited covers it; don't open over it.
                if (LaunchRequests.shared.readerStarts != readerStartsSeen) return@collect
                book.openableUri()?.let {
                    context.startActivity(ReaderActivity.intent(context, it, book.url))
                }
            }
        }
    }
    // Which series is open, if any. Kept here rather than on the back
    // stack because it is a step inside the library rather than away from
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
    var seriesRemoveFromLibrary by remember {
        mutableStateOf<com.chmouel.liseur.data.db.Book?>(null)
    }
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
                val local = book.openableUri()
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
                refusal = state.refusedUploads[book.url],
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
                onRemoveFromLibrary = {
                    seriesRemoveFromLibrary = book
                    seriesSheetBook = null
                },
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
                deletesWholeBook = state.serverDeletesWholeBook,
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
        seriesRemoveFromLibrary?.let { book ->
            ConfirmRemoveFromLibraryDialog(
                book = book,
                onConfirm = {
                    viewModel.removeFromLibrary(book)
                    seriesRemoveFromLibrary = null
                },
                onDismiss = { seriesRemoveFromLibrary = null },
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
            book.openableUri()?.let {
                context.startActivity(ReaderActivity.intent(context, it, book.url))
            }
        },
        onOpenSettings = onOpenSettings,
        onOpenStats = onOpenStats,
        onOpenBookStats = onOpenBookStats,
        onConnectServer = onConnectServer,
        onBrowseLibraries = onBrowseLibraries,
        onStartWithFreeBooks = onStartWithFreeBooks,
        onDownload = viewModel::download,
        onCancelDownload = viewModel::cancelDownload,
        onRemoveDownload = viewModel::removeDownload,
        onSetFinished = viewModel::setFinished,
        onSetArchived = viewModel::setArchived,
        onDeleteLocal = viewModel::deleteLocalBook,
        onRemoveFromLibrary = viewModel::removeFromLibrary,
        onUnhide = viewModel::unhide,
        hiddenBooks = viewModel.hiddenBooks,
        onDeleteFromServer = viewModel::deleteFromServer,
        onUploadToServer = viewModel::uploadToServer,
        onUploadPending = viewModel::uploadPending,
        onUploadPendingAlways = viewModel::uploadPendingAlways,
        onDismissUploadPrompt = viewModel::dismissUploadPrompt,
        onDismissCatalogPartial = viewModel::dismissCatalogPartial,
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
        uploadRefusals = viewModel.uploadRefusals,
        onRefusalShown = viewModel::refusalSeen,
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
        widgetBook = widgetShelfBook?.takeIf { book ->
            widgetRequest?.let { it.bookUrl == book.url && LaunchRequests.shared.owns(it) } == true
        },
        onWidgetBookHandled = {
            widgetShelfBook = null
            // A newer tap may have arrived after this book was composed; only the current one may open.
            val current = widgetRequest?.let(LaunchRequests.shared::owns) == true
            if (current) onWidgetHandled()
            current
        },
    )
}
