package com.chmouel.liseur.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.compose.ui.graphics.toArgb
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.ui.WidthClass
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/** Maps the app's reading preferences onto Readium's EPUB preferences. */
@OptIn(ExperimentalReadiumApi::class)
fun ReaderPrefs.toEpubPreferences(
    columnMode: ColumnMode = this.columnMode,
): EpubPreferences =
    EpubPreferences(
        fontFamily = font.cssName?.let { FontFamily(it) },
        fontSize = fontSize,
        theme = when (theme) {
            ReaderTheme.LIGHT -> Theme.LIGHT
            ReaderTheme.SEPIA -> Theme.SEPIA
            ReaderTheme.DARK, ReaderTheme.BLACK -> Theme.DARK
        },
        // Readium's own palette is close to ours but not identical (its sepia
        // is #FAF4E8 against our #F6EFDF, its dark #000000 against our
        // #1F1F1F). The page is inset from the screen edges, so any
        // difference shows up as bands above and below the text. Pass our
        // colours for every theme so the two always match.
        backgroundColor = Color(theme.background.toArgb()),
        textColor = Color(theme.foreground.toArgb()),
        lineHeight = lineHeight,
        pageMargins = pageMargins,
        // Left unset on Auto rather than sent as ColumnCount.AUTO, so a
        // reader who never touches this setting gets exactly the page
        // Readium would have laid out on its own.
        columnCount = when (columnMode) {
            ColumnMode.AUTO -> null
            ColumnMode.ONE -> ColumnCount.ONE
            ColumnMode.TWO -> ColumnCount.TWO
        },
        // Readium CSS only applies font-size and advanced settings (line
        // height, margins…) when publisher styles are turned off.
        publisherStyles = if (fontSize != 1.0 || lineHeight != null || pageMargins != null) {
            false
        } else {
            null
        },
    )

/**
 * Column width asked for in two-column mode, as a share of the viewport.
 *
 * Anything at or below half leaves room for two columns at every width;
 * staying under it absorbs the page gutter and the column gap without
 * having to know what they are.
 */
private const val TWO_COLUMN_WIDTH_VW = 45.0

/**
 * The column mode a window this wide can actually carry out.
 *
 * A phone cannot fit two columns of text worth reading, and the control
 * that sets this is hidden below [WidthClass.MEDIUM] — so a reader who
 * picks two columns on a tablet and then rotates it, or folds it, or
 * opens the book in a narrow split-screen pane, would otherwise be stuck
 * with a setting they can no longer see or undo. Narrow windows fall
 * back to Auto, which is the untouched Readium layout.
 */
fun ColumnMode.effectiveFor(widthClass: WidthClass): ColumnMode =
    if (widthClass == WidthClass.COMPACT) ColumnMode.AUTO else this

/**
 * Navigator configuration declaring the bundled reading fonts, served from
 * the app's assets, and taking over what happens when text is selected.
 *
 * [columnMode] is also applied here, at the stylesheet's own level, and
 * not only as a user preference. Readium keeps `--USER__colCount` behind
 * a `min-width: 60em` media query, so on anything narrower than a large
 * tablet — a 7" e-ink reader, a phone held sideways — asking for two
 * columns through preferences alone does nothing. Setting the RS
 * properties instead reaches the unconditional `:root` rule.
 *
 * The column width has to go with it. `column-count` is a ceiling, not a
 * demand — the used count is the smaller of it and what the column width
 * allows — and Readium's default `--RS__colWidth` is 45em, so two columns
 * only actually appear on a screen wide enough for 90em of text. Above
 * 60em Readium answers this by setting the width to `auto`; the Kotlin
 * `Length` has no `auto`, so half the viewport does the same job: two
 * columns fit at any width, and the ceiling stops it becoming three.
 * Nothing below [WidthClass.MEDIUM] ever gets here, because
 * [effectiveFor] has already turned it back into Auto.
 *
 * These are read when the book opens rather than watched, so a change
 * lands on the next book; the preference above covers the live case on
 * screens wide enough for it.
 *
 * The system's own selection menu is refused so the app can put its own
 * bar next to the words instead: the platform bar floats where it likes,
 * offers actions a book has no use for, and cannot show highlight colours.
 */
@OptIn(ExperimentalReadiumApi::class)
fun epubNavigatorConfiguration(
    columnMode: ColumnMode = ColumnMode.Default,
    onTextSelected: () -> Unit = {},
): EpubNavigatorFragment.Configuration =
    EpubNavigatorFragment.Configuration {
        servedAssets = listOf("fonts/.*")
        readiumCssRsProperties = when (columnMode) {
            ColumnMode.AUTO -> RsProperties()
            ColumnMode.ONE -> RsProperties(colCount = ColCount.ONE)
            ColumnMode.TWO -> RsProperties(
                colCount = ColCount.TWO,
                colWidth = Length.Vw(TWO_COLUMN_WIDTH_VW),
            )
        }
        selectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                onTextSelected()
                // The mode has to be accepted or the web view drops the
                // selection along with it; emptying the menu is what keeps
                // the platform bar from ever being drawn.
                menu.clear()
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.clear()
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }

        addFontFamilyDeclaration(FontFamily(checkNotNull(ReaderFont.LITERATA.cssName))) {
            addFontFace {
                addSource("fonts/Literata.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(200..900)
            }
            addFontFace {
                addSource("fonts/Literata-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(200..900)
            }
        }

        addFontFamilyDeclaration(FontFamily(checkNotNull(ReaderFont.VOLLKORN.cssName))) {
            addFontFace {
                addSource("fonts/Vollkorn.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(400..900)
            }
            addFontFace {
                addSource("fonts/Vollkorn-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(400..900)
            }
        }

        addFontFamilyDeclaration(FontFamily(checkNotNull(ReaderFont.ATKINSON.cssName))) {
            addFontFace {
                addSource("fonts/AtkinsonHyperlegible-Regular.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(400..400)
            }
            addFontFace {
                addSource("fonts/AtkinsonHyperlegible-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(400..400)
            }
            addFontFace {
                addSource("fonts/AtkinsonHyperlegible-Bold.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(700..700)
            }
        }

        addFontFamilyDeclaration(FontFamily(checkNotNull(ReaderFont.INTER.cssName))) {
            addFontFace {
                addSource("fonts/Inter.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(100..900)
            }
            addFontFace {
                addSource("fonts/Inter-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(100..900)
            }
        }
    }
