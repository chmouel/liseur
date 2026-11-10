package com.chmouel.liseur.ui.library.styles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.LibraryLayout
import com.chmouel.liseur.domain.LibraryStyle
import com.chmouel.liseur.domain.ShelfGrouping
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.settings.ChipRow
import com.chmouel.liseur.ui.settings.SwitchRow

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

/**
 * Picks the library style from the library itself, where the result can
 * be seen behind the sheet, with the one option the chosen style has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryStyleSheet(
    layout: LibraryLayout,
    onChange: (LibraryLayout) -> Unit,
    onDismiss: () -> Unit,
) {
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
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
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
