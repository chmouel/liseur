package com.chmouel.liseur.ui.library.styles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.chmouel.liseur.domain.ShelfTone
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

private val ShelfTone.label: Int
    get() = when (this) {
        ShelfTone.THEME -> R.string.shelf_tone_theme
        ShelfTone.STONE -> R.string.shelf_tone_stone
        ShelfTone.OAK -> R.string.shelf_tone_oak
        ShelfTone.WALNUT -> R.string.shelf_tone_walnut
        ShelfTone.BRASS -> R.string.shelf_tone_brass
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
    // With the tabs, Home is a tab of its own and the sheet styles only the Library.
    val tabs = layout.bottomBar
    val shown = if (tabs && layout.style == LibraryStyle.HOME) LibraryStyle.CLASSIC else layout.style
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    text = stringResource(if (tabs) R.string.library_view_title else R.string.library_style),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (tabs) {
                    StartTabToggle(
                        startOnHome = layout.startOnHome,
                        onChange = { onChange(layout.copy(startOnHome = it)) },
                    )
                }
            }
            Column(Modifier.selectableGroup()) {
                LibraryStyle.entries.filter { !tabs || it != LibraryStyle.HOME }.forEach { style ->
                    val selected = style == shown
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
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Column(Modifier.selectableGroup()) {
                listOf(false, true).forEach { useBottomBar ->
                    val selected = layout.bottomBar == useBottomBar
                    val title = if (useBottomBar) {
                        R.string.library_bottom_bar
                    } else {
                        R.string.library_hero_card
                    }
                    val detail = if (useBottomBar) {
                        R.string.library_bottom_bar_detail
                    } else {
                        R.string.library_hero_card_detail
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(title)) },
                        supportingContent = { Text(stringResource(detail)) },
                        leadingContent = {
                            CurrentBookPreview(
                                bottomBar = useBottomBar,
                                books = books,
                                selected = selected,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onChange(layout.copy(bottomBar = useBottomBar)) },
                            ),
                    )
                }
            }
            when (shown) {
                LibraryStyle.SHELVES -> Column(Modifier.padding(horizontal = 8.dp)) {
                    ChipRow(
                        title = stringResource(R.string.shelf_grouping),
                        options = ShelfGrouping.entries,
                        selected = layout.shelfGrouping,
                        label = { stringResource(it.label) },
                        onSelected = { onChange(layout.copy(shelfGrouping = it)) },
                    )
                    ChipRow(
                        title = stringResource(R.string.shelf_tone),
                        options = ShelfTone.entries,
                        selected = layout.shelfTone,
                        label = { stringResource(it.label) },
                        onSelected = { onChange(layout.copy(shelfTone = it)) },
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

                LibraryStyle.CLASSIC -> Column(Modifier.padding(horizontal = 8.dp)) {
                    ChipRow(
                        title = stringResource(R.string.library_columns),
                        options = listOf(false, true),
                        selected = layout.compactGrid,
                        label = {
                            stringResource(
                                if (it) R.string.library_columns_compact else R.string.library_columns_comfortable,
                            )
                        },
                        onSelected = { onChange(layout.copy(compactGrid = it)) },
                    )
                }

                LibraryStyle.HOME -> Unit
            }
        }
    }
}

/** Which tab the app opens on: a quiet house-or-books toggle beside the title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartTabToggle(startOnHome: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.library_opens_on),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        SingleChoiceSegmentedButtonRow {
            listOf(true, false).forEachIndexed { index, home ->
                val label = stringResource(if (home) R.string.library_style_home else R.string.library_tab_library)
                SegmentedButton(
                    selected = startOnHome == home,
                    onClick = { onChange(home) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                    modifier = Modifier
                        .width(56.dp)
                        .semantics { contentDescription = label },
                ) {
                    Icon(
                        imageVector = when {
                            home && startOnHome -> Icons.Filled.Home
                            home -> Icons.Outlined.Home
                            !startOnHome -> Icons.Filled.LocalLibrary
                            else -> Icons.Outlined.LocalLibrary
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private const val MAX_BACKDROP_EDGE = 2048

/** Applies the EXIF rotation or mirroring that recompressing would otherwise drop. */
private fun upright(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return source
    }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated !== source) source.recycle()
    return rotated
}

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
            while (longest / sample > MAX_BACKDROP_EDGE) {
                sample *= 2
            }
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decode)
            } ?: return@runCatching null
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            val bitmap = upright(decoded, orientation)
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
