package com.chmouel.liseur.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.chmouel.liseur.R
import com.chmouel.liseur.data.opds.StarterCatalog
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import java.util.Locale

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
 * What a language code is called, in the reader's own language.
 *
 * From ICU rather than from `strings.xml`: seventeen names across six
 * translations is a hundred strings the platform already knows, and it
 * spells each the way the reading locale spells it. Capitalised for
 * that locale, since ICU gives several languages a lowercase name and a
 * list of them reads as a mistake.
 */
internal fun starterLanguageLabel(code: String, locale: Locale): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
    return if (name.isEmpty()) code else name.replaceFirstChar { it.titlecase(locale) }
}

/**
 * Which language to fetch the shelf in.
 *
 * A menu rather than a third row of chips: fifteen categories and four
 * sizes are already as tall as this sheet should get, and a language is
 * the one question here that usually needs no answer at all.
 *
 * It opens short — the reader's own language and English — with the
 * rest behind one more tap, so the common case is a glance and the
 * uncommon one is not hidden. The menu always carries the current
 * selection even when it came from the long list, or reopening it would
 * show a menu that does not contain what is chosen.
 */
@Composable
private fun StarterLanguagePicker(
    selected: String,
    locale: Locale,
    onSelect: (String) -> Unit,
    enabled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    val device = StarterCatalog.resolveLanguage(locale.language)
    val shortList = listOf(device, StarterCatalog.DEFAULT_LANGUAGE, selected).distinct()
    val rest = remember(locale, shortList) {
        StarterCatalog.OFFERED_LANGUAGES
            .filterNot { it in shortList }
            .sortedBy { starterLanguageLabel(it, locale).lowercase(locale) }
    }
    val shown = if (showAll) shortList + rest else shortList

    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(starterLanguageLabel(selected, locale))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                open = false
                showAll = false
            },
        ) {
            shown.forEach { code ->
                DropdownMenuItem(
                    text = { Text(starterLanguageLabel(code, locale)) },
                    leadingIcon = {
                        RadioButton(selected = code == selected, onClick = null)
                    },
                    onClick = {
                        onSelect(code)
                        open = false
                        showAll = false
                    },
                )
            }
            if (!showAll && rest.isNotEmpty()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.starter_catalog_language_more)) },
                    onClick = { showAll = true },
                )
            }
        }
    }
}

/**
 * What to start the library with: a language, a shelf, and how much of
 * it.
 *
 * A sheet rather than a straight connection, because there are three
 * things to say and none has an answer that suits everybody. All three
 * open on a default, so a reader who does not care taps once more and
 * is done.
 *
 * Language comes first because it narrows everything under it: a shelf
 * picked before a language is a shelf picked in the dark, and a reader
 * whose phone is not in English needs to know the offer holds for them
 * before choosing what is on it.
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
    onConfirm: (StarterCatalog.Category, Int, String) -> Unit,
    connecting: Boolean,
    modifier: Modifier = Modifier,
) {
    // The composition's locale rather than `Locale.getDefault()`, so a
    // per-app language is what the picker opens on.
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ENGLISH
    var category by remember { mutableStateOf(StarterCatalog.Category.POPULAR) }
    var shelf by remember { mutableIntStateOf(StarterCatalog.DEFAULT_SHELF) }
    var language by remember(locale) {
        mutableStateOf(StarterCatalog.resolveLanguage(locale.language))
    }

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // Opened full rather than half. Three questions and nineteen
        // chips do not fit in a half sheet, and the half it showed cut
        // the categories mid-row, so the reader's first move was
        // always to drag it up.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
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
                stringResource(R.string.starter_catalog_language),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            StarterLanguagePicker(
                selected = language,
                locale = locale,
                onSelect = { picked ->
                    language = picked
                    // A shelf that does not exist in the new language
                    // has just left the row below, and a selection
                    // nobody can see is one the button would act on.
                    if (!category.offeredIn(picked)) {
                        category = StarterCatalog.Category.POPULAR
                    }
                },
                enabled = !connecting,
            )

            Text(
                stringResource(R.string.starter_catalog_which),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StarterCatalog.Category.entries
                    .filter { it.offeredIn(language) }
                    .forEach { option ->
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
                Button(onClick = { onConfirm(category, shelf, language) }, enabled = !connecting) {
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
