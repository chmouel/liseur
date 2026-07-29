package com.chmouel.liseur.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chmouel.liseur.R
import com.chmouel.liseur.data.calibre.CatalogStatus
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.DownloadState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpenBook: () -> Unit,
    onAddFolder: () -> Unit,
    onBookSelected: (Book) -> Unit,
    onOpenAccount: () -> Unit,
    onDownload: (Book) -> Unit,
    onCancelDownload: (Book) -> Unit,
    onRemoveDownload: (Book) -> Unit,
    onSetFinished: (Book, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDownloadAndOpen: (Book) -> Unit,
    failedOpens: Flow<Book>,
    onPendingOpenHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHost = remember { SnackbarHostState() }
    val downloading = stringResource(R.string.download_in_progress)
    val downloadsNotAllowed = stringResource(R.string.downloads_not_allowed)
    var sheetBook by remember { mutableStateOf<Book?>(null) }
    val scope = rememberCoroutineScope()
    val notYetHere = stringResource(R.string.book_not_downloaded)
    val credentialsLost = stringResource(R.string.calibre_credentials_lost)

    val downloadFailed = stringResource(R.string.download_failed_open)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenAccount) {
                        Icon(
                            Icons.Outlined.CloudQueue,
                            contentDescription = stringResource(R.string.calibre_account),
                        )
                    }
                    IconButton(onClick = onAddFolder) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            contentDescription = stringResource(R.string.add_folder),
                        )
                    }
                    IconButton(onClick = onOpenBook) {
                        Icon(
                            Icons.Outlined.FileOpen,
                            contentDescription = stringResource(R.string.open_book),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize())

                state.books.isEmpty() -> EmptyLibrary(
                    onOpenBook = onOpenBook,
                    onAddFolder = onAddFolder,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> BookGrid(
                    state = state,
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
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
                        Text(stringResource(R.string.remove_download))
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
        }
    }
}

@Composable
private fun BookGrid(
    state: LibraryUiState,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.continueReading?.let { recent ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueReadingCard(
                    entry = recent,
                    onClick = { onBookSelected(recent.book) },
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
                    .width(64.dp)
                    .height(96.dp),
            )
            Column(
                Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.continue_reading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = entry.book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.book.author?.let {
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
                            modifier = Modifier.weight(1f),
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
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                    modifier = Modifier.matchParentSize(),
                )

                book.downloadState != DownloadState.DOWNLOADED ->
                    OnServerBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            if (book.finished && progress == null) {
                FinishedBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        book.author?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The tick that says you have read this one. */
@Composable
private fun FinishedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = stringResource(R.string.book_finished),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val artwork = book.coverPath ?: book.coverUrl
    if (artwork != null) {
        AsyncImage(
            model = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** Dims the cover and shows how far the download has got. */
@Composable
private fun DownloadOverlay(fraction: Float?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        val color = MaterialTheme.colorScheme.inverseOnSurface
        if (fraction == null) {
            CircularProgressIndicator(color = color, modifier = Modifier.size(32.dp))
        } else {
            CircularProgressIndicator(
                progress = { fraction },
                color = color,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** Marks a book that is in the catalog but not yet on the device. */
@Composable
private fun OnServerBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .padding(5.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudDownload,
            contentDescription = stringResource(R.string.book_on_server),
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun EmptyLibrary(
    onOpenBook: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
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
    }
}
