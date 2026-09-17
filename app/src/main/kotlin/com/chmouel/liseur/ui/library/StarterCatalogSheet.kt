package com.chmouel.liseur.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.opds.StarterCatalog
import com.chmouel.liseur.ui.LiseurModalBottomSheet

/**
 * The label for a shelf Liseur offers.
 *
 * Here rather than on the enum because the data layer does not reach for
 * resources. Exhaustive on purpose: a category added without a name to
 * show it under will not compile.
 */
@Composable
internal fun starterCategoryLabel(category: StarterCatalog.Category): String =
    stringResource(
        when (category) {
            StarterCatalog.Category.POPULAR -> R.string.starter_category_popular
            StarterCatalog.Category.BEST_EVER -> R.string.starter_category_best_ever
            StarterCatalog.Category.SCIENCE_FICTION -> R.string.starter_category_science_fiction
            StarterCatalog.Category.FANTASY -> R.string.starter_category_fantasy
            StarterCatalog.Category.HORROR -> R.string.starter_category_horror
            StarterCatalog.Category.GOTHIC -> R.string.starter_category_gothic
            StarterCatalog.Category.ADVENTURE -> R.string.starter_category_adventure
            StarterCatalog.Category.WESTERN -> R.string.starter_category_western
            StarterCatalog.Category.HISTORICAL_FICTION ->
                R.string.starter_category_historical_fiction
            StarterCatalog.Category.MYSTERY -> R.string.starter_category_mystery
            StarterCatalog.Category.SHORT_STORIES -> R.string.starter_category_short_stories
            StarterCatalog.Category.POETRY -> R.string.starter_category_poetry
            StarterCatalog.Category.HUMOR -> R.string.starter_category_humor
            StarterCatalog.Category.PHILOSOPHY -> R.string.starter_category_philosophy
            StarterCatalog.Category.CHILDRENS -> R.string.starter_category_childrens
        },
    )

/**
 * What to start the library with: a shelf, and how much of it.
 *
 * A sheet rather than a straight connection, because there are two
 * things to say and neither has an answer that suits everybody. Both
 * open on a default, so a reader who does not care taps once more and
 * is done.
 *
 * The size matters more than it looks. Every book on the shelf is a
 * request to somebody else's free server, so this is also the control
 * that decides how long the reader waits and how much Project
 * Gutenberg is asked for.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun StarterCatalogSheet(
    onDismiss: () -> Unit,
    onConfirm: (StarterCatalog.Category, Int) -> Unit,
    connecting: Boolean,
    modifier: Modifier = Modifier,
) {
    var category by remember { mutableStateOf(StarterCatalog.Category.POPULAR) }
    var shelf by remember { mutableIntStateOf(StarterCatalog.DEFAULT_SHELF) }

    LiseurModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.starter_catalog),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.starter_catalog_sheet_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                stringResource(R.string.starter_catalog_which),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StarterCatalog.Category.entries.forEach { option ->
                    FilterChip(
                        selected = option == category,
                        onClick = { category = option },
                        enabled = !connecting,
                        label = { Text(starterCategoryLabel(option)) },
                    )
                }
            }

            Text(
                stringResource(R.string.starter_catalog_how_many),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StarterCatalog.SIZES.forEach { size ->
                    FilterChip(
                        selected = size == shelf,
                        onClick = { shelf = size },
                        enabled = !connecting,
                        label = { Text(size.toString()) },
                    )
                }
            }
            Text(
                stringResource(R.string.starter_catalog_how_many_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !connecting) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = { onConfirm(category, shelf) }, enabled = !connecting) {
                    if (connecting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Text(stringResource(R.string.starter_catalog_start))
                    }
                }
            }
        }
    }
}
