package com.chmouel.liseur.reader

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
        backgroundColor = if (theme == ReaderTheme.BLACK) Color(theme.background.toArgb()) else null,
        textColor = if (theme == ReaderTheme.BLACK) Color(theme.foreground.toArgb()) else null,
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
 * Navigator configuration declaring the bundled reading fonts,
 * served from the app's assets.
 */
@OptIn(ExperimentalReadiumApi::class)
fun epubNavigatorConfiguration(): EpubNavigatorFragment.Configuration =
    EpubNavigatorFragment.Configuration {
        servedAssets = listOf("fonts/.*")

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
