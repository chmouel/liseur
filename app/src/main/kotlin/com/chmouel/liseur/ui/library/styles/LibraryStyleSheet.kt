package com.chmouel.liseur.ui.library.styles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.LibraryLayout
import com.chmouel.liseur.domain.LibraryStyle
import com.chmouel.liseur.domain.ShelfBackdrop
import com.chmouel.liseur.domain.ShelfGrouping
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.settings.ChipRow
import com.chmouel.liseur.ui.settings.SwitchRow
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LibraryStyle.label: Int
    get() = when (this) {
        LibraryStyle.CLASSIC -> R.string.library_style_classic
        LibraryStyle.HOME -> R.string.library_style_home
        LibraryStyle.SHELVES -> R.string.library_style_shelves
        LibraryStyle.LIST -> R.string.library_style_list
    }

private val LibraryStyle.detail: Int
    get() = when (this) {
        LibraryStyle.CLASSIC -> R.string.library_style_classic_detail
        LibraryStyle.HOME -> R.string.library_style_home_detail
        LibraryStyle.SHELVES -> R.string.library_style_shelves_detail
        LibraryStyle.LIST -> R.string.library_style_list_detail
    }

private val ShelfGrouping.label: Int
    get() = when (this) {
        ShelfGrouping.SERIES -> R.string.sort_series
        ShelfGrouping.AUTHOR -> R.string.shelf_group_author
        ShelfGrouping.STATUS -> R.string.shelf_group_status
    }

private val ShelfBackdrop.label: Int
    get() = when (this) {
        ShelfBackdrop.PLAIN -> R.string.shelf_backdrop_plain
        ShelfBackdrop.WOOD -> R.string.shelf_backdrop_wood
        ShelfBackdrop.PLASTER -> R.string.shelf_backdrop_plaster
        ShelfBackdrop.BRICKS -> R.string.shelf_backdrop_bricks
        ShelfBackdrop.CUSTOM -> R.string.shelf_backdrop_custom
    }

/**
 * Picks the library style from the library itself, where the result can
 * be seen behind the sheet, with the options the chosen style has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryStyleSheet(
    layout: LibraryLayout,
    /** A few of the reader's books, whose covers fill the previews. */
    books: List<Book>,
    onChange: (LibraryLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickBackdrop = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = importBackdropImage(context, uri) ?: return@launch
            onChange(layout.copy(shelfBackdrop = ShelfBackdrop.CUSTOM, shelfBackdropUri = saved))
        }
    }

    LiseurModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.library_style),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Column(Modifier.selectableGroup()) {
                LibraryStyle.entries.forEach { style ->
                    val selected = style == layout.style
                    ListItem(
                        headlineContent = { Text(stringResource(style.label)) },
                        supportingContent = { Text(stringResource(style.detail)) },
                        leadingContent = { StylePreview(style = style, books = books, selected = selected) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onChange(layout.copy(style = style)) },
                            ),
                    )
                }
            }
            if (layout.style == LibraryStyle.SHELVES || layout.style == LibraryStyle.LIST) {
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            when (layout.style) {
                LibraryStyle.SHELVES -> Column(Modifier.padding(horizontal = 8.dp)) {
                    ChipRow(
                        title = stringResource(R.string.shelf_grouping),
                        options = ShelfGrouping.entries,
                        selected = layout.shelfGrouping,
                        label = { stringResource(it.label) },
                        onSelected = { onChange(layout.copy(shelfGrouping = it)) },
                    )
                    ChipRow(
                        title = stringResource(R.string.shelf_backdrop),
                        options = ShelfBackdrop.entries,
                        selected = layout.shelfBackdrop,
                        label = { stringResource(it.label) },
                        onSelected = { chosen ->
                            val needPick = chosen == ShelfBackdrop.CUSTOM &&
                                (layout.shelfBackdrop == ShelfBackdrop.CUSTOM || layout.shelfBackdropUri == null)
                            if (needPick) {
                                pickBackdrop.launch(arrayOf("image/*"))
                            } else {
                                onChange(layout.copy(shelfBackdrop = chosen))
                            }
                        },
                    )
                }

                LibraryStyle.LIST -> Column(Modifier.padding(horizontal = 8.dp)) {
                    SwitchRow(
                        title = stringResource(R.string.library_compact_list),
                        subtitle = stringResource(R.string.library_compact_list_detail),
                        checked = layout.compactList,
                        onCheckedChange = { onChange(layout.copy(compactList = it)) },
                    )
                }

                LibraryStyle.CLASSIC, LibraryStyle.HOME -> Unit
            }
        }
    }
}

private const val MAX_BACKDROP_EDGE = 2048

/**
 * Copies the chosen backdrop into app storage so it survives document
 * provider changes, downscaling oversized photos first.
 */
private suspend fun importBackdropImage(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching null
            var sample = 1
            while (longest / (sample * 2) >= MAX_BACKDROP_EDGE) {
                sample *= 2
            }
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decode)
            } ?: return@runCatching null
            val out = File(context.filesDir, "shelf_backdrop.jpg")
            try {
                out.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                }
            } finally {
                bitmap.recycle()
            }
            "${out.toURI()}?v=${System.currentTimeMillis()}"
        }.getOrNull()
    }
