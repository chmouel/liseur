package com.chmouel.liseur.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import com.chmouel.liseur.domain.LibrarySort
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import kotlinx.coroutines.flow.Flow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.CatalogStatus
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.BRAND_TILE_ASPECT
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.brandTileHeight
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.coverMinSize
import com.chmouel.liseur.ui.libraryBarHeight
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpenBook: () -> Unit,
    onAddFolder: () -> Unit,
    onBookSelected: (Book) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenBookStats: (Book) -> Unit,
    onConnectServer: () -> Unit,
    onDownload: (Book) -> Unit,
    onCancelDownload: (Book) -> Unit,
    onRemoveDownload: (Book) -> Unit,
    onSetFinished: (Book, Boolean) -> Unit,
    onSetArchived: (Book, Boolean) -> Unit,
    onDeleteLocal: (Book) -> Unit,
    onDeleteFromServer: (Book) -> Unit,
    deleteFailures: Flow<DeleteFailure>,
    onRefresh: () -> Unit,
    onSetSort: (LibrarySort) -> Unit,
    onToggleSortDirection: () -> Unit,
    onDownloadAndOpen: (Book) -> Unit,
    failedOpens: Flow<Book>,
    onPendingOpenHandled: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onSetFilter: (LibraryFilter) -> Unit = {},
    onSetSearchActive: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Which cut of the reading mark to draw.  Asked of the scheme in
    // force rather than of the resource qualifiers, so it follows the
    // app's own dark setting even when the system disagrees.
    val darkMark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // The bar shows the illustration at the size the window can afford.
    val barWidth = windowWidth()
    val tileHeight = brandTileHeight(barWidth)
    // Keyed to the picture, not to the window class: a seven-inch
    // e-reader lands just under Material's tablet threshold, and the
    // bar should follow the size of the tile it is holding.
    val wide = tileHeight >= 64.dp
    val gridState = rememberLazyGridState()
    val snackbarHost = remember { SnackbarHostState() }
    val downloading = stringResource(R.string.download_in_progress)
    val downloadsNotAllowed = stringResource(R.string.downloads_not_allowed)
    var sheetBook by remember { mutableStateOf<Book?>(null) }
    var confirmServerDelete by remember { mutableStateOf<Book?>(null) }
    val scope = rememberCoroutineScope()
    val eInk = LocalEInk.current
    val notYetHere = stringResource(R.string.book_not_downloaded)
    val credentialsLost = stringResource(R.string.server_credentials_lost)

    val downloadFailed = stringResource(R.string.download_failed_open)
    val serverDeleteFailed = stringResource(R.string.delete_from_server_failed)
    val localDeleteFailed = stringResource(R.string.delete_local_failed)
    LaunchedEffect(deleteFailures) {
        deleteFailures.collect { failure ->
            val message = if (failure.onServer) serverDeleteFailed else localDeleteFailed
            snackbarHost.showSnackbar(message.format(failure.book.title))
        }
    }
    LaunchedEffect(failedOpens) {
        failedOpens.collect { book ->
            onPendingOpenHandled()
            snackbarHost.showSnackbar(downloadFailed.format(book.title))
        }
    }

    LaunchedEffect(state.catalogStatus) {
        if (state.catalogStatus is CatalogStatus.CredentialsLost) {
            snackbarHost.showSnackbar(credentialsLost)
        }
    }

    // What the search field shows is held here rather than read back from
    // the view model: the query is combined with seven other flows and
    // filtered off the main thread before it returns, which is far too
    // late to draw the letter that was just typed.  The view model is
    // still told about every change; it just no longer owes an answer.
    var searchField by remember { mutableStateOf(TextFieldValue()) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.isSearchActive) {
        if (!state.isSearchActive) return@LaunchedEffect
        // Opening search brings the last query back with all of it
        // selected, so the next letter replaces it and one delete clears
        // it, without losing it for anyone who only wanted a second look.
        val query = state.searchQuery
        searchField = TextFieldValue(query, TextRange(0, query.length))
        searchFocus.requestFocus()
        // Focus alone usually raises the keyboard, but not dependably
        // when the field arrives in the same frame as the bar holding it.
        keyboard?.show()
    }

    // Without this, back on an open search bar falls through to the
    // activity and closes the app.
    BackHandler(enabled = state.isSearchActive) { onSetSearchActive(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (state.isSearchActive) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchField,
                            onValueChange = {
                                searchField = it
                                onSearchQueryChange(it.text)
                            },
                            placeholder = { Text(stringResource(R.string.search_books)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { onSetSearchActive(false) }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        if (searchField.text.isNotEmpty()) {
                            IconButton(onClick = {
                                searchField = TextFieldValue()
                                onSearchQueryChange("")
                            }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.clear),
                                )
                            }
                        }
                    },
                )
            } else {
                // Deliberately the compact bar, not the large one: the
                // collapsed form is the good one, so it is what the shelf
                // gets all the time rather than only once you scroll.
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier.clickable {
                                scope.launch {
                                    // Scrolling the whole way is a long
                                    // animation on a screen that repaints
                                    // in frames; e-paper gets there in one.
                                    if (eInk) {
                                        gridState.scrollToItem(0)
                                    } else {
                                        gridState.animateScrollToItem(0)
                                    }
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // A woman reading stretched out on her couch,
                            // cut from the banner art — the reading mark,
                            // in place of a plain wordmark or the launcher
                            // icon.  Painted art with its own wall and
                            // floor, so it is framed as a small tile
                            // rather than knocked out to line work.
                            //
                            // Which cut to draw is asked of the theme in
                            // force here, not of a -night qualifier: those
                            // follow the system, and the app's own
                            // light/dark setting is allowed to disagree.
                            Image(
                                painter = painterResource(
                                    if (darkMark) R.drawable.ic_reading_scene_night
                                    else R.drawable.ic_reading_scene,
                                ),
                                contentDescription = null,
                                // Fit, not Crop, and the frame pinned to
                                // the picture's own aspect: whatever
                                // height the bar ends up granting, the
                                // box can never come out wider than the
                                // art and take a slice off her head.
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .height(tileHeight)
                                    .aspectRatio(BRAND_TILE_ASPECT)
                                    .clip(RoundedCornerShape(if (wide) 14.dp else 10.dp)),
                            )
                            Spacer(Modifier.width(if (wide) 16.dp else 10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.library_title),
                                    style = if (wide) {
                                        MaterialTheme.typography.headlineMedium
                                    } else {
                                        MaterialTheme.typography.titleLarge
                                    },
                                )
                                if (!state.loading && state.books.isNotEmpty()) {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.library_book_count,
                                            state.books.size,
                                            state.books.size,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        letterSpacing = 0.8.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                    expandedHeight = libraryBarHeight(barWidth),
                    actions = {
                        IconButton(onClick = { onSetSearchActive(true) }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.search_books),
                            )
                        }
                        var addMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { addMenuOpen = true }) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = stringResource(R.string.add_books),
                                )
                            }
                            DropdownMenu(
                                expanded = addMenuOpen,
                                onDismissRequest = { addMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.add_folder)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.CreateNewFolder,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        addMenuOpen = false
                                        onAddFolder()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_book)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.FileOpen,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        addMenuOpen = false
                                        onOpenBook()
                                    },
                                )
                            }
                        }
                        // A menu rather than more icons. The bar is
                        // already carrying two, and the shelf is what
                        // this screen is for.
                        var moreOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { moreOpen = true }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = moreOpen,
                                onDismissRequest = { moreOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reading_stats)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.BarChart,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        moreOpen = false
                                        onOpenStats()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Settings,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        moreOpen = false
                                        onOpenSettings()
                                    },
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Above every branch below, deliberately. A notice that
                // only appeared over a full shelf would be missing from
                // the one case that needs it most: a new account whose
                // first refresh failed, where the library is empty and
                // nothing on screen says why.
                (state.catalogStatus as? CatalogStatus.Failed)?.let { failed ->
                    CatalogFailureNotice(
                        reason = failed.reason,
                        onRetry = onRefresh,
                        onReconnect = onConnectServer,
                    )
                }
                when {
                    state.loading -> LibrarySkeleton(Modifier.fillMaxSize())

                    // Everything on the shelf has been put away. Not an
                    // empty library, and saying so would be alarming — the
                    // books are all still here, behind one tap.
                    state.books.isEmpty() && state.libraryIsEmpty && state.hasArchived ->
                        EverythingPutAway(
                            onShowArchived = { onSetFilter(LibraryFilter.ARCHIVED) },
                            modifier = Modifier.fillMaxSize(),
                        )

                    state.books.isEmpty() && state.libraryIsEmpty -> EmptyLibrary(
                        onOpenBook = onOpenBook,
                        onAddFolder = onAddFolder,
                        onConnectServer = onConnectServer,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Books exist, they are simply all hidden. Offering to
                    // add a folder here would be answering a question nobody
                    // asked, and would suggest the shelf had been lost.
                    state.books.isEmpty() -> NothingMatched(
                        searching = state.isSearchActive && state.searchQuery.isNotBlank(),
                        onClear = {
                            onSearchQueryChange("")
                            onSetFilter(LibraryFilter.ALL)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> BookGrid(
                        state = state,
                        gridState = gridState,
                        onSetSort = onSetSort,
                        onToggleSortDirection = onToggleSortDirection,
                        onSetFilter = onSetFilter,
                        onBookSelected = { book ->
                            when {
                                book.openableUrl != null -> onBookSelected(book)
                                book.url in state.downloads ->
                                    scope.launch { snackbarHost.showSnackbar(downloading) }
                                !state.canDownload ->
                                    scope.launch { snackbarHost.showSnackbar(downloadsNotAllowed) }
                                else -> onDownloadAndOpen(book)
                            }
                        },
                        onBookLongPress = { sheetBook = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    confirmServerDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmServerDelete = null },
            title = { Text(stringResource(R.string.delete_from_server)) },
            text = { Text(stringResource(R.string.delete_from_server_warning, book.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFromServer(book)
                        confirmServerDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmServerDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    sheetBook?.let { book ->
        BookActionsSheet(
            book = book,
            downloading = book.url in state.downloads,
            onDismiss = { sheetBook = null },
            canDownload = state.canDownload,
            onDownload = { onDownload(book); sheetBook = null },
            onCancelDownload = { onCancelDownload(book); sheetBook = null },
            onRemoveDownload = { onRemoveDownload(book); sheetBook = null },
            onSetFinished = { onSetFinished(book, it); sheetBook = null },
            onSetArchived = { onSetArchived(book, it); sheetBook = null },
            onOpenStats = { onOpenBookStats(book); sheetBook = null },
            onDeleteLocal = { onDeleteLocal(book); sheetBook = null },
            onDeleteFromServer = { confirmServerDelete = book; sheetBook = null },
        )
    }
}

/** Long-press actions for a book: chiefly, freeing the space it takes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookActionsSheet(
    book: Book,
    downloading: Boolean,
    canDownload: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onSetFinished: (Boolean) -> Unit,
    onSetArchived: (Boolean) -> Unit,
    onOpenStats: () -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteFromServer: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(book.title, style = MaterialTheme.typography.titleMedium)
            book.author?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            when {
                downloading -> Button(
                    onClick = onCancelDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel_download))
                }

                book.downloadState == DownloadState.DOWNLOADED && book.remoteUuid != null ->
                    OutlinedButton(
                        onClick = onRemoveDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.remove_copy_on_device))
                    }

                book.remoteUuid != null -> Button(
                    onClick = onDownload,
                    enabled = canDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.download_book))
                }

                else -> Unit
            }
            if (book.remoteUuid == null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDeleteLocal, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.delete_file))
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { onSetFinished(!book.finished) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (book.finished) R.string.mark_unread else R.string.mark_read,
                    ),
                )
            }
            TextButton(
                onClick = { onSetArchived(!book.archived) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (book.archived) R.string.unarchive_book else R.string.archive_book,
                    ),
                )
            }
            TextButton(onClick = onOpenStats, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reading_stats))
            }
            if (book.remoteUuid != null) {
                // Kept apart from the others on purpose: everything above
                // touches this device only, this one reaches the server.
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onDeleteFromServer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.delete_from_server),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookGrid(
    state: LibraryUiState,
    gridState: LazyGridState,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onSetSort: (LibrarySort) -> Unit,
    onToggleSortDirection: () -> Unit,
    onSetFilter: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = coverMinSize(windowWidth())),
        state = gridState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Not a search result, so it has no business sitting above them
        // and pushing the real answers off the screen.
        state.continueReading?.takeIf { !state.isSearchActive }?.let { recent ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueReadingCard(
                    entry = recent,
                    onClick = { onBookSelected(recent.book) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            // Filters and sort share one line: they answer the same
            // question, "which books and in what order", and stacking
            // them cost a whole row of covers for no reason.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The chips scroll rather than wrap or clip, so a narrow
                // phone with every chip showing still reaches the last
                // one instead of losing it off the edge.
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.filter == LibraryFilter.ALL,
                        onClick = { onSetFilter(LibraryFilter.ALL) },
                        label = { FilterChipLabel(stringResource(R.string.filter_all)) },
                    )
                    // Only worth asking once some books are on a server
                    // and some are not: with nothing but local files,
                    // everything is downloaded and the chip is a no-op.
                    if (state.hasServer) {
                        FilterChip(
                            selected = state.filter == LibraryFilter.DOWNLOADED,
                            onClick = { onSetFilter(LibraryFilter.DOWNLOADED) },
                            label = {
                                FilterChipLabel(stringResource(R.string.filter_downloaded))
                            },
                        )
                    }
                    FilterChip(
                        selected = state.filter == LibraryFilter.UNREAD,
                        onClick = { onSetFilter(LibraryFilter.UNREAD) },
                        label = { FilterChipLabel(stringResource(R.string.filter_unread)) },
                    )
                    // Offered only once something has been put away: an
                    // empty drawer is not worth a permanent chip.
                    if (state.hasArchived || state.filter == LibraryFilter.ARCHIVED) {
                        FilterChip(
                            selected = state.filter == LibraryFilter.ARCHIVED,
                            onClick = {
                                onSetFilter(
                                    if (state.filter == LibraryFilter.ARCHIVED) {
                                        LibraryFilter.ALL
                                    } else {
                                        LibraryFilter.ARCHIVED
                                    },
                                )
                            },
                            label = {
                                FilterChipLabel(stringResource(R.string.filter_archived))
                            },
                        )
                    }
                }
                // Pinned to the far end of the row and laid out last, so
                // it is never squeezed: the chips give way to it rather
                // than the other way round.
                SortRow(
                    sort = state.sort,
                    reversed = state.sortReversed,
                    onSetSort = onSetSort,
                    onToggleDirection = onToggleSortDirection,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        items(state.books, key = { it.id }) { book ->
            BookCard(
                book = book,
                progress = state.downloads[book.url],
                onClick = { onBookSelected(book) },
                onLongClick = { onBookLongPress(book) },
            )
        }
    }
}

@Composable
private fun ContinueReadingCard(
    entry: ContinueReading,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                book = entry.book,
                modifier = Modifier
                    .width(72.dp)
                    .height(108.dp)
                    .shadow(6.dp, RoundedCornerShape(10.dp)),
            )
            Column(
                Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.continue_reading).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.book.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.book.displayAuthor?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                entry.progression?.let { progression ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progression.toFloat() },
                            strokeCap = StrokeCap.Round,
                            modifier = Modifier.weight(1f).height(6.dp),
                        )
                        Text(
                            text = "${(progression * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    progress: DownloadProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // The whole card is one thing to a screen reader: the cover, the badges
    // and the two lines of text are all describing the same book.
    val state = bookStateDescription(book, progress)
    Column(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClickLabel = stringResource(R.string.read_book),
                onLongClickLabel = stringResource(R.string.book_actions),
            )
            .semantics(mergeDescendants = true) { stateDescription = state },
    ) {
        Box {
            BookCover(
                book = book,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    // A book you have read should read as done at a glance,
                    // without disappearing from the shelf.
                    .alpha(if (book.finished) 0.55f else 1f),
            )
            when {
                progress != null -> DownloadOverlay(
                    fraction = progress.fraction,
                    queued = progress.queued,
                    modifier = Modifier.matchParentSize(),
                )

                book.downloadState != DownloadState.DOWNLOADED ->
                    OnServerBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            if (book.finished && progress == null) {
                FinishedBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        Column(
            modifier = Modifier
                .padding(top = 6.dp)
                .heightIn(min = 56.dp),
        ) {
            Text(
                text = book.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            book.displayAuthor?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What a screen reader should say about a book beyond its title and author. */
@Composable
private fun bookStateDescription(book: Book, progress: DownloadProgress?): String = when {
    progress?.queued == true -> stringResource(R.string.state_download_queued)
    progress != null -> stringResource(R.string.state_downloading)
    book.finished -> stringResource(R.string.state_finished)
    book.downloadState != DownloadState.DOWNLOADED -> stringResource(R.string.state_on_server)
    else -> stringResource(R.string.state_on_device)
}

/**
 * Cover-shaped placeholders while the first library query runs. A blank
 * screen reads as "no books"; this reads as "nearly there".
 */
/** The breathing alpha the loading placeholders are drawn at. */
@Composable
private fun skeletonPulse(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    return alpha
}

@Composable
private fun LibrarySkeleton(modifier: Modifier = Modifier) {
    // On electronic paper the pulse is not a hint that something is
    // coming, it is the whole screen repainting twice a second until it
    // does. The placeholders are just as legible held still — and the
    // transition is never started, rather than started and ignored.
    val alpha = if (LocalEInk.current) 0.5f else skeletonPulse()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = coverMinSize(windowWidth())),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
        modifier = modifier,
    ) {
        items(SKELETON_COUNT) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                        ),
                )
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.8f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                        ),
                )
            }
        }
    }
}

private const val SKELETON_COUNT = 9

/** The tick that says you have read this one. */
@Composable
private fun FinishedBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.book_finished),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * What went wrong last time the catalog was fetched.
 *
 * Phrased as a report of the last attempt rather than a claim about now,
 * because connectivity comes back without telling anyone and a notice
 * that insists you are offline while you are not is worse than none. The
 * action follows the reason: there is no point offering to try again
 * against a password the server has already refused.
 */
@Composable
private fun CatalogFailureNotice(
    reason: SyncFailure,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val explanation = when (reason) {
        SyncFailure.Offline -> R.string.catalog_failed_offline
        SyncFailure.Timeout -> R.string.catalog_failed_timeout
        SyncFailure.Unauthorised -> R.string.catalog_failed_unauthorised
        SyncFailure.Forbidden -> R.string.catalog_failed_forbidden
        SyncFailure.NotFound -> R.string.catalog_failed_not_found
        SyncFailure.Malformed -> R.string.catalog_failed_malformed
        is SyncFailure.ServerError -> R.string.catalog_failed_server_error
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = stringResource(explanation),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            when (reason) {
                SyncFailure.Unauthorised -> TextButton(onClick = onReconnect) {
                    Text(stringResource(R.string.catalog_reconnect))
                }
                else -> if (reason.worthRetrying) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.catalog_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderCover(book: Book, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp),
        ) {
            Text(
                text = book.displayTitle,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            book.displayAuthor?.let { author ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val artwork = book.coverPath ?: book.coverUrl
    val borderModifier = modifier
        .clip(shape)
        .border(
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            shape,
        )
    if (artwork != null) {
        SubcomposeAsyncImage(
            model = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = borderModifier,
            error = { PlaceholderCover(book) },
            loading = { PlaceholderCover(book) },
        )
    } else {
        Box(modifier = borderModifier) {
            PlaceholderCover(book)
        }
    }
}

/**
 * Badges sit on cover artwork, not on a themed surface, so they carry their
 * own colours. The Material scheme is no help here: its `inverseOnSurface`
 * is dark in the dark theme, which left the badge invisible against the
 * scrim behind it and against a dark cover.
 */
private val CoverBadgeScrim = Color.Black.copy(alpha = 0.6f)
private val CoverBadgeContent = Color.White

/**
 * Dims the cover and shows how far the download has got.
 *
 * A queued download gets a still icon rather than a turning one. They
 * are not the same thing and they were shown as if they were: tapping a
 * book with no connection left it spinning indefinitely, which reads as
 * a download in progress that will never finish rather than as one
 * waiting for its moment.
 */
@Composable
private fun DownloadOverlay(
    fraction: Float?,
    queued: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CoverBadgeScrim),
        contentAlignment = Alignment.Center,
    ) {
        when {
            queued -> Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = CoverBadgeContent,
                modifier = Modifier.size(32.dp),
            )
            fraction == null -> BusyIndicator(
                color = CoverBadgeContent,
                modifier = Modifier.size(32.dp),
            )
            else -> CircularProgressIndicator(
                progress = { fraction },
                color = CoverBadgeContent,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** Marks a book that is in the catalog but not yet on the device. */
@Composable
private fun OnServerBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.primary,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = stringResource(R.string.book_on_server),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(
    onOpenBook: () -> Unit,
    onAddFolder: () -> Unit,
    onConnectServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scrollable even though it always fits, so that a pull from the top
    // still reaches the refresh above it. An empty library is exactly
    // when someone needs to pull: they have just added a folder or
    // connected an account and are waiting for books to turn up.
    BoxWithConstraints(modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.empty_library_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.empty_library_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAddFolder) {
                Text(stringResource(R.string.add_folder))
            }
            OutlinedButton(onClick = onOpenBook) {
                Text(stringResource(R.string.open_book))
            }
            // The text above offers a server; until now nothing here
            // took anyone to one, and it lives three taps away under
            // Settings, which is not somewhere a new library looks.
            OutlinedButton(onClick = onConnectServer) {
                Text(stringResource(R.string.connect_server))
            }
        }
    }
}

/**
 * Shown when the shelf has books on it but a search or a filter is
 * hiding every one of them.
 */
@Composable
private fun NothingMatched(
    searching: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    if (searching) R.string.no_books_match else R.string.no_books_in_filter,
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.show_all_books))
            }
        }
    }
}

/**
 * What the library says once every book on it has been put away.
 *
 * It has to lead somewhere, because the only way back to those books is
 * a chip that lives in the grid this screen is standing in for.
 */
@Composable
private fun EverythingPutAway(
    onShowArchived: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(R.string.everything_put_away),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onShowArchived) {
                Text(stringResource(R.string.filter_archived))
            }
        }
    }
}

/** The name of an order, for the button and the menu. */
@Composable
private fun LibrarySort.label(): String = stringResource(
    when (this) {
        LibrarySort.RECENT -> R.string.sort_recent
        LibrarySort.TITLE -> R.string.sort_title
        LibrarySort.AUTHOR -> R.string.sort_author
        LibrarySort.ADDED -> R.string.sort_added
    },
)

/**
 * A filter chip's text, one line and never wider than its words.
 *
 * Sharing a line with the sort control leaves the chips less room than
 * they had to themselves, so they are set a step down and kept from
 * wrapping: a chip two lines tall would push the whole row out of the
 * grid's rhythm.
 */
@Composable
private fun FilterChipLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * How the library is arranged, and the way to change it.
 *
 * It scrolls with the grid rather than sitting in the top bar: the order
 * is worth seeing without a tap, but not worth a permanent row of chrome
 * above every book.
 *
 * Order and direction are one control, not two. A separate reverse
 * button spent a whole touch target on a question nobody asks without
 * first having picked what to sort by, so the direction now lives in the
 * menu: the current order carries the arrow, and tapping it flips.
 */
@Composable
private fun SortRow(
    sort: LibrarySort,
    reversed: Boolean,
    onSetSort: (LibrarySort) -> Unit,
    onToggleDirection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val direction = if (reversed) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward

    Box(modifier = modifier) {
        // Sits at the very end of the filter row, so its insets are cut
        // to what the touch target needs and the label is allowed to
        // truncate: on a narrow phone the arrow beside it matters more
        // than the last word of "Recently added".
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        ) {
            Text(
                text = sort.label(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            Icon(
                imageVector = direction,
                contentDescription = stringResource(
                    if (reversed) R.string.sort_reversed else R.string.sort_normal,
                ),
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(16.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // Anchored to the button's right edge so it opens inward
            // from the screen edge instead of hanging off it.
            offset = DpOffset(x = 8.dp, y = 0.dp),
        ) {
            LibrarySort.entries.forEach { option ->
                val current = option == sort
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        // Choosing the order you are already in is not a
                        // no-op: it is how you turn it around. Anything
                        // else would leave the row inert under the
                        // finger.
                        if (current) onToggleDirection() else onSetSort(option)
                        open = false
                    },
                    trailingIcon = {
                        if (current) {
                            Icon(
                                imageVector = direction,
                                contentDescription = stringResource(
                                    if (reversed) {
                                        R.string.sort_reversed
                                    } else {
                                        R.string.sort_normal
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}
