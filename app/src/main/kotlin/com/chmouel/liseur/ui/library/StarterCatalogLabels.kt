package com.chmouel.liseur.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import com.chmouel.liseur.data.opds.StarterCatalog
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
