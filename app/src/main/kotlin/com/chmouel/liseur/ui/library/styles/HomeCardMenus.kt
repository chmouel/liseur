package com.chmouel.liseur.ui.library.styles

import android.content.ClipData
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.quotes.HomeQuote
import com.chmouel.liseur.domain.HomeSeriesSet
import com.chmouel.liseur.domain.HomeShelfKind
import com.chmouel.liseur.domain.HomeShelfTile
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.reader.annotations.citationText
import com.chmouel.liseur.reader.annotations.shareText
import kotlinx.coroutines.launch

@Composable
internal fun HomeQuoteMenu(
    quote: HomeQuote,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenPassage: () -> Unit,
    onEditNote: () -> Unit,
    onAnotherQuote: () -> Unit,
    onBookDetails: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val passage = quote.text.trimQuoteMarks()
    val quoted = stringResource(R.string.home_quote_text, passage)
    val shareBody = citationText(
        text = quoted,
        title = quote.book.displayTitle,
        authors = listOfNotNull(quote.book.displayAuthor),
    )
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.selection_copy)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
            enabled = passage.isNotBlank(),
            onClick = {
                onDismiss()
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(quote.book.displayTitle, passage)))
                }
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.annotation_share)) },
            leadingIcon = { Icon(Icons.Outlined.Share, null) },
            enabled = passage.isNotBlank(),
            onClick = {
                onDismiss()
                context.shareText(shareBody, quote.book.displayTitle)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vocabulary_open_passage)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
            onClick = {
                onDismiss()
                onOpenPassage()
            },
        )
        if (quote.highlight && quote.annotationId != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_quote_edit_note)) },
                leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                onClick = {
                    onDismiss()
                    onEditNote()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_quote_another)) },
            leadingIcon = { Icon(Icons.Outlined.Shuffle, null) },
            onClick = {
                onDismiss()
                onAnotherQuote()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.book_details)) },
            leadingIcon = { Icon(Icons.Outlined.Info, null) },
            onClick = {
                onDismiss()
                onBookDetails()
            },
        )
    }
}

@Composable
internal fun HomeSeriesMenu(
    set: HomeSeriesSet,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    val context = LocalContext.current
    val next = set.next ?: set.shelf.nextUp
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.series_open)) },
            leadingIcon = { Icon(Icons.Outlined.Layers, null) },
            onClick = {
                onDismiss()
                onOpenSeries()
            },
        )
        if (next != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (next.inProgress) R.string.series_continue else R.string.series_start,
                            next.book.displayTitle,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
                onClick = {
                    onDismiss()
                    onOpenBook(next.book)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_series_share)) },
            leadingIcon = { Icon(Icons.Outlined.Share, null) },
            enabled = set.shelf.name.isNotBlank(),
            onClick = {
                onDismiss()
                context.shareText(set.shelf.name, null)
            },
        )
    }
}

@Composable
internal fun HomeShelfTileMenu(
    tile: HomeShelfTile,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenShelf: () -> Unit,
    onChooseStyle: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_shelf_open)) },
            leadingIcon = { Icon(tile.kind.icon, null) },
            onClick = {
                onDismiss()
                onOpenShelf()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_style)) },
            leadingIcon = { Icon(Icons.Outlined.Dashboard, null) },
            onClick = {
                onDismiss()
                onChooseStyle()
            },
        )
    }
}
