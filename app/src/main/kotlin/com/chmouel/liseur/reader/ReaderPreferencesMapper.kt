package com.chmouel.liseur.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.compose.ui.graphics.toArgb
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/** Maps the app's reading preferences onto Readium's EPUB preferences. */
@OptIn(ExperimentalReadiumApi::class)
fun ReaderPrefs.toEpubPreferences(): EpubPreferences =
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
        // Readium CSS only applies font-size and advanced settings (line
        // height, margins…) when publisher styles are turned off.
        publisherStyles = if (fontSize != 1.0 || lineHeight != null || pageMargins != null) {
            false
        } else {
            null
        },
    )

/**
 * Navigator configuration declaring the bundled reading fonts, served from
 * the app's assets, and taking over what happens when text is selected.
 *
 * The system's own selection menu is refused so the app can put its own
 * bar next to the words instead: the platform bar floats where it likes,
 * offers actions a book has no use for, and cannot show highlight colours.
 */
@OptIn(ExperimentalReadiumApi::class)
fun epubNavigatorConfiguration(
    onTextSelected: () -> Unit = {},
): EpubNavigatorFragment.Configuration =
    EpubNavigatorFragment.Configuration {
        servedAssets = listOf("fonts/.*")
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
